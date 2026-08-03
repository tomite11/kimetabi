import { expect, test } from "@playwright/test";

test("匿名Firebaseユーザーが実APIで旅行作成から候補採択と予定表示まで進める", async ({
  page,
}, testInfo) => {
  const apiResponses: Array<{
    method: string;
    pathname: string;
    status: number;
  }> = [];
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/")) {
      apiResponses.push({
        method: response.request().method(),
        pathname: url.pathname,
        status: response.status(),
      });
    }
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "旅行一覧" })).toBeVisible();

  const destination = "東京";
  await page.getByRole("button", { name: "旅行を作る" }).click();
  await page.getByLabel("目的地").fill(destination);
  await page.getByLabel("出発日").fill("2026-09-20");
  await page.getByLabel("帰着日").fill("2026-09-22");
  await page.getByLabel("人数").fill("3");
  await page.getByRole("button", { name: "この内容で旅行を作る" }).click();

  await expect(page).toHaveURL(/\/t\/\d+$/);
  await expect(
    page.getByRole("heading", { name: `${destination}の旅` }),
  ).toBeVisible();
  const tripPath = new URL(page.url()).pathname.replace(
    /^\/t\//,
    "/api/trips/",
  );

  await page.getByRole("link", { name: "旅程" }).click();
  const firstSlot = page.locator("ol").getByRole("link").first();
  await firstSlot.click();
  await expect(page.getByText("候補はまだありません")).toBeVisible();

  const urlCandidateTitle = "公式サイト候補";
  await page.getByLabel("名前").fill(urlCandidateTitle);
  await page.getByLabel("URL").fill("https://example.com/hotel");
  await page.getByRole("button", { name: "この枠に追加" }).click();
  const urlCandidate = page
    .getByRole("article")
    .filter({ hasText: urlCandidateTitle });
  await expect(urlCandidate.getByText("情報を取得待ち")).toBeVisible();

  const candidateTitle = "朝の新幹線";
  await page.getByLabel("名前").fill(candidateTitle);
  await page.getByLabel("URL").fill("");
  await page.getByRole("textbox", { name: "金額", exact: true }).fill("12000");
  await page.getByRole("button", { name: "この枠に追加" }).click();
  const manualCandidate = page
    .getByRole("article")
    .filter({ hasText: candidateTitle });
  await expect(
    manualCandidate.getByText(candidateTitle, { exact: true }),
  ).toBeVisible();
  await manualCandidate.getByRole("button", { name: "これがいい" }).click();
  await expect(manualCandidate.getByText(/いい 1/)).toBeVisible();
  await page.getByRole("button", { name: "この候補を決定" }).click();
  await expect(
    page.getByRole("button", { name: "この候補に変更" }),
  ).toBeVisible();

  await page.getByRole("link", { name: "旅程" }).click();
  await expect(page.getByText(`確定予定: ${candidateTitle}`)).toBeVisible();

  const adoptedSnapshot = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === tripPath &&
      response.request().method() === "GET",
  );
  await page.reload();
  expect((await adoptedSnapshot).status()).toBe(200);
  await expect(page.getByText(`確定予定: ${candidateTitle}`)).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("adopted-plan-mobile.png"),
    fullPage: true,
  });

  expect(apiResponses).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        method: "GET",
        pathname: "/api/trips",
        status: 200,
      }),
      expect.objectContaining({
        method: "POST",
        pathname: "/api/trips",
        status: 201,
      }),
      expect.objectContaining({
        method: "GET",
        pathname: tripPath,
        status: 200,
      }),
      expect.objectContaining({ method: "POST", status: 201 }),
      expect.objectContaining({ method: "PUT", status: 200 }),
    ]),
  );
});
