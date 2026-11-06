import { defineConfig, devices } from "@playwright/test";

/**
 * 운영 테스트 (docs/qa/TEST_CASES.md). E2E(playwright.config.ts)와 달리 서버를 띄우지 않고,
 * **이미 실행 중인 서비스**(기본: 웹 3100 · API 8080, demo profile)에 대해 실제 사용자처럼 실행한다.
 * 데모 데이터를 바꾸므로 깨끗한 데이터에서 시작한다: API를 `--stayops.demo.force-refresh-on-startup=true`로 다시 띄운 뒤 실행.
 *   STAYOPS_OPS_WEB=http://localhost:3100 STAYOPS_OPS_API=http://localhost:8080 pnpm test:ops
 * 케이스마다 화면 캡처를 ops-results/evidence/에 남긴다.
 */
export const OPS_WEB = process.env.STAYOPS_OPS_WEB ?? "http://localhost:3100";
export const OPS_API = process.env.STAYOPS_OPS_API ?? "http://localhost:8080";

export default defineConfig({
  testDir: "./ops",
  outputDir: "ops-results/artifacts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [["list"], ["json", { outputFile: "ops-results/results.json" }]],
  use: {
    baseURL: OPS_WEB,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
    viewport: { width: 1280, height: 900 },
  },
  projects: [{ name: "ops", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 900 } } }],
});
