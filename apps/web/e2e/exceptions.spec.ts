import { expect, test } from "@playwright/test";
import { assignTask, expectToast, loginAs, openTasks, STAFF, taskCard } from "./support";

test.describe.configure({ mode: "serial" });

/** 02 §4.2: 시작된 투숙 중 청소가 있으면 체크아웃을 거부하고, 원인과 해결 방법을 보여 준다. */
test("checkout is refused while a stayover cleaning is in progress, then succeeds after completion", async ({ page }) => {
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await assignTask(page, "104", "투숙 중 청소", STAFF.hk1);

  await loginAs(page, STAFF.hk1);
  await openTasks(page);
  const stayover = taskCard(page, "104", "투숙 중 청소");
  await stayover.getByRole("button", { name: "시작" }).click();
  await expect(stayover).toContainText("진행 중");

  await loginAs(page, STAFF.frontDesk);
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "예약" }).click();
  const inHouse = page.getByRole("region", { name: "투숙 중" }).getByRole("listitem").filter({ hasText: "104호" });
  await inHouse.getByRole("button", { name: "체크아웃" }).click();
  const refusal = inHouse.getByRole("alert");
  await expect(refusal).toContainText("담당자가 차단을 해제하고 청소를 완료");
  await expect(refusal).toContainText(STAFF.hk1);
  await expect(refusal).toContainText("추적 ID");

  await loginAs(page, STAFF.hk1);
  await openTasks(page);
  await taskCard(page, "104", "투숙 중 청소").getByRole("button", { name: "완료" }).click();
  await expectToast(page, "작업을 완료했습니다");

  await loginAs(page, STAFF.frontDesk);
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "예약" }).click();
  await page.getByRole("region", { name: "투숙 중" }).getByRole("listitem").filter({ hasText: "104호" }).getByRole("button", { name: "체크아웃" }).click();
  await expectToast(page, "104호 체크아웃했습니다");
});

/** 02 §5: 판매 중단 객실은 체크인할 수 없다. 관리자가 판매를 재개하면 체크인된다. */
test("check-in to an out-of-service room is blocked until the manager restores it", async ({ page }) => {
  await loginAs(page, STAFF.frontDesk);
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "예약" }).click();
  const arrival = () => page.getByRole("region", { name: "도착 예정" }).getByRole("listitem").filter({ hasText: "102호" });
  await arrival().getByRole("button", { name: "체크인" }).click();
  await expect(arrival().getByRole("alert")).toBeVisible();

  await loginAs(page, STAFF.manager);
  await page.goto("/rooms");
  await page.getByRole("link", { name: "102호", exact: true }).first().click();
  await expect(page.getByText("판매 상태: 판매 중단")).toBeVisible();
  await page.getByRole("button", { name: "판매 재개" }).click();
  const dialog = page.getByRole("dialog", { name: "102호 판매 재개" });
  await dialog.getByLabel("사유").fill("점검 완료 (E2E)");
  await dialog.locator("button[type=submit]").click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("판매 상태: 판매 가능")).toBeVisible();

  await loginAs(page, STAFF.frontDesk);
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "예약" }).click();
  await arrival().getByRole("button", { name: "체크인" }).click();
  await expectToast(page, "102호 체크인했습니다");
});
