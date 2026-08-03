import { expect, test } from "@playwright/test";

test("旅行一覧の空状態から旅行を作成できる", async ({ page }, testInfo) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "旅行一覧" })).toBeVisible();
  await expect(page.getByRole("button", { name: "旅行を作る" })).toBeEnabled();
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

  await page.getByRole("button", { name: "旅行を作る" }).click();
  await page.getByLabel("目的地").fill("東京");
  await page.getByLabel("出発日").fill("2026-09-20");
  await page.getByLabel("帰着日").fill("2026-09-22");
  await page.getByLabel("人数").fill("3");
  await page.screenshot({
    path: testInfo.outputPath("trip-create-mobile.png"),
    fullPage: true,
  });

  await page.getByLabel("目的地").focus();
  await expect(page.getByLabel("目的地")).toBeFocused();
  await expect
    .poll(() =>
      page
        .getByLabel("目的地")
        .evaluate((element) => getComputedStyle(element).outlineWidth),
    )
    .toBe("3px");

  await page.getByRole("button", { name: "この内容で旅行を作る" }).click();
  await expect(page).toHaveURL(/\/t\/42$/);
  await expect(page.getByRole("heading", { name: "東京の旅" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "メンバー 3人" }),
  ).toBeVisible();

  expect(consoleErrors).toEqual([]);
});
