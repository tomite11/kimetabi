import { expect, test } from "@playwright/test";

test("匿名Firebaseユーザーが実APIで旅行を作成しsnapshotを再表示できる", async ({
  page,
}) => {
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

  const destination = `東京-${Date.now()}`;
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

  const reloadedSnapshot = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === tripPath &&
      response.request().method() === "GET",
  );
  await page.reload();
  expect((await reloadedSnapshot).status()).toBe(200);
  await expect(
    page.getByRole("heading", { name: `${destination}の旅` }),
  ).toBeVisible();

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
    ]),
  );
});
