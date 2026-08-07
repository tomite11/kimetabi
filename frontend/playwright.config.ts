import { defineConfig, devices } from "@playwright/test";

const realApi = process.env.E2E_REAL_API === "true";
const databasePort = process.env.E2E_DATABASE_PORT || "55432";

export default defineConfig({
  testDir: "./e2e",
  testMatch: realApi
    ? "real-api-trip.spec.ts"
    : [
        "trip-list-empty-state.spec.ts",
        "guest-trip-shell.spec.ts",
        "expense-capture.spec.ts",
      ],
  fullyParallel: true,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:5173",
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
  ],
  webServer: realApi
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
        command: "VITE_ENABLE_MSW=true npm run dev -- --host 127.0.0.1",
        url: "http://127.0.0.1:5173",
        reuseExistingServer: !process.env.CI,
      },
});
