import { expect, test } from "@playwright/test";

test("旅行一覧の空状態をキーボードとモバイルで利用できる", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");

  await expect(
    page.getByRole("heading", {
      name: "次の旅を、ここから一本につなごう。",
    }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "旅行を作る" })).toBeDisabled();
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    )
    .toBe(true);

  await page.keyboard.press("Tab");
  const skipLink = page.getByRole("link", { name: "本文へ移動" });
  await expect(skipLink).toBeFocused();
  await expect
    .poll(() =>
      skipLink.evaluate(
        (element) => getComputedStyle(element).outlineWidth === "3px",
      ),
    )
    .toBe(true);
  await expect
    .poll(() =>
      page
        .locator('[class*="journeyDot"]')
        .evaluate(
          (element) =>
            Number.parseFloat(getComputedStyle(element).animationDuration) *
              1000 <=
            0.01,
        ),
    )
    .toBe(true);

  expect(consoleErrors).toEqual([]);
});
