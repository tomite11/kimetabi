import { expect, test } from "@playwright/test";

test("モバイルとキーボードで精算案を確認して確定できる", async ({
  page,
}, testInfo) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/t/42/settle");

  await expect(
    page.getByRole("heading", { name: /2回の送金で/ }),
  ).toBeVisible();
  await expect(page.getByText("￥238,640")).toBeVisible();
  await expect(page.getByRole("link", { name: "支出" })).toHaveAttribute(
    "aria-current",
    "page",
  );

  const confirm = page.getByRole("button", { name: "精算を確定" });
  await confirm.focus();
  await expect(confirm).toBeFocused();
  await expect
    .poll(() =>
      confirm.evaluate(
        (element) => getComputedStyle(element).outlineWidth === "3px",
      ),
    )
    .toBe(true);
  await page.keyboard.press("Enter");
  await expect(page.getByText("支払い待ち").first()).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("settlement-mobile.png"),
    fullPage: true,
  });
  expect(consoleErrors).toEqual([]);
});
