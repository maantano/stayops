import path from "node:path";
import { expect, test } from "@playwright/test";
import { loginAs, STAFF } from "./support";

/** P11: 데모 데이터셋(언어 간 계약 fixture)을 올리고 세 정책을 비교 실행한다. */
test("upload a synthetic dataset and compare policies", async ({ page }) => {
  const dataset = path.resolve(__dirname, "../../api/src/test/resources/simulation/dataset-contract.json");
  await loginAs(page, STAFF.manager);
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "시뮬레이션" }).click();

  await page.getByLabel("데모 데이터셋 올리기 (gen-v1 JSON)").setInputFiles(dataset);
  await expect(page.getByLabel("데이터셋").locator("option")).not.toHaveCount(0);
  await page.getByLabel("대안 A: 준비 마감이 급한 객실부터").check();
  await page.getByLabel("대안 B: 하우스키핑 교대 시작을 모두 09:00으로").check();
  await page.getByRole("button", { name: "실행", exact: true }).click();

  const result = page.getByRole("region", { name: "비교 결과" });
  const table = result.getByRole("table");
  await expect(table).toBeVisible();
  await expect(table.getByRole("columnheader")).toHaveCount(4); // 지표 + 정책 3개
  await expect(table.getByRole("row", { name: /정시 준비율/ })).toContainText("%");
  await expect(result.getByText(/실제 운영 성과나 최적화 보장이 아닙니다/)).toBeVisible();
});
