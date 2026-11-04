import { expect, test } from "@playwright/test";
import { assignTask, expectToast, loginAs, openTasks, STAFF, taskCard } from "./support";

/**
 * 05 §8 핵심 흐름: 데모 데이터의 202호(체크아웃 직후, 청소 미배정)를
 * 배정 → 시작 → 차단 → 해제 → 완료 → 검사 실패 → 재청소 → 재검사 통과 → 준비 완료 → 체크인까지 화면으로 진행한다.
 * 역할마다 다른 직원으로 바꿔 가며, 각 직원에게 보이는 버튼만 쓴다.
 */
test("checkout cleaning → block/resolve → inspection fail → re-clean → pass → READY → check-in", async ({ page }) => {
  // 관리자: 체크아웃 청소 배정
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await assignTask(page, "202", "체크아웃 청소", STAFF.hk2);

  // 하우스키핑: 시작 → 차단 → 해제 → 완료
  await loginAs(page, STAFF.hk2);
  await openTasks(page);
  const cleaning = taskCard(page, "202", "체크아웃 청소");
  await cleaning.getByRole("button", { name: "시작" }).click();
  await expect(cleaning).toContainText("진행 중");

  await cleaning.getByRole("button", { name: "차단 등록" }).click();
  const blockDialog = page.getByRole("dialog", { name: "202호 작업 차단 등록" });
  await blockDialog.getByLabel("설명").fill("린넨 카트 대기 (E2E)");
  await blockDialog.locator("button[type=submit]").click();
  await expect(blockDialog).toBeHidden();
  await expect(cleaning).toContainText("차단됨");
  await expect(cleaning.getByRole("list", { name: "활성 차단 사유" })).toContainText("린넨 카트 대기 (E2E)");

  await cleaning.getByRole("button", { name: "해제" }).click();
  await expect(cleaning).toContainText("진행 중");
  await cleaning.getByRole("button", { name: "완료" }).click();
  await expectToast(page, "작업을 완료했습니다");
  await expect(cleaning).toHaveCount(0); // 완료된 작업은 기본 필터(진행 중인 상태)에서 빠진다

  // 관리자: 자동 생성된 검사 배정
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await assignTask(page, "202", "검사", STAFF.inspector);

  // 검사자: 시작 → 실패
  await loginAs(page, STAFF.inspector);
  await openTasks(page);
  const inspection = taskCard(page, "202", "검사");
  await inspection.getByRole("button", { name: "시작" }).click();
  await inspection.getByRole("button", { name: "검사 결과 입력" }).click();
  const resultDialog = page.getByRole("dialog", { name: "202호 검사 결과" });
  await resultDialog.getByLabel("욕실").uncheck();
  await resultDialog.getByLabel(/실패 → 재청소/).check();
  await resultDialog.getByLabel("실패 사유").fill("욕실 물기 (E2E)");
  await resultDialog.locator("button[type=submit]").click();
  await expect(resultDialog).toBeHidden();

  // 관리자: 재청소 배정 → 하우스키핑 완료 → 재검사 배정
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await expect(taskCard(page, "202", "재청소")).toContainText("검사 실패");
  await assignTask(page, "202", "재청소", STAFF.hk2);

  await loginAs(page, STAFF.hk2);
  await openTasks(page);
  const reClean = taskCard(page, "202", "재청소");
  await reClean.getByRole("button", { name: "시작" }).click();
  await expect(reClean).toContainText("진행 중");
  await reClean.getByRole("button", { name: "완료" }).click();
  await expect(reClean).toHaveCount(0);

  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await assignTask(page, "202", "검사", STAFF.inspector);

  // 검사자: 통과
  await loginAs(page, STAFF.inspector);
  await openTasks(page);
  const reInspection = taskCard(page, "202", "검사");
  await reInspection.getByRole("button", { name: "시작" }).click();
  await reInspection.getByRole("button", { name: "검사 결과 입력" }).click();
  const passDialog = page.getByRole("dialog", { name: "202호 검사 결과" });
  await expect(passDialog.getByLabel(/통과 → 객실 준비 완료/)).toBeChecked();
  await passDialog.locator("button[type=submit]").click();
  await expect(passDialog).toBeHidden();

  // 프런트: 객실이 준비 완료이고 타임라인에 흐름이 남았는지 확인한 뒤 체크인
  await loginAs(page, STAFF.frontDesk);
  await page.goto("/rooms");
  await page.getByRole("link", { name: "202호", exact: true }).first().click();
  await expect(page.getByText("운영 상태: 준비 완료")).toBeVisible();
  const timeline = page.getByRole("region", { name: "이벤트 타임라인" });
  await expect(timeline).toBeVisible();

  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "예약" }).click();
  const arrival = page.getByRole("region", { name: "도착 예정" }).getByRole("listitem").filter({ hasText: "202호" });
  await arrival.getByRole("button", { name: "체크인" }).click();
  await expectToast(page, "202호 체크인했습니다");
});
