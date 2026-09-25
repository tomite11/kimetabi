import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import path from "node:path";

const previewUrl = process.env.E2E_PREVIEW_URL;
const evidenceDirectory = process.env.E2E_EVIDENCE_DIR;

test("PreviewでPWA・オフライン・実機相当UI・2ブラウザ同期を検証する", async ({
  browser,
}, testInfo) => {
  test.setTimeout(420_000);
  test.skip(!previewUrl, "Firebase Hosting Preview URLが必要です");

  const ownerContext = await browser.newContext({
    viewport: { width: 360, height: 800 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  });
  const memberContext = await browser.newContext({
    viewport: { width: 1280, height: 800 },
  });
  const owner = await ownerContext.newPage();
  const member = await memberContext.newPage();

  try {
    await verifyInstallablePwa(owner, ownerContext, testInfo);
    const tripId = await createTripWithKeyboard(owner);
    await verifyResponsiveAccessibility(owner, tripId, testInfo);
    await verifyCameraAndFileFallback(owner, ownerContext, tripId, testInfo);
    await verifyTwoBrowserSynchronization(owner, member, tripId, testInfo);
  } finally {
    await ownerContext.close();
    await memberContext.close();
  }
});

async function verifyInstallablePwa(
  page: Page,
  context: BrowserContext,
  testInfo: { outputPath: (...pathSegments: string[]) => string },
) {
  await page.goto(previewUrl!);
  await expect(page.getByText("タビキメワリ", { exact: true })).toBeVisible();
  const documentResponse = await page.request.get(previewUrl!);
  expect(documentResponse.headers()["content-security-policy"]).toContain(
    "https://storage.googleapis.com",
  );

  const manifestResponse = await page.request.get(
    `${previewUrl}/manifest.webmanifest`,
  );
  expect(manifestResponse.ok()).toBeTruthy();
  const manifest = (await manifestResponse.json()) as {
    name: string;
    display: string;
    start_url: string;
    icons: Array<{ src: string; sizes: string; purpose?: string }>;
  };
  expect(manifest).toMatchObject({
    name: "タビキメワリ",
    display: "standalone",
    start_url: "/",
  });
  expect(manifest.icons).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ sizes: "192x192" }),
      expect.objectContaining({ sizes: "512x512" }),
      expect.objectContaining({ sizes: "512x512", purpose: "maskable" }),
    ]),
  );
  for (const icon of manifest.icons) {
    const iconResponse = await page.request.get(
      new URL(icon.src, previewUrl).href,
    );
    expect(iconResponse.ok(), icon.src).toBeTruthy();
    expect(iconResponse.headers()["content-type"]).toContain("image/png");
  }

  await page.waitForFunction(async () => {
    if (!("serviceWorker" in navigator)) return false;
    await navigator.serviceWorker.ready;
    return Boolean(navigator.serviceWorker.controller);
  });
  const devtools = await context.newCDPSession(page);
  const installability = (await devtools.send(
    "Page.getInstallabilityErrors",
  )) as { installabilityErrors: unknown[] };
  expect(installability.installabilityErrors).toEqual([]);
  await devtools.detach();
  const serviceWorkerResponse = await page.request.get(`${previewUrl}/sw.js`);
  expect(serviceWorkerResponse.ok()).toBeTruthy();
  expect(serviceWorkerResponse.headers()["cache-control"]).toContain(
    "no-cache",
  );
  const cachedApiRequests = await page.evaluate(async () => {
    const requests = (
      await Promise.all(
        (await caches.keys()).map(async (cacheName) => {
          const cache = await caches.open(cacheName);
          return cache.keys();
        }),
      )
    ).flat();
    return requests
      .map((request) => request.url)
      .filter((url) => new URL(url).pathname.startsWith("/api/"));
  });
  expect(cachedApiRequests).toEqual([]);

  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText("タビキメワリ", { exact: true })).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event("offline")));
  await expect(page.getByText("オフラインです", { exact: true })).toBeVisible();
  await saveScreenshot(page, testInfo, "01-offline-start-360.png");
  await context.setOffline(false);
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
}

async function createTripWithKeyboard(page: Page) {
  await page.goto(previewUrl!);
  await expect(page.getByRole("heading", { name: "旅行一覧" })).toBeVisible();
  await page.getByRole("button", { name: "旅行を作る" }).click();
  await page.getByLabel("目的地").fill("Phase 6 東京");
  await page.getByLabel("出発日").fill("2026-09-26");
  await page.getByLabel("帰着日").fill("2026-09-28");
  await page.getByLabel("人数").fill("2");
  await page.getByRole("button", { name: "この内容で旅行を作る" }).focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/t\/\d+$/);
  await expect(
    page.getByRole("heading", { name: "Phase 6 東京の旅" }),
  ).toBeVisible();
  return Number(new URL(page.url()).pathname.split("/").at(-1));
}

