import { expect, test, type Page } from "@playwright/test";

async function createTrip(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "旅行を作る" }).click();
  await page.getByLabel("目的地").fill("M6 結合旅行");
  await page.getByLabel("出発日").fill("2026-09-20");
  await page.getByLabel("帰着日").fill("2026-09-22");
  await page.getByLabel("人数").fill("2");
  await page.getByRole("button", { name: "この内容で旅行を作る" }).click();
  await expect(page).toHaveURL(/\/t\/\d+$/);
  return Number(new URL(page.url()).pathname.split("/").at(-1));
}

async function addAndConfirmExpense(
  page: Page,
  tripId: number,
  amount: number,
) {
  await page.goto(`/t/${tripId}/expenses/new`);
  await page.getByRole("button", { name: "金額を入れる" }).click();
  await page.getByLabel("支出の総額").fill(String(amount));
  await page.getByRole("button", { name: "未確定として記録" }).click();
  await expect(
    page.getByText("金額を未確定の支出として記録しました。", {
      exact: false,
    }),
  ).toBeVisible();

  await page.goto(`/t/${tripId}/expenses`);
  await page
    .getByRole("button", {
      name: new RegExp(`${amount.toLocaleString("ja-JP")}.*支払者と按分を確認`),
    })
    .click();
  await expect(
    page.getByRole("heading", { name: "この支出を確定" }),
  ).toBeFocused();
  await expect(page.getByRole("checkbox")).toHaveCount(2);
  await expect(
    page.getByRole("checkbox").filter({ checked: true }),
  ).toHaveCount(2);
  await page.getByRole("button", { name: "確定して次へ" }).click();
  await expect(page.getByRole("heading", { name: "確定済み" })).toBeVisible();
  await expect(
    page.getByText(`￥${amount.toLocaleString("ja-JP")}`),
  ).toBeVisible();
}

test("実APIで確定支出から精算完了、後発支出、再精算まで一周する", async ({
  browser,
  page,
}) => {
  const tripId = await createTrip(page);

  await page.getByRole("button", { name: /メンバー 1人/ }).click();
  await page.getByRole("button", { name: "招待リンクを作る" }).click();
  const invitationUrl = await page.getByLabel("共有するリンク").inputValue();

  const guestContext = await browser.newContext();
  const guestPage = await guestContext.newPage();
  const invitation = new URL(invitationUrl, page.url());
  await guestPage.goto(invitation.href);
  await guestPage.getByLabel("表示名").fill("同行者");
  await guestPage.getByRole("button", { name: "この名前で参加する" }).click();
  await expect(guestPage).toHaveURL(`/t/${tripId}`);
  await guestContext.close();

  await page.reload();
  await expect(
    page.getByRole("button", { name: /メンバー 2人/ }),
  ).toBeVisible();

  await addAndConfirmExpense(page, tripId, 12_000);
  await page.goto(`/t/${tripId}/settle`);
  await page.getByRole("button", { name: "精算案を作る" }).click();
  await expect(page.getByText("￥12,000")).toBeVisible();
  await expect(page.getByText("￥6,000")).toBeVisible();
  await page.getByRole("button", { name: "精算を確定" }).click();
  await page.getByRole("button", { name: /支払い済みにする/ }).click();
  await page.getByRole("button", { name: /受取を確認する/ }).click();
  await expect(page.getByText("精算が完了しました")).toBeVisible();

  await addAndConfirmExpense(page, tripId, 4_000);
  await page.goto(`/t/${tripId}/settle`);
  await expect(page.getByText("未反映の変更があります")).toBeVisible();
  await expect(page.getByText("￥12,000")).toBeVisible();
  await page.getByRole("button", { name: "再計算する" }).click();

  await expect(page.getByText("￥16,000")).toBeVisible();
  await expect(page.getByText("￥2,000")).toBeVisible();
  await expect(page.getByRole("heading", { name: "以前の精算" })).toBeVisible();
  await expect(page.getByText("1件 · ￥12,000")).toBeVisible();
  await expect(page.getByText("完了", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "精算を確定" })).toBeEnabled();
});
