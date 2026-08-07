import type { components } from "../../api/generated/schema";
import { refreshAccessToken } from "../../api/client";
import {
  database,
  type PendingOperation,
  type PendingOperationState,
} from "../../offline/database";
import {
  completeReceiptUpload,
  confirmExpense,
  createExpenseDraft,
  ExpenseApiError,
  getExpense,
  prepareReceiptUpload,
  uploadReceiptBlob,
  type Expense,
  type UpdateExpenseRequest,
} from "./expenseApi";

type CreateExpenseDraftRequest =
  components["schemas"]["CreateExpenseDraftRequest"];

export type QueuedExpense = Omit<
  PendingOperation,
  "payload" | "operationType"
> & {
  id: number;
  operationType: "CREATE_EXPENSE_DRAFT";
  payload: CreateExpenseDraftRequest;
};

export type QueuedExpenseConfirmation = Omit<
  PendingOperation,
  "payload" | "operationType" | "resourceId" | "targetVersion"
> & {
  id: number;
  operationType: "CONFIRM_EXPENSE";
  payload: UpdateExpenseRequest;
  resourceId: number;
  targetVersion: number;
};

export type QueuedExpenseOperation = QueuedExpense | QueuedExpenseConfirmation;

const activeScopes = new Set<string>();
const requestedScopes = new Set<string>();

function scopeKey(firebaseUid: string, tripId: number) {
  return `${firebaseUid}:${tripId}`;
}

function isDraftPayload(value: unknown): value is CreateExpenseDraftRequest {
  if (!value || typeof value !== "object") return false;
  const payload = value as Record<string, unknown>;
  return (
    (typeof payload.amount === "number" && payload.amount > 0) ||
    payload.hasReceipt === true
  );
}

export async function enqueueExpenseDraft(
  firebaseUid: string,
  tripId: number,
  payload: CreateExpenseDraftRequest,
  receipt?: Blob,
  idempotencyKey: string = crypto.randomUUID(),
) {
  const receiptBlobId = receipt ? crypto.randomUUID() : undefined;
  return database.transaction(
    "rw",
    database.pendingOperations,
    database.receiptBlobs,
    async () => {
      if (receipt && receiptBlobId) {
        await database.receiptBlobs.add({
          id: receiptBlobId,
          firebaseUid,
          tripId,
          blob: receipt,
          contentType: receipt.type as
            "image/jpeg" | "image/png" | "image/webp",
          createdAt: new Date().toISOString(),
        });
      }
      return database.pendingOperations.add({
        firebaseUid,
        tripId,
        method: "POST",
        path: `/api/trips/${tripId}/expenses`,
        payload,
        idempotencyKey,
        receiptBlobId,
        operationType: "CREATE_EXPENSE_DRAFT",
        createdAt: new Date().toISOString(),
        retryCount: 0,
        state: "PENDING",
      });
    },
  );
}

export async function enqueueExpenseConfirmation(
  firebaseUid: string,
  tripId: number,
  expenseId: number,
  payload: UpdateExpenseRequest,
  state: PendingOperationState = "PENDING",
  lastProblem?: string,
) {
  return database.pendingOperations.add({
    firebaseUid,
    tripId,
    method: "PATCH",
    path: `/api/trips/${tripId}/expenses/${expenseId}`,
    payload,
    idempotencyKey: crypto.randomUUID(),
    operationType: "CONFIRM_EXPENSE",
    resourceId: expenseId,
    targetVersion: payload.version,
    createdAt: new Date().toISOString(),
    retryCount: 0,
    state,
    lastProblem,
  });
}

function isConfirmationPayload(value: unknown): value is UpdateExpenseRequest {
  if (!value || typeof value !== "object") return false;
  const payload = value as Record<string, unknown>;
  return (
    typeof payload.version === "number" &&
    typeof payload.amount === "number" &&
    payload.status === "CONFIRMED" &&
    Array.isArray(payload.shares)
  );
}

export async function listQueuedExpenses(
  firebaseUid: string,
  tripId: number,
): Promise<QueuedExpenseOperation[]> {
  const operations = await database.pendingOperations
    .where("[firebaseUid+tripId]")
    .equals([firebaseUid, tripId])
    .sortBy("createdAt");
  return operations.flatMap<QueuedExpenseOperation>((operation) => {
    if (operation.id == null) return [];
    if (
      operation.operationType === "CREATE_EXPENSE_DRAFT" &&
      isDraftPayload(operation.payload)
    )
      return [{ ...operation, payload: operation.payload } as QueuedExpense];
    if (
      operation.operationType === "CONFIRM_EXPENSE" &&
      operation.resourceId != null &&
      operation.targetVersion != null &&
      isConfirmationPayload(operation.payload)
    )
      return [
        {
          ...operation,
          payload: operation.payload,
          resourceId: operation.resourceId,
          targetVersion: operation.targetVersion,
        } as QueuedExpenseConfirmation,
      ];
    return [];
  });
}

