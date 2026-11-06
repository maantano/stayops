import { expect, test } from "@playwright/test";
import { expectNoSeriousA11yViolations, loginAs, openTasks, STAFF, taskCard } from "./support";

/** 05 §8 / 03 §8: 주요 화면 axe serious·critical 0, 키보드만으로 dialog 열기·닫기와 포커스 복귀. */
for (const colorScheme of ["light", "dark"] as const) {
test.describe(`${colorScheme} mode`, () => {
test.use({ colorScheme });

test("main screens have no serious or critical axe violations", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expect(page.getByRole("button", { name: /^MGR-01/ })).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  // 사용 설명서: 로그인 없이 열리고, 목차로 이동할 수 있다
  await page.getByRole("link", { name: "사용 설명서 보기" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "StayOps 사용 설명서" })).toBeVisible();
  for (const tab of await page.getByRole("tab").all()) {
    await tab.click();
    await expect(tab).toHaveAttribute("aria-selected", "true");
    await expectNoSeriousA11yViolations(page);
  }
  await page.getByRole("link", { name: "← StayOps로 돌아가기" }).click();

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

  // OS 색 모드를 따른다: 페이지 배경과 카드 표면이 모두 모드에 맞는 밝기여야 한다
  // (수동 확인에서 발견: 다크 모드에서 배경만 어둡고 카드는 흰색, 글자는 밝아 안 보이던 "반쪽 다크 모드" 회귀 방지)
  const lightness = await page.evaluate(() => {
    const ctx = document.createElement("canvas").getContext("2d")!;
    const luma = (el: Element) => {
      ctx.clearRect(0, 0, 1, 1);
      ctx.fillStyle = getComputedStyle(el).backgroundColor; // oklch 등도 canvas가 sRGB로 바꿔 준다
      ctx.fillRect(0, 0, 1, 1);
      const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
      return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
    };
    return { page: luma(document.body), card: luma(document.querySelector(".rounded-lg.border")!) };
  });
  if (colorScheme === "dark") {
    expect(lightness.page).toBeLessThan(0.2);
    expect(lightness.card).toBeLessThan(0.2);
  } else {
    expect(lightness.page).toBeGreaterThan(0.9);
    expect(lightness.card).toBeGreaterThan(0.9);
  }
});
});
}

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
