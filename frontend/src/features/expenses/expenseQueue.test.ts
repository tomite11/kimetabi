import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { database } from "../../offline/database";
import {
  enqueueExpenseDraft,
  enqueueExpenseConfirmation,
  flushExpenseQueue,
  listQueuedExpenses,
} from "./expenseQueue";
import { ExpenseApiError } from "./expenseApi";

const api = vi.hoisted(() => ({
  createExpenseDraft: vi.fn(),
  getExpense: vi.fn(),
  prepareReceiptUpload: vi.fn(),
  uploadReceiptBlob: vi.fn(),
  completeReceiptUpload: vi.fn(),
  confirmExpense: vi.fn(),
  refreshAccessToken: vi.fn(),
}));

vi.mock("../../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api/client")>()),
  refreshAccessToken: api.refreshAccessToken,
}));

vi.mock("./expenseApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./expenseApi")>()),
  createExpenseDraft: api.createExpenseDraft,
  getExpense: api.getExpense,
  prepareReceiptUpload: api.prepareReceiptUpload,
  uploadReceiptBlob: api.uploadReceiptBlob,
  completeReceiptUpload: api.completeReceiptUpload,
  confirmExpense: api.confirmExpense,
}));

const draft = {
  id: 801,
  createdByMemberId: 7,
  payerId: null,
  amount: 1200,
  currency: null,
  baseAmount: null,
  paidAt: "2026-08-07T00:00:00Z",
  source: "MANUAL" as const,
  status: "DRAFT" as const,
  allocationType: null,
  receipts: [],
  shares: [],
  version: 0,
};

beforeEach(async () => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
  await database.pendingOperations.clear();
  await database.receiptBlobs.clear();
});

afterEach(async () => {
  await database.pendingOperations.clear();
  await database.receiptBlobs.clear();
});

describe("expenseQueue", () => {
  it("同じIdempotency-KeyとBlobを通信復帰まで保持する", async () => {
    api.createExpenseDraft.mockRejectedValue(new TypeError("offline"));
    const blob = new Blob(["receipt"], { type: "image/jpeg" });
    await enqueueExpenseDraft(
      "user-1",
      42,
      { hasReceipt: true, source: "MANUAL" },
      blob,
      "fixed-key",
    );

    await flushExpenseQueue("user-1", 42, vi.fn());

    const [operation] = await listQueuedExpenses("user-1", 42);
    expect(operation).toMatchObject({
      idempotencyKey: "fixed-key",
      state: "PENDING",
      retryCount: 1,
    });
    expect(
      await database.receiptBlobs.get(operation.receiptBlobId!),
    ).toMatchObject({
      blob,
    });
  });

  it("成功確認後だけ操作を削除する", async () => {
    api.createExpenseDraft.mockResolvedValue(draft);
    const onSuccess = vi.fn();
    await enqueueExpenseDraft(
      "user-1",
      42,
      { amount: 1200, source: "MANUAL" },
      undefined,
      "fixed-key",
    );

    await flushExpenseQueue("user-1", 42, onSuccess);

    expect(api.createExpenseDraft).toHaveBeenCalledWith(
      42,
      "fixed-key",
      expect.objectContaining({ amount: 1200 }),
    );
    expect(onSuccess).toHaveBeenCalledWith(draft);
    expect(await listQueuedExpenses("user-1", 42)).toEqual([]);
  });

  it("写真DRAFTを作成し署名uploadとcompletionの成功後にBlobを削除する", async () => {
    const blob = new Blob(["receipt"], { type: "image/jpeg" });
    api.createExpenseDraft.mockResolvedValue(draft);
    api.prepareReceiptUpload.mockResolvedValue({
      receiptId: "00000000-0000-0000-0000-000000000001",
      uploadUrl: "https://storage.example/upload",
      expiresAt: "2026-08-07T01:10:00Z",
      requiredHeaders: { "Content-Type": "image/jpeg" },
      expenseVersion: 1,
    });
    api.completeReceiptUpload.mockResolvedValue({
      ...draft,
      version: 2,
      receipts: [
        {
          id: "00000000-0000-0000-0000-000000000001",
          contentType: "image/jpeg",
          byteSize: blob.size,
          status: "UPLOADED",
        },
      ],
    });
    await enqueueExpenseDraft(
      "user-1",
      42,
      { hasReceipt: true, source: "MANUAL" },
      blob,
    );
    vi.spyOn(database.receiptBlobs, "get").mockResolvedValue({
      id: (await listQueuedExpenses("user-1", 42))[0].receiptBlobId!,
      firebaseUid: "user-1",
      tripId: 42,
      blob,
      contentType: "image/jpeg",
      createdAt: "2026-08-07T00:00:00Z",
    });

    await flushExpenseQueue("user-1", 42, vi.fn());

    expect(api.prepareReceiptUpload).toHaveBeenCalledWith(42, draft, blob);
    expect(api.uploadReceiptBlob).toHaveBeenCalledWith(
      "https://storage.example/upload",
      { "Content-Type": "image/jpeg" },
      blob,
    );
    expect(api.completeReceiptUpload).toHaveBeenCalledWith(
      42,
      801,
      "00000000-0000-0000-0000-000000000001",
      1,
    );
    expect(await database.receiptBlobs.count()).toBe(0);
    expect(await database.pendingOperations.count()).toBe(0);
  });

  it.each([
    [409, "CONFLICT"],
    [422, "NEEDS_CORRECTION"],
  ] as const)("HTTP %sを%sとして保持する", async (status, state) => {
    api.createExpenseDraft.mockRejectedValue(
      new ExpenseApiError(status, undefined),
    );
    await enqueueExpenseDraft("user-1", 42, {
      amount: 1200,
      source: "MANUAL",
    });

    await flushExpenseQueue("user-1", 42, vi.fn());

    expect((await listQueuedExpenses("user-1", 42))[0].state).toBe(state);
  });

  it("401でtokenを更新し同じ操作を再送する", async () => {
    api.createExpenseDraft
      .mockRejectedValueOnce(new ExpenseApiError(401, undefined))
      .mockResolvedValueOnce(draft);
    await enqueueExpenseDraft(
      "user-1",
      42,
      { amount: 1200, source: "MANUAL" },
      undefined,
      "fixed-key",
    );

    await flushExpenseQueue("user-1", 42, vi.fn());

    expect(api.refreshAccessToken).toHaveBeenCalledOnce();
    expect(api.createExpenseDraft).toHaveBeenCalledTimes(2);
    expect(api.createExpenseDraft.mock.calls[0][1]).toBe("fixed-key");
    expect(api.createExpenseDraft.mock.calls[1][1]).toBe("fixed-key");
  });

  it("確定操作の対象versionを保持し競合時に自動上書きしない", async () => {
    api.confirmExpense.mockRejectedValue(new ExpenseApiError(409, undefined));
    await enqueueExpenseConfirmation("user-1", 42, 801, {
      version: 3,
      amount: 1200,
      payerId: 7,
      paidAt: "2026-08-07T00:00:00Z",
      status: "CONFIRMED",
      allocationType: "EQUAL",
      shares: [{ memberId: 7, weight: 1 }],
    });

    await flushExpenseQueue("user-1", 42, vi.fn());

    const [operation] = await listQueuedExpenses("user-1", 42);
    expect(operation).toMatchObject({
      operationType: "CONFIRM_EXPENSE",
      resourceId: 801,
      targetVersion: 3,
      state: "CONFLICT",
    });
    expect(api.confirmExpense).toHaveBeenCalledOnce();
  });
});
