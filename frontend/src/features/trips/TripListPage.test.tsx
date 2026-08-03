import { cleanup, fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it } from "vitest";

import { renderWithProviders } from "../../test/renderWithProviders";
import { server } from "../../test/mocks/server";
import { apiBaseUrl, apiClient } from "../../api/client";
import { TripHomePage } from "./TripHomePage";
import { TripListPage } from "./TripListPage";
import { TripShell } from "./TripShell";

afterEach(cleanup);

function renderTripRoutes() {
  return renderWithProviders(
    <MemoryRouter>
      <Routes>
        <Route path="/" element={<TripListPage />} />
        <Route path="/t/:tripId" element={<TripShell />}>
          <Route index element={<TripHomePage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("TripListPage", () => {
  it("OpenAPI契約に沿った空状態を表示する", async () => {
    expect(apiBaseUrl).toBe("http://localhost:5173");
    const response = await fetch("http://localhost:5173/api/trips");
    expect(await response.json()).toEqual({ items: [], nextCursor: null });
    const apiResponse = await apiClient.GET("/api/trips");
    expect(apiResponse.data).toEqual({ items: [], nextCursor: null });

    renderTripRoutes();

    expect(screen.getByRole("status")).toHaveTextContent("読み込んでいます");
    expect(
      await screen.findByRole("heading", { name: "旅行一覧" }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "旅行を作る" })).toBeEnabled();
    expect(
      screen.queryByText("まずは新しい旅行を作ります。"),
    ).not.toBeInTheDocument();
  });

  it("日程・目的地・人数から旅行を作成して開始画面へ進む", async () => {
    const user = userEvent.setup();
    let requestBody: unknown;
    let idempotencyKey: string | null = null;
    server.use(
      http.post("*/api/trips", async ({ request }) => {
        requestBody = await request.json();
        idempotencyKey = request.headers.get("Idempotency-Key");
        return HttpResponse.json(
          {
            trip: {
              id: 42,
              title: "札幌の旅",
              destination: "札幌",
              startsOn: "2026-10-10",
              endsOn: "2026-10-12",
              timezone: "Asia/Tokyo",
              expectedMemberCount: 4,
              phase: "PLANNING",
              phaseOverride: null,
              voteVisibility: "ANONYMOUS",
              budgetCap: null,
              revision: 1,
              version: 1,
            },
            members: [
              { id: 7, name: "わたし", role: "OWNER", status: "ACTIVE" },
            ],
            slots: [],
          },
          { status: 201 },
        );
      }),
    );

    renderTripRoutes();
    await user.click(await screen.findByRole("button", { name: "旅行を作る" }));
    await user.type(screen.getByLabelText("目的地"), "札幌");
    fireEvent.change(screen.getByLabelText("出発日"), {
      target: { value: "2026-10-10" },
    });
    fireEvent.change(screen.getByLabelText("帰着日"), {
      target: { value: "2026-10-12" },
    });
    await user.clear(screen.getByLabelText("人数"));
    await user.type(screen.getByLabelText("人数"), "4");
    await user.click(
      screen.getByRole("button", { name: "この内容で旅行を作る" }),
    );

    expect(
      await screen.findByRole("heading", { name: "札幌の旅" }),
    ).toBeVisible();
    expect(requestBody).toMatchObject({
      title: "札幌の旅",
      destination: "札幌",
      startsOn: "2026-10-10",
      endsOn: "2026-10-12",
      expectedMemberCount: 4,
      ownerName: "わたし",
      voteVisibility: "ANONYMOUS",
    });
    expect(idempotencyKey).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });

  it("入力エラーを項目に関連付けて表示する", async () => {
    const user = userEvent.setup();
    renderTripRoutes();

    await user.click(await screen.findByRole("button", { name: "旅行を作る" }));
    await user.click(
      screen.getByRole("button", { name: "この内容で旅行を作る" }),
    );

    expect(await screen.findByText("行き先を入力してください")).toBeVisible();
    expect(screen.getByLabelText("目的地")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });

  it("APIエラーから再試行できる", async () => {
    server.use(
      http.get("*/api/trips", () =>
        HttpResponse.json(
          { title: "Unavailable", status: 503 },
          { status: 503 },
        ),
      ),
    );

    renderTripRoutes();

    expect(
      await screen.findByRole("heading", {
        name: "旅行を読み込めませんでした",
      }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "再読み込み" })).toBeVisible();
  });
});
