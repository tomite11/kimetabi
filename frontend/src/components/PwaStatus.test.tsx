import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";

const { registerSW, updateSW } = vi.hoisted(() => ({
  registerSW: vi.fn(),
  updateSW: vi.fn(async () => undefined),
}));

vi.mock("virtual:pwa-register", () => ({ registerSW }));

import { initializePwa } from "../pwa/pwaLifecycle";
import { PwaStatus } from "./PwaStatus";

beforeEach(() => {
  registerSW.mockReturnValue(updateSW);
});

it("待機中のService Workerを案内し、利用者の操作で適用する", async () => {
  initializePwa();
  const options = registerSW.mock.calls[0]?.[0];
  options?.onNeedRefresh?.();

  render(<PwaStatus />);
  expect(screen.getByText("新しいバージョンがあります")).toBeVisible();
  expect(screen.getByText(/未送信の操作は保ったまま/)).toBeVisible();

  fireEvent.click(screen.getByRole("button", { name: "アプリを更新" }));
  expect(updateSW).toHaveBeenCalledWith(true);
});
