import { expect, test } from "@playwright/test";
import { expectNoSeriousA11yViolations, loginAs, openTasks, STAFF, taskCard } from "./support";

/** 05 §8 / 03 §8: 주요 화면 axe serious·critical 0, 키보드만으로 dialog 열기·닫기와 포커스 복귀. */
test("main screens have no serious or critical axe violations", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expect(page.getByRole("button", { name: /^MGR-01/ })).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  await loginAs(page, STAFF.manager);
  for (const [link, heading] of [
    ["대시보드", "대시보드"],
    ["객실", "객실"],
    ["작업", "작업"],
    ["예약", "예약"],
    ["시뮬레이션", "시뮬레이션"],
  ] as const) {
    await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: link }).click();
    await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
    await page.waitForLoadState("networkidle");
    await expectNoSeriousA11yViolations(page);
  }

  await page.goto("/rooms");
  await page.getByRole("link", { name: "204호", exact: true }).first().click();
  await expect(page.getByRole("heading", { name: "이벤트 타임라인" })).toBeVisible();
  await page.waitForLoadState("networkidle");
  await expectNoSeriousA11yViolations(page);
});

test("dialogs work with the keyboard and return focus to the trigger", async ({ page }) => {
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  const trigger = taskCard(page, "204", "체크아웃 청소").getByRole("button", { name: "차단 등록" });

  await trigger.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "204호 작업 차단 등록" });
  await expect(dialog).toBeVisible();
  await expect(dialog.locator(":focus")).toHaveCount(1); // 포커스가 dialog 안으로 들어간다
  await expectNoSeriousA11yViolations(page);

  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(trigger).toBeFocused();

  // 닫기(×) 버튼으로 닫아도 포커스가 돌아온다
  await trigger.focus();
  await page.keyboard.press("Enter");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "닫기" }).click();
  await expect(dialog).toBeHidden();
  await expect(trigger).toBeFocused();
});

/** P12 보안 점검: production build는 보안 헤더를 붙이고, CSP 아래에서도 화면이 동작한다(위 시나리오들이 CSP 하에서 실행됨). */
test("production responses carry security headers", async ({ page }) => {
  const response = await page.goto("/");
  const headers = response!.headers();
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["content-security-policy"]).toContain("frame-ancestors 'none'");
  expect(headers["content-security-policy"]).toContain("connect-src 'self' http://localhost:8081");
  expect(headers["x-powered-by"]).toBeUndefined();
});
