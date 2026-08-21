import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";

import type { components } from "../../api/generated/schema";
import { setAccessTokenProvider } from "../../api/client";
import { renderWithProviders } from "../../test/renderWithProviders";
import { settlementDraft, tokyoTripSnapshot } from "../../test/mocks/fixtures";
import { server } from "../../test/mocks/server";
import { SettlementPage } from "./SettlementPage";

type TripSnapshot = components["schemas"]["TripSnapshot"];

function renderPage(snapshot: TripSnapshot = tokyoTripSnapshot) {
  return renderWithProviders(
    <MemoryRouter initialEntries={["/t/42/settle"]}>
      <Routes>
        <Route path="/t/:tripId" element={<Outlet context={snapshot} />}>
          <Route path="settle" element={<SettlementPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => {
  setAccessTokenProvider();
  cleanup();
});

describe("SettlementPage", () => {
  it("支出集計と精算DRAFTを表示して管理者が確定する", async () => {
    let submitted: unknown;
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [settlementDraft], nextCursor: null }),
      ),
      http.post(
        "*/api/trips/42/settlements/901/confirmation",
        async ({ request }) => {
          submitted = await request.json();
          return HttpResponse.json({
            ...settlementDraft,
            status: "CONFIRMED",
            version: 1,
          });
        },
      ),
    );
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("￥238,640")).toBeVisible();
    expect(screen.getByText("2件")).toBeVisible();
    expect(
      screen.getByText("送金回数を抑えた精算", { exact: false }),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: "精算を確定" }));
    await waitFor(() => expect(submitted).toEqual({ version: 0 }));
    expect(screen.getAllByText("支払い待ち")).toHaveLength(2);
  });

  it("送金元本人が支払済み、送金先本人が受取確認できる", async () => {
    const paid = {
      ...settlementDraft,
      status: "CONFIRMED" as const,
      version: 1,
    };
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [paid], nextCursor: null }),
      ),
      http.patch(
        "*/api/trips/42/settlements/901/transfers/1001",
        async ({ request }) => {
          const body = (await request.json()) as { status: string };
          return HttpResponse.json({
            ...paid,
            transfers: paid.transfers.map((transfer) =>
              transfer.id === 1001
                ? { ...transfer, status: body.status, version: 1 }
                : transfer,
            ),
          });
        },
      ),
    );
    const memberSnapshot = {
      ...tokyoTripSnapshot,
      currentMemberId: 8,
    } satisfies TripSnapshot;
    const user = userEvent.setup();
    renderPage(memberSnapshot);

    await user.click(
      await screen.findByRole("button", { name: "支払い済みにする" }),
    );
    expect(await screen.findByText("受取確認待ち")).toBeVisible();
    cleanup();

    const received = {
      ...paid,
      transfers: paid.transfers.map((transfer) =>
        transfer.id === 1001
          ? { ...transfer, status: "PAID" as const, version: 1 }
          : transfer,
      ),
    };
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [received], nextCursor: null }),
      ),
    );
    renderPage();
    expect(
      await screen.findByRole("button", { name: "受取を確認する" }),
    ).toBeVisible();
  });

  it("未反映変更から再計算し旧精算を履歴表示する", async () => {
    const changed = {
      ...settlementDraft,
      status: "CONFIRMED" as const,
      hasUnappliedChanges: true,
      version: 1,
    };
    const recalculated = {
      ...settlementDraft,
      id: 902,
      expenseTotal: 240_000,
      calculatedAt: "2026-09-24T03:00:00Z",
    };
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [changed], nextCursor: null }),
      ),
      http.post("*/api/trips/42/settlements", () =>
        HttpResponse.json(recalculated, { status: 201 }),
      ),
    );
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("未反映の変更があります")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "再計算する" }));
    expect(await screen.findByText("￥240,000")).toBeVisible();
    expect(screen.getByRole("heading", { name: "以前の精算" })).toBeVisible();
    expect(screen.getByText("2件 · ￥238,640")).toBeVisible();
  });

  it("空状態で権限に応じた作成導線を表示する", async () => {
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [], nextCursor: null }),
      ),
    );
    renderPage();
    expect(await screen.findByText("精算案はまだありません")).toBeVisible();
    expect(screen.getByRole("button", { name: "精算案を作る" })).toBeVisible();
    cleanup();

    renderPage({ ...tokyoTripSnapshot, currentMemberId: 8 });
    expect(
      await screen.findByText("精算案はOWNERまたはORGANIZERが作成できます。"),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "精算案を作る" }),
    ).not.toBeInTheDocument();
  });

  it("409を上書きせず最新内容への導線を表示する", async () => {
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [settlementDraft], nextCursor: null }),
      ),
      http.post("*/api/trips/42/settlements/901/confirmation", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Conflict",
            status: 409,
            code: "VERSION_CONFLICT",
            message: "更新されています。",
            traceId: "test-trace",
          },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "精算を確定" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "ほかの端末で精算が更新",
    );
    expect(
      screen.getByRole("button", { name: "最新内容を読み込む" }),
    ).toBeVisible();
  });

  it("401では認証情報を1回更新して同じ操作を再試行する", async () => {
    let attempts = 0;
    const refreshes: boolean[] = [];
    setAccessTokenProvider(async (forceRefresh) => {
      if (forceRefresh) refreshes.push(true);
      return "test-token";
    });
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [settlementDraft], nextCursor: null }),
      ),
      http.post("*/api/trips/42/settlements/901/confirmation", () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json(
              { status: 401, message: "認証の更新が必要です。" },
              { status: 401 },
            )
          : HttpResponse.json({
              ...settlementDraft,
              status: "CONFIRMED",
              version: 1,
            });
      }),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "精算を確定" }));

    await waitFor(() => expect(attempts).toBe(2));
    expect(refreshes).toEqual([true]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("403では現在の精算を維持して認可エラーを表示する", async () => {
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({ items: [settlementDraft], nextCursor: null }),
      ),
      http.post("*/api/trips/42/settlements/901/confirmation", () =>
        HttpResponse.json(
          { status: 403, message: "精算を確定する権限がありません。" },
          { status: 403 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "精算を確定" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "精算を確定する権限がありません。",
    );
    expect(screen.getByRole("button", { name: "精算を確定" })).toBeVisible();
  });

  it("完了状態と送金不要状態を明確に表示する", async () => {
    server.use(
      http.get("*/api/trips/42/settlements", () =>
        HttpResponse.json({
          items: [
            {
              ...settlementDraft,
              status: "COMPLETED",
              transfers: [],
              version: 1,
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    renderPage();
    expect(await screen.findByText("精算が完了しました")).toBeVisible();
    expect(screen.getByText("送金は必要ありません")).toBeVisible();
  });
});
