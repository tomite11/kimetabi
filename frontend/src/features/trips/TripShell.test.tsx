import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";

import { renderWithProviders } from "../../test/renderWithProviders";
import { tokyoTripSnapshot } from "../../test/mocks/fixtures";
import { server } from "../../test/mocks/server";
import { ExpensePage } from "./ExpensePage";
import { PlanPage } from "./PlanPage";
import { CandidateComparisonPage } from "../planning/CandidateComparisonPage";
import { TripHomePage } from "./TripHomePage";
import { TripShell } from "./TripShell";

afterEach(cleanup);

function renderShell(path = "/t/42") {
  return renderWithProviders(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/t/:tripId" element={<TripShell />}>
          <Route index element={<TripHomePage />} />
          <Route path="plan" element={<PlanPage />} />
          <Route path="plan/:slotId" element={<CandidateComparisonPage />} />
          <Route path="expenses" element={<ExpensePage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("TripShell", () => {
  it("固定3タブと計画中の主アクションを表示する", async () => {
    renderShell();
    expect(
      await screen.findByRole("heading", { name: "東京の旅" }),
    ).toBeVisible();
    const navigation = screen.getByRole("navigation", {
      name: "旅行のメインナビゲーション",
    });
    expect(navigation).toHaveTextContent("ホーム旅程支出");
    expect(screen.getByRole("link", { name: "ホーム" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("button", { name: "候補を追加" })).toBeVisible();
  });

  it("キーボードで旅程タブへ移動して空でない旅程を表示する", async () => {
    const user = userEvent.setup();
    renderShell();
    const plan = await screen.findByRole("link", { name: "旅程" });
    plan.focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("heading", { name: "旅程" })).toBeVisible();
    expect(plan).toHaveAttribute("aria-current", "page");
    expect(screen.getByText("往路の移動")).toBeVisible();
  });

  it("旅程の枠をキーボードで開いて候補比較へ進める", async () => {
    const user = userEvent.setup();
    renderShell("/t/42/plan");
    const slot = await screen.findByRole("link", { name: /往路の移動/ });
    slot.focus();
    await user.keyboard("{Enter}");
    expect(
      await screen.findByRole("heading", { name: "往路の移動" }),
    ).toBeVisible();
  });

  it("採択済みのplan itemを旅程に表示する", async () => {
    server.use(
      http.get("*/api/trips/42", () =>
        HttpResponse.json({
          ...tokyoTripSnapshot,
          slots: [
            {
              ...tokyoTripSnapshot.slots[0],
              status: "DECIDED",
              adoptedCandidateId: 501,
            },
          ],
          planItems: [
            {
              id: 301,
              slotId: 101,
              fromCandidateId: 501,
              title: "朝の新幹線",
              startsAt: null,
              timezone: "Asia/Tokyo",
              placeRef: null,
              version: 0,
            },
          ],
        }),
      ),
    );
    renderShell("/t/42/plan");

    expect(await screen.findByText("確定予定: 朝の新幹線")).toBeVisible();
  });

  it("支出の空状態でもタブ位置と主アクションを維持する", async () => {
    renderShell("/t/42/expenses");
    expect(await screen.findByText("支出はまだありません")).toBeVisible();
    expect(screen.getByRole("link", { name: "支出" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("button", { name: "候補を追加" })).toBeVisible();
  });
});
