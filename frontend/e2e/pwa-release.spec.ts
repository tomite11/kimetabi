import { expect, test } from "@playwright/test";

test("manifestとService Workerでオフライン起動できる", async ({
  context,
  page,
  browserName,
}) => {
  test.skip(
    browserName !== "chromium",
    "Service Worker検証はChromiumで実行する",
  );
  await page.goto("/");
  await expect(page.getByText("タビキメワリ", { exact: true })).toBeVisible();
  const manifest = await page.request.get("/manifest.webmanifest");
  expect(manifest.ok()).toBeTruthy();
  expect(await manifest.json()).toMatchObject({
    name: "タビキメワリ",
    start_url: "/",
    display: "standalone",
  });
  await page.waitForFunction(async () => {
    if (!("serviceWorker" in navigator)) return false;
    await navigator.serviceWorker.ready;
    return Boolean(navigator.serviceWorker.controller);
  });
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText("タビキメワリ", { exact: true })).toBeVisible();
  // Chromium keeps navigator.onLine stale across a service-worker navigation;
  // dispatch the browser's offline signal after proving the shell reloaded.
  await page.evaluate(() => window.dispatchEvent(new Event("offline")));
  await expect(page.getByText("オフラインです", { exact: true })).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "本文へ移動" })).toBeFocused();
});
