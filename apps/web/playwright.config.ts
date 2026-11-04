import { defineConfig, devices } from "@playwright/test";

/**
 * E2E (P12). API는 `e2e` profile로 별도 DB(stayops_e2e)를 비우고 데모 데이터를 다시 넣은 뒤 8081에서,
 * 웹은 production build로 3200에서 띄운다. 시나리오가 같은 데모 데이터를 순서대로 바꾸므로 worker 1개로 직렬 실행한다.
 */
const API_PORT = 8081;
const WEB_PORT = 3200;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "./gradlew bootRun --console=plain --args='--spring.profiles.active=e2e'",
      cwd: "../api",
      url: `http://localhost:${API_PORT}/actuator/health`,
      env: { STAYOPS_API_PORT: String(API_PORT), STAYOPS_WEB_ALLOWED_ORIGINS: `http://localhost:${WEB_PORT}` },
      reuseExistingServer: false, // 매 실행마다 DB를 초기화하기 위해 항상 새로 띄운다
      timeout: 180_000,
    },
    {
      // 배포와 같은 production build로 검증한다. NEXT_PUBLIC_* 값은 build 시점에 들어간다.
      command: `pnpm exec next build && pnpm exec next start --port ${WEB_PORT}`,
      url: `http://localhost:${WEB_PORT}`,
      env: { NEXT_PUBLIC_STAYOPS_API_BASE_URL: `http://localhost:${API_PORT}` },
      reuseExistingServer: false,
      timeout: 240_000,
    },
  ],
});
