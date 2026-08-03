import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";

import { renderWithProviders } from "../../test/renderWithProviders";
import {
  tokyoTripSnapshot,
  transportSlotDetail,
} from "../../test/mocks/fixtures";
import { server } from "../../test/mocks/server";
import { TripShell } from "../trips/TripShell";
import { CandidateComparisonPage } from "./CandidateComparisonPage";

afterEach(cleanup);

function renderPage() {
  return renderWithProviders(
    <MemoryRouter initialEntries={["/t/42/plan/101"]}>
      <Routes>
        <Route path="/t/:tripId" element={<TripShell />}>
          <Route path="plan/:slotId" element={<CandidateComparisonPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("CandidateComparisonPage", () => {
  it("generated API dataで候補を比較し仮選択と採択を分離する", async () => {
    const user = userEvent.setup();
    renderPage();

    expect(
      await screen.findByRole("heading", { name: "往路の移動" }),
    ).toBeVisible();
    expect(screen.getAllByText("¥12,000").length).toBeGreaterThan(0);
    expect(
      screen.getByRole("button", { name: "この候補を決定" }),
    ).toBeVisible();

    await user.click(
      screen.getByRole("button", { name: "ゆっくり新幹線を仮選択" }),
    );
    expect(screen.getByLabelText("旅行予算")).toHaveTextContent("1人 ¥10,000");
    await user.click(screen.getByRole("button", { name: "この候補を決定" }));
    expect(
      await screen.findByRole("button", { name: "この候補に変更" }),
    ).toBeVisible();
  });

  it("むりの投票では理由を入力するまで送信できない", async () => {
    const user = userEvent.setup();
    renderPage();
    const noButton = (
      await screen.findAllByRole("button", { name: "むり" })
    )[0];
    await user.click(noButton);

    const submit = screen.getByRole("button", { name: "理由を添えて投票" });
    expect(submit).toBeDisabled();
    await user.type(
      screen.getByRole("textbox", { name: "「むり」の理由" }),
      "時間が遅い",
    );
    expect(submit).toBeEnabled();
    await user.click(submit);
    expect(await screen.findByText(/むり 1/)).toBeVisible();
  });

  it("候補がない場合も追加フォームを表示する", async () => {
    server.use(
      http.get("*/api/trips/42/slots/101", () =>
        HttpResponse.json({
          ...transportSlotDetail,
          candidates: [],
          votesByCandidate: {},
        }),
      ),
    );
    renderPage();

    expect(await screen.findByText("候補はまだありません")).toBeVisible();
    expect(screen.getByRole("button", { name: "この枠に追加" })).toBeVisible();
  });

  it("一般メンバーには候補採択を表示しない", async () => {
    server.use(
      http.get("*/api/trips/42", () =>
        HttpResponse.json({ ...tokyoTripSnapshot, currentMemberId: 8 }),
      ),
    );
    renderPage();

    expect(
      await screen.findByRole("heading", { name: "往路の移動" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "この候補を決定" }),
    ).not.toBeInTheDocument();
  });

  it("採択の競合時に現在値の再読み込みを案内する", async () => {
    server.use(
      http.put("*/api/trips/42/slots/101/adoption", () =>
        HttpResponse.json(
          { code: "SLOT_VERSION_CONFLICT", message: "更新が競合しました" },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(
      await screen.findByRole("button", { name: "この候補を決定" }),
    );
    expect(
      await screen.findByText("ほかのメンバーが先に候補を決定しました。"),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "現在値を再読み込み" }),
    ).toBeVisible();
  });
});
