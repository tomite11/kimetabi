import { expect, test } from "@playwright/test";

test("モバイルで撮影fallbackと金額入力を操作できる", async ({
  page,
}, testInfo) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/t/42/expenses/new");

  await expect(
    page.getByRole("heading", { name: "支出を記録する" }),
  ).toBeVisible();
  const camera = page.getByLabel("カメラで撮影");
  const fallback = page.getByLabel("写真ライブラリから選ぶ");
  await expect(camera).toHaveAttribute("capture", "environment");
  await expect(fallback).not.toHaveAttribute("capture");
  const cameraAction = page.getByText("カメラで撮影", { exact: true });
  await cameraAction.focus();
  await expect(cameraAction).toBeFocused();

  const amountChoice = page.getByRole("button", { name: /金額を入れる/ });
  await amountChoice.click();
  const amount = page.getByRole("spinbutton", { name: "支出の総額" });
  await expect(amount).toBeFocused();
  await amount.fill("1280");
  await page.getByRole("button", { name: "未確定として記録" }).click();
  await expect(page.getByRole("status")).toContainText(
    "未確定の支出として記録",
  );
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    )
    .toBe(true);

  await amountChoice.focus();
  await expect(amountChoice).toBeFocused();
  await expect
    .poll(() =>
      amountChoice.evaluate(
        (element) => getComputedStyle(element).outlineWidth === "3px",
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("expense-capture-mobile.png"),
    fullPage: true,
  });
  expect(consoleErrors).toEqual([]);
});
