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
import { database } from "../../offline/database";
import { CandidateComparisonPage } from "./CandidateComparisonPage";

afterEach(async () => {
  cleanup();
  await database.pendingOperations.clear();
  Object.defineProperty(navigator, "onLine", {
    configurable: true,
    value: true,
  });
});

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
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "URLから候補を追加" }));
    expect(screen.getByRole("textbox", { name: "URL" })).toHaveFocus();
  });

  it("オフライン操作を保存し同じIdempotency-Keyでonline復帰時に再送する", async () => {
    const keys: string[] = [];
    let attempts = 0;
    server.use(
      http.post("*/api/trips/42/slots/101/candidates", async ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key") ?? "");
        attempts += 1;
        if (attempts === 1) {
          await new Promise((resolve) => setTimeout(resolve, 30));
          return HttpResponse.error();
        }
        const body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            ...transportSlotDetail.candidates[0],
            ...body,
            id: 509,
            metadataStatus: "PENDING",
          },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderPage();

    await user.type(
      await screen.findByRole("textbox", { name: "URL" }),
      "https://example.com/new-hotel",
    );
    await user.type(screen.getByRole("textbox", { name: "金額" }), "18000");
    await user.click(screen.getByRole("button", { name: "この枠に追加" }));

    expect(await screen.findByText("候補を送信しています…")).toBeVisible();
    window.dispatchEvent(new Event("online"));
    expect(await screen.findByText("情報を取得待ち")).toBeVisible();
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe("");
    expect(keys[1]).toBe(keys[0]);
    expect(await database.pendingOperations.count()).toBe(0);
  });

  it("metadata失敗状態を区別しretryableだけ再取得できる", async () => {
    server.use(
      http.get("*/api/trips/42/slots/101", () =>
        HttpResponse.json({
          ...transportSlotDetail,
          candidates: [
            {
              ...transportSlotDetail.candidates[0],
              metadataStatus: "FAILED_RETRYABLE",
              metadataErrorCode: "FETCH_TIMEOUT",
            },
            {
              ...transportSlotDetail.candidates[1],
              metadataStatus: "FAILED_PERMANENT",
              metadataErrorCode: "SSRF_REJECTED",
            },
          ],
        }),
      ),
      http.post("*/api/trips/42/candidates/501/metadata/retry", () =>
        HttpResponse.json({
          ...transportSlotDetail.candidates[0],
          metadataStatus: "PENDING",
          version: 1,
        }),
      ),
      http.patch("*/api/trips/42/candidates/502", async ({ request }) => {
        const body = (await request.json()) as {
          title: string;
          estAmount: number;
        };
        return HttpResponse.json({
          ...transportSlotDetail.candidates[1],
          ...body,
          metadataStatus: "FAILED_PERMANENT",
          version: 1,
        });
      }),
    );
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("取得に失敗・再試行できます")).toBeVisible();
    expect(screen.getByText("自動取得できませんでした")).toBeVisible();
    await user.click(
      screen.getByRole("button", { name: "名前と金額を手入力" }),
    );
    const manualName = screen.getByRole("textbox", { name: "候補名" });
    expect(manualName).toHaveFocus();
    await user.clear(manualName);
    await user.type(manualName, "手入力の候補");
    await user.click(screen.getByRole("button", { name: "手入力を保存" }));
    expect(await screen.findByText("手入力の候補")).toBeVisible();
    await user.click(
      screen.getByRole("button", { name: "メタデータを再取得" }),
    );
    expect(await screen.findByText("情報を取得待ち")).toBeVisible();
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
