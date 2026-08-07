import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";

import { database } from "../../offline/database";
import { renderWithProviders } from "../../test/renderWithProviders";
import {
  amountExpenseDraft,
  tokyoTripSnapshot,
} from "../../test/mocks/fixtures";
import { server } from "../../test/mocks/server";
import { ExpensePage } from "./ExpensePage";

function TripContext() {
  return <Outlet context={tokyoTripSnapshot} />;
}

function renderPage() {
  return renderWithProviders(
    <MemoryRouter initialEntries={["/t/42/expenses"]}>
      <Routes>
        <Route path="/t/:tripId" element={<TripContext />}>
          <Route path="expenses" element={<ExpensePage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  server.use(
    http.get("*/api/trips/42/expenses", () =>
      HttpResponse.json({ items: [amountExpenseDraft], nextCursor: null }),
    ),
  );
});

afterEach(async () => {
  cleanup();
  await database.pendingOperations.clear();
  await database.receiptBlobs.clear();
});

describe("ExpensePage", () => {
  it("未確定トレイから全員按分を確認し連続確定する", async () => {
    let submitted: Record<string, unknown> | undefined;
    server.use(
      http.patch("*/api/trips/42/expenses/801", async ({ request }) => {
        submitted = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          ...amountExpenseDraft,
          ...submitted,
          status: "CONFIRMED",
          currency: "JPY",
          baseAmount: 1280,
          version: 1,
          shares: [
            { memberId: 7, weight: 1, finalAmount: 427 },
            { memberId: 8, weight: 1, finalAmount: 427 },
            { memberId: 9, weight: 1, finalAmount: 426 },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderPage();

    expect(
      await screen.findByRole("heading", { name: "未確定の支出" }),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: /1,280/ }));
    const confirmTitle = screen.getByRole("heading", {
      name: "この支出を確定",
    });
    await waitFor(() => expect(confirmTitle).toHaveFocus());
    expect(screen.getByText("￥426〜")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "確定して次へ" }));

    await waitFor(() => expect(submitted).toBeDefined());
    expect(submitted).toMatchObject({
      version: 0,
      payerId: 7,
      amount: 1280,
      allocationType: "EQUAL",
      status: "CONFIRMED",
      shares: [
        { memberId: 7, weight: 1 },
        { memberId: 8, weight: 1 },
        { memberId: 9, weight: 1 },
      ],
    });
    expect(
      await screen.findByRole("heading", { name: "確定済み" }),
    ).toBeVisible();
  });

  it("前回と同じプリセットで現在の旅行メンバーだけを選ぶ", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /1,280/ }));
    await user.click(screen.getByRole("button", { name: "前回と同じ" }));

    expect(
      await screen.findByText("前回と同じ負担メンバーを反映しました。"),
    ).toBeVisible();
    expect(screen.getByText("￥640〜")).toBeVisible();
    expect(screen.getByRole("checkbox", { name: "ケン" })).not.toBeChecked();
  });

  it("409を自動上書きせず最新内容への導線を出す", async () => {
    server.use(
      http.patch("*/api/trips/42/expenses/801", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Conflict",
            status: 409,
            code: "VERSION_CONFLICT",
            message: "更新されています。",
            traceId: "test-trace",
            currentVersion: 2,
          },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /1,280/ }));
    await user.click(screen.getByRole("button", { name: "確定して次へ" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "ほかの端末で支出が更新",
    );
    expect(
      screen.getByRole("button", { name: "最新内容を読み込む" }),
    ).toBeVisible();
  });

  it("支出がないとき記録入口だけを明確に表示する", async () => {
    server.use(
      http.get("*/api/trips/42/expenses", () =>
        HttpResponse.json({ items: [], nextCursor: null }),
      ),
    );
    renderPage();

    expect(await screen.findByText("支出はまだありません")).toBeVisible();
    expect(
      screen.getByRole("link", { name: "最初の支出を記録" }),
    ).toHaveAttribute("href", "/t/42/expenses/new");
    expect(
      screen.queryByRole("heading", { name: "未確定の支出" }),
    ).not.toBeInTheDocument();
  });
});
