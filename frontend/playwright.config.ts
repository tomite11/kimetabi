import { defineConfig, devices } from "@playwright/test";

const realApi = process.env.E2E_REAL_API === "true";
const realtime = process.env.E2E_REALTIME === "true";
const pwaRelease = process.env.E2E_PWA === "true";
const crossBrowser = process.env.E2E_CROSS_BROWSER === "true";
const previewAcceptance = process.env.E2E_PREVIEW === "true";
const previewUi = process.env.E2E_PREVIEW_UI === "true";
const databasePort = process.env.E2E_DATABASE_PORT || "55432";

export default defineConfig({
  testDir: "./e2e",
  testMatch:
    previewAcceptance || previewUi
      ? [
          previewUi
            ? "preview-phase6-ui.spec.ts"
            : "preview-phase1-acceptance.spec.ts",
        ]
      : realtime
        ? ["realtime-recovery.spec.ts"]
        : realApi
          ? ["real-api-trip.spec.ts", "real-api-settlement.spec.ts"]
          : pwaRelease
            ? ["pwa-release.spec.ts"]
            : crossBrowser
              ? [
                  "trip-list-empty-state.spec.ts",
                  "guest-trip-shell.spec.ts",
                  "expense-capture.spec.ts",
                  "settlement.spec.ts",
                ]
              : [
                  "trip-list-empty-state.spec.ts",
                  "guest-trip-shell.spec.ts",
                  "expense-capture.spec.ts",
                  "settlement.spec.ts",
                ],
  fullyParallel: !pwaRelease && !crossBrowser,
  workers: pwaRelease || crossBrowser ? 1 : undefined,
  reporter: "list",
  use: {
    baseURL:
      previewAcceptance || previewUi
        ? process.env.E2E_PREVIEW_URL
        : "http://127.0.0.1:5173",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "mobile-chromium",
      use: {
        ...devices["iPhone 13"],
        browserName: "chromium",
      },
    },
    ...(crossBrowser
      ? [
          { name: "desktop-chromium", use: { ...devices["Desktop Chrome"] } },
          { name: "desktop-firefox", use: { ...devices["Desktop Firefox"] } },
        ]
      : []),
  ],
  webServer:
    previewAcceptance || previewUi
      ? undefined
      : realApi
        ? [
            {
              command:
                "XDG_CONFIG_HOME=/tmp/kimetabi-firebase-config CI=true firebase emulators:start --only auth --project demo-kimetabi-e2e --config ../firebase.json",
              url: "http://127.0.0.1:9099",
              reuseExistingServer: false,
              timeout: 60_000,
            },
            {
              command: `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099 FIREBASE_PROJECT_ID=demo-kimetabi-e2e DATABASE_URL=jdbc:postgresql://127.0.0.1:${databasePort}/kimetabi DATABASE_USERNAME=kimetabi DATABASE_PASSWORD=kimetabi ../backend/mvnw -f ../backend/pom.xml spring-boot:run`,
              url: "http://127.0.0.1:8080/actuator/health",
              reuseExistingServer: false,
              timeout: 120_000,
            },
            {
              command:
                "VITE_FIREBASE_API_KEY=demo-api-key VITE_FIREBASE_AUTH_DOMAIN=demo-kimetabi-e2e.firebaseapp.com VITE_FIREBASE_PROJECT_ID=demo-kimetabi-e2e VITE_FIREBASE_APP_ID=1:123:web:e2e VITE_FIREBASE_AUTH_EMULATOR_URL=http://127.0.0.1:9099 npm run dev -- --host 127.0.0.1",
              url: "http://127.0.0.1:5173",
              reuseExistingServer: false,
              timeout: 60_000,
            },
          ]
        : {
            command: pwaRelease
              ? "npm run preview -- --host 127.0.0.1 --port 5173"
              : realtime
                ? "VITE_ENABLE_MSW=true VITE_ENABLE_REALTIME=true VITE_WEBSOCKET_URL=ws://127.0.0.1:5174/ws npm run dev -- --host 127.0.0.1"
                : "VITE_ENABLE_MSW=true npm run dev -- --host 127.0.0.1",
            url: "http://127.0.0.1:5173",
            reuseExistingServer: !process.env.CI,
          },
});
