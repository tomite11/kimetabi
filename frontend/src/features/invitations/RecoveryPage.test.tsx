import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";

import { renderWithProviders } from "../../test/renderWithProviders";
import { RecoveryPage } from "./RecoveryPage";

afterEach(cleanup);

describe("RecoveryPage", () => {
  it("正式な復旧routeから以前のメンバー情報を引き継ぐ", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MemoryRouter initialEntries={["/recover/recovery-token-long-enough"]}>
        <Routes>
          <Route path="/recover/:recoveryToken" element={<RecoveryPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(
      screen.getByRole("heading", { name: "旅の参加情報を、この端末へ。" }),
    ).toHaveFocus();
    await user.click(
      screen.getByRole("button", { name: "参加情報を復旧する" }),
    );
    expect(
      await screen.findByRole("heading", { name: "参加情報を復旧しました" }),
    ).toBeVisible();
    expect(screen.getByText(/ユイさんの参加情報/)).toBeVisible();
  });
});