async function verifyResponsiveAccessibility(
  page: Page,
  tripId: number,
  testInfo: { outputPath: (...pathSegments: string[]) => string },
) {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto(`${previewUrl}/t/${tripId}`);
  await expect(
    page.getByRole("heading", { name: "Phase 6 東京の旅" }),
  ).toBeVisible();
  await expectNoHorizontalOverflow(page);

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
        .locator('[class*="routeTrack"] span')
        .evaluate(
          (element) =>
            Number.parseFloat(getComputedStyle(element).transitionDuration) *
              1000 <=
            0.01,
        ),
    )
    .toBe(true);
  await saveScreenshot(page, testInfo, "02-trip-home-360-reduced-motion.png");

  // 720 physical pixels at 200% browser zoom yields a 360 CSS-pixel layout.
  // Recreate that effective viewport directly so Chromium performs real reflow
  // instead of using non-standard CSS zoom.
  await page.goto(`${previewUrl}/t/${tripId}`);
  await expectNoHorizontalOverflow(page);
  const navigation = page.getByRole("navigation", {
    name: "旅行のメインナビゲーション",
  });
  await expect(navigation).toBeVisible();
  await expectElementWithinViewport(page, navigation);
  await expectElementWithinViewport(
    page,
    page.getByRole("button", { name: "候補を追加" }),
  );
  await saveScreenshot(page, testInfo, "03-trip-home-200-percent.png");
}

async function verifyCameraAndFileFallback(
  page: Page,
  context: BrowserContext,
  tripId: number,
  testInfo: { outputPath: (...pathSegments: string[]) => string },
) {
  await page.goto(`${previewUrl}/t/${tripId}/expenses/new`);
  const camera = page.getByLabel("カメラで撮影");
  const fallback = page.getByLabel("写真ライブラリから選ぶ");
  await expect(camera).toHaveAttribute("accept", "image/*");
  await expect(camera).toHaveAttribute("capture", "environment");
  await expect(fallback).toHaveAttribute(
    "accept",
    "image/jpeg,image/png,image/webp",
  );
  await expect(fallback).not.toHaveAttribute("capture");

  await context.setOffline(true);
  await fallback.setInputFiles(path.resolve("public/icons/app-icon-192.png"));
  await expect(page.getByRole("status")).toContainText("保存準備ができました");
  await page
    .getByRole("button", { name: "写真だけで保存", exact: true })
    .click();
  await expect(
    page.getByText(/端末に保存しました。オフラインでも大丈夫です。/),
  ).toBeVisible();
  await expect(page.getByText("オフラインです", { exact: true })).toBeVisible();
  await saveScreenshot(page, testInfo, "04-camera-fallback-offline.png");
  await context.setOffline(false);
  await page.evaluate(() => window.dispatchEvent(new Event("online")));

  await page.goto(`${previewUrl}/t/${tripId}/expenses`);
  await expect(
    page.getByRole("heading", { name: "未確定の支出" }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "未確定の支出" }).getByRole("button"),
  ).toHaveCount(1);
}

async function verifyTwoBrowserSynchronization(
  owner: Page,
  member: Page,
  tripId: number,
  testInfo: { outputPath: (...pathSegments: string[]) => string },
) {
  await owner.goto(`${previewUrl}/t/${tripId}`);
  await owner.getByRole("button", { name: "メンバー 1人" }).click();
  await owner.getByRole("button", { name: "招待リンクを作る" }).click();
  const invitationUrl = await owner.getByLabel("共有するリンク").inputValue();
  expect(invitationUrl).toMatch(/\/join\//);
  await owner.getByRole("button", { name: "メンバー管理を閉じる" }).click();

  await member.goto(invitationUrl);
  await member.getByLabel("表示名").fill("Phase6 Member");
  await member.getByRole("button", { name: "この名前で参加する" }).click();
  await expect(member).toHaveURL(new RegExp(`/t/${tripId}$`));
  await expect(
    member.getByRole("heading", { name: "Phase 6 東京の旅" }),
  ).toBeVisible();
  await expect(owner.getByRole("button", { name: "メンバー 2人" }), {
    message: "別ブラウザの参加をWebSocket経由で反映する",
  }).toBeVisible({ timeout: 330_000 });

  await saveScreenshot(owner, testInfo, "05-owner-synchronized.png");
  await saveScreenshot(member, testInfo, "06-member-synchronized.png");
}

async function expectNoHorizontalOverflow(page: Page) {
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    )
    .toBe(true);
}

async function expectElementWithinViewport(
  page: Page,
  locator: ReturnType<Page["getByRole"]>,
) {
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  const viewport = page.viewportSize();
  expect(viewport).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width);
}

async function saveScreenshot(
  page: Page,
  testInfo: { outputPath: (...pathSegments: string[]) => string },
  filename: string,
) {
  const outputPath = evidenceDirectory
    ? path.resolve(evidenceDirectory, filename)
    : testInfo.outputPath(filename);
  await page.screenshot({ path: outputPath, fullPage: true });
}
