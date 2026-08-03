import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";

import { renderWithProviders } from "../../test/renderWithProviders";
import { server } from "../../test/mocks/server";
import { JoinPage } from "./JoinPage";

afterEach(cleanup);

function renderJoinPage() {
  return renderWithProviders(
    <MemoryRouter initialEntries={["/join/invitation-token-long-enough"]}>
      <Routes>
        <Route path="/join/:inviteToken" element={<JoinPage />} />
        <Route path="/t/:tripId" element={<h1>旅行ホーム</h1>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("JoinPage", () => {
  it("名前だけを送信して旅行ホームへ進む", async () => {
    const user = userEvent.setup();
    let body: unknown;
    server.use(
      http.post("*/api/invitations/accept", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(
          {
            trip: {
              id: 42,
              title: "東京の旅",
              destination: "東京",
              startsOn: "2026-09-20",
              endsOn: "2026-09-22",
              timezone: "Asia/Tokyo",
              expectedMemberCount: 3,
              phase: "PLANNING",
              phaseOverride: null,
              voteVisibility: "ANONYMOUS",
              budgetCap: null,
              revision: 1,
              version: 1,
            },
            members: [],
            slots: [],
          },
          { status: 201 },
        );
      }),
    );

    renderJoinPage();
    expect(
      screen.getByRole("heading", { name: "名前だけで、旅に合流。" }),
    ).toHaveFocus();
    await user.type(screen.getByLabelText("表示名"), " ユイ ");
    await user.click(
      screen.getByRole("button", { name: "この名前で参加する" }),
    );

    expect(
      await screen.findByRole("heading", { name: "旅行ホーム" }),
    ).toBeVisible();
    expect(body).toEqual({
      token: "invitation-token-long-enough",
      name: "ユイ",
    });
  });

  it("使用できない招待を具体的に案内する", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("*/api/invitations/accept", () =>
        HttpResponse.json({ status: 404 }, { status: 404 }),
      ),
    );
    renderJoinPage();

    await user.type(screen.getByLabelText("表示名"), "ユイ");
    await user.click(
      screen.getByRole("button", { name: "この名前で参加する" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "無効、期限切れ、または使用済み",
    );
  });

  it("空の名前を入力欄へ関連付ける", async () => {
    const user = userEvent.setup();
    renderJoinPage();
    await user.click(
      screen.getByRole("button", { name: "この名前で参加する" }),
    );
    expect(await screen.findByText("表示名を入力してください")).toBeVisible();
    expect(screen.getByLabelText("表示名")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
});
