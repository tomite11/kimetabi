import { expect, test } from "@playwright/test";

test("匿名参加後もモバイルで3タブ、フォーカス、空状態を維持する", async ({
  page,
}, testInfo) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/join/invitation-token-long-enough");

  const heading = page.getByRole("heading", { name: "名前だけで、旅に合流。" });
  await expect(heading).toBeFocused();
  await page.getByLabel("表示名").fill("ユイ");
  await page.getByRole("button", { name: "この名前で参加する" }).click();

  await expect(page).toHaveURL(/\/t\/42$/);
  await expect(
    page.getByText("登録するまでこの端末でのみ有効です"),
  ).toBeVisible();
  const memberButton = page.getByRole("button", { name: "メンバー 3人" });
  await memberButton.click();
  await expect(page.getByRole("dialog", { name: "メンバー" })).toBeVisible();
  await page.getByRole("button", { name: "メンバー管理を閉じる" }).click();
  await expect(memberButton).toBeFocused();
  const navigation = page.getByRole("navigation", {
    name: "旅行のメインナビゲーション",
  });
  await expect(navigation.getByRole("link")).toHaveCount(3);
  await expect(page.getByRole("button", { name: "候補を追加" })).toBeVisible();

  const planTab = navigation.getByRole("link", { name: "旅程" });
  await planTab.focus();
  await expect(planTab).toBeFocused();
  await expect
    .poll(() =>
      planTab.evaluate(
        (element) => getComputedStyle(element).outlineWidth === "3px",
      ),
    )
    .toBe(true);
  await planTab.press("Enter");
  await expect(page).toHaveURL(/\/t\/42\/plan$/);
  await expect(planTab).toHaveAttribute("aria-current", "page");

  await navigation.getByRole("link", { name: "支出" }).click();
  await expect(page.getByText("支出はまだありません")).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    )
    .toBe(true);
  await expect
    .poll(() =>
      page
        .locator('[class*="routeTrack"] > span')
        .evaluate(
          (element) =>
            Number.parseFloat(getComputedStyle(element).transitionDuration) *
              1000 <=
            0.01,
        ),
    )
    .toBe(true);

  await page.screenshot({
    path: testInfo.outputPath("guest-shell-mobile.png"),
  });
  expect(consoleErrors).toEqual([]);
});
