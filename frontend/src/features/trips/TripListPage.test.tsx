import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { renderWithProviders } from "../../test/renderWithProviders";
import { server } from "../../test/mocks/server";
import { apiBaseUrl, apiClient } from "../../api/client";
import { TripListPage } from "./TripListPage";

describe("TripListPage", () => {
  it("OpenAPI契約に沿った空状態を表示する", async () => {
    expect(apiBaseUrl).toBe("http://localhost:5173");
    const response = await fetch("http://localhost:5173/api/trips");
    expect(await response.json()).toEqual({ items: [], nextCursor: null });
    const apiResponse = await apiClient.GET("/api/trips");
    expect(apiResponse.data).toEqual({ items: [], nextCursor: null });

    renderWithProviders(<TripListPage />);

    expect(screen.getByRole("status")).toHaveTextContent("読み込んでいます");
    expect(
      await screen.findByRole("heading", {
        name: "次の旅を、ここから一本につなごう。",
      }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "旅行を作る" })).toBeDisabled();
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

    renderWithProviders(<TripListPage />);

    expect(
      await screen.findByRole("heading", {
        name: "旅行を読み込めませんでした",
      }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "再読み込み" })).toBeVisible();
  });
});
