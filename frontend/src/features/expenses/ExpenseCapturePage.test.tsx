import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";

import { renderWithProviders } from "../../test/renderWithProviders";
import { server } from "../../test/mocks/server";
import { database } from "../../offline/database";
import { ExpenseCapturePage } from "./ExpenseCapturePage";

afterEach(async () => {
  cleanup();
  await database.pendingOperations.clear();
  await database.receiptBlobs.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderPage() {
  return renderWithProviders(
    <MemoryRouter initialEntries={["/t/42/expenses/new"]}>
      <Routes>
        <Route
          path="/t/:tripId/expenses/new"
          element={<ExpenseCapturePage />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe("ExpenseCapturePage", () => {
  it("カメラ入力とファイル選択fallbackを提供する", () => {
    renderPage();
    expect(screen.getByLabelText("カメラで撮影")).toHaveAttribute(
      "capture",
      "environment",
    );
    expect(screen.getByLabelText("写真ライブラリから選ぶ")).not.toHaveAttribute(
      "capture",
    );
  });

  it("長辺1600pxに圧縮したプレビューを表示する", async () => {
    const close = vi.fn();
    vi.stubGlobal(
      "createImageBitmap",
      vi.fn().mockResolvedValue({ width: 2400, height: 1200, close }),
    );
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({
      drawImage: vi.fn(),
    } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(
      (callback) => callback(new Blob(["compressed"], { type: "image/jpeg" })),
    );
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:preview"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    const user = userEvent.setup();
    renderPage();
    await user.upload(
      screen.getByLabelText("写真ライブラリから選ぶ"),
      new File(["image"], "receipt.png", { type: "image/png" }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("1600×800px");
    expect(
      screen.getByRole("img", { name: "圧縮したレシートのプレビュー" }),
    ).toBeVisible();
    expect(close).toHaveBeenCalled();
  });

  it("金額をgenerated API contractでDRAFT作成する", async () => {
    let body: Record<string, unknown> | undefined;
    let idempotencyKey = "";
    server.use(
      http.post("*/api/trips/42/expenses", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        idempotencyKey = request.headers.get("Idempotency-Key") ?? "";
        return HttpResponse.json(
          {
            id: 801,
            createdByMemberId: 7,
            amount: 1280,
            source: "MANUAL",
            status: "DRAFT",
            receipts: [],
            shares: [],
            version: 0,
          },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("button", { name: /金額を入れる/ }));
    const input = screen.getByRole("spinbutton", { name: "支出の総額" });
    expect(input).toHaveFocus();
    await user.type(input, "1280");
    await user.click(screen.getByRole("button", { name: "未確定として記録" }));
    expect(await screen.findByRole("status")).toHaveTextContent(
      "未確定の支出として記録",
    );
    await waitFor(() =>
      expect(body).toMatchObject({ amount: 1280, source: "MANUAL" }),
    );
    expect(idempotencyKey).not.toBe("");
  });

  it("0円を送信せず入力エラーを案内する", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("button", { name: /金額を入れる/ }));
    await user.type(
      screen.getByRole("spinbutton", { name: "支出の総額" }),
      "0",
    );
    await user.click(screen.getByRole("button", { name: "未確定として記録" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("1円以上");
  });
});