function failureState(error: unknown): PendingOperationState {
  if (!(error instanceof ExpenseApiError)) return "PENDING";
  if (error.status === 409) return "CONFLICT";
  if (error.status >= 400 && error.status < 500 && error.status !== 401)
    return "NEEDS_CORRECTION";
  return "PENDING";
}

async function withAuthenticationRetry<T>(operation: () => Promise<T>) {
  try {
    return await operation();
  } catch (error) {
    if (!(error instanceof ExpenseApiError) || error.status !== 401)
      throw error;
    await refreshAccessToken();
    return operation();
  }
}

async function uploadQueuedReceipt(operation: QueuedExpense, expense: Expense) {
  if (!operation.receiptBlobId) return expense;
  const stored = await database.receiptBlobs.get(operation.receiptBlobId);
  if (!stored) {
    throw new ExpenseApiError(422, {
      type: "about:blank",
      title: "Receipt missing",
      status: 422,
      code: "LOCAL_RECEIPT_MISSING",
      message: "端末に保存したレシート画像が見つかりません。",
      traceId: "local",
    });
  }
  if (expense.receipts.some((receipt) => receipt.status === "UPLOADED"))
    return expense;
  const capability = await withAuthenticationRetry(() =>
    prepareReceiptUpload(operation.tripId, expense, stored.blob),
  );
  await uploadReceiptBlob(
    capability.uploadUrl,
    capability.requiredHeaders,
    stored.blob,
  );
  return withAuthenticationRetry(() =>
    completeReceiptUpload(
      operation.tripId,
      expense.id,
      capability.receiptId,
      capability.expenseVersion,
    ),
  );
}

export async function flushExpenseQueue(
  firebaseUid: string,
  tripId: number,
  onSuccess: (expense: Expense) => void,
) {
  const scope = scopeKey(firebaseUid, tripId);
  if (activeScopes.has(scope)) {
    requestedScopes.add(scope);
    return;
  }
  activeScopes.add(scope);
  try {
    const operations = await listQueuedExpenses(firebaseUid, tripId);
    for (const operation of operations) {
      if (
        operation.state === "CONFLICT" ||
        operation.state === "NEEDS_CORRECTION"
      )
        continue;
      await database.pendingOperations.update(operation.id, {
        state: "RETRYING",
        retryCount: operation.retryCount + 1,
      });
      try {
        if (operation.operationType === "CONFIRM_EXPENSE") {
          const expense = await withAuthenticationRetry(() =>
            confirmExpense(tripId, operation.resourceId, operation.payload),
          );
          onSuccess(expense);
          await database.pendingOperations.delete(operation.id);
          continue;
        }
        let expense = operation.resourceId
          ? await withAuthenticationRetry(() =>
              getExpense(tripId, operation.resourceId!),
            )
          : await withAuthenticationRetry(() =>
              createExpenseDraft(
                tripId,
                operation.idempotencyKey,
                operation.payload,
              ),
            );
        if (!operation.resourceId) {
          await database.pendingOperations.update(operation.id, {
            resourceId: expense.id,
            targetVersion: expense.version,
          });
        }
        expense = await uploadQueuedReceipt(operation, expense);
        onSuccess(expense);
        await database.transaction(
          "rw",
          database.pendingOperations,
          database.receiptBlobs,
          async () => {
            await database.pendingOperations.delete(operation.id);
            if (operation.receiptBlobId)
              await database.receiptBlobs.delete(operation.receiptBlobId);
          },
        );
      } catch (error) {
        const state = failureState(error);
        await database.pendingOperations.update(operation.id, {
          state,
          lastProblem:
            error instanceof Error ? error.message : "オンライン復帰待ち",
        });
        if (state === "PENDING") break;
      }
    }
  } finally {
    activeScopes.delete(scope);
    if (requestedScopes.delete(scope))
      await flushExpenseQueue(firebaseUid, tripId, onSuccess);
  }
}

export async function retryQueuedExpense(id: number) {
  await database.pendingOperations.update(id, {
    state: "PENDING",
    lastProblem: undefined,
  });
}

export async function discardQueuedExpense(id: number) {
  const operation = await database.pendingOperations.get(id);
  if (!operation) return;
  await database.transaction(
    "rw",
    database.pendingOperations,
    database.receiptBlobs,
    async () => {
      await database.pendingOperations.delete(id);
      if (operation.receiptBlobId)
        await database.receiptBlobs.delete(operation.receiptBlobId);
    },
  );
}
