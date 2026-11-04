import AxeBuilder from "@axe-core/playwright";
import { expect, type Locator, type Page } from "@playwright/test";

/**
 * 데모 사용자 선택 화면에서 직원을 고른다 (ADR-005). 로그인 상태면 선택 화면이 홈으로 보내므로,
 * 저장된 데모 세션을 먼저 지워 항상 선택 화면에서 시작한다.
 */
export async function loginAs(page: Page, displayName: string) {
  if (page.url().startsWith("http")) await page.evaluate(() => (localStorage.clear(), sessionStorage.clear()));
  await page.goto("/");
  await page.getByRole("button", { name: new RegExp(`^${escape(displayName)}`) }).click();
  await expect(page.getByRole("button", { name: "사용자 변경" })).toBeVisible();
}

export const STAFF = {
  manager: "MGR-01 관리자",
  frontDesk: "FD-01 프런트",
  hk1: "HK-01 하우스키핑",
  hk2: "HK-02 하우스키핑",
  inspector: "IN-01 검사",
} as const;

/** 작업 보드의 작업 카드 (객실 번호 + 작업 종류로 찾는다). */
export function taskCard(page: Page, roomNumber: string, type: string): Locator {
  return page
    .getByRole("article")
    .filter({ has: page.getByRole("link", { name: `${roomNumber}호`, exact: true }) })
    .filter({ hasText: type });
}

export async function openTasks(page: Page) {
  await page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name: "작업" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "작업" })).toBeVisible();
}

/** 명령 성공 토스트를 기다린다 (toast는 role=status). */
export async function expectToast(page: Page, text: string | RegExp) {
  await expect(page.getByRole("status").filter({ hasText: text }).first()).toBeVisible();
}

/** axe: serious·critical 위반 0 (05 §8). 위반이 있으면 규칙 id와 대상을 보여 준다. */
export async function expectNoSeriousA11yViolations(page: Page) {
  const result = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  const serious = result.violations
    .filter((v) => v.impact === "serious" || v.impact === "critical")
    .map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`);
  expect(serious).toEqual([]);
}

function escape(text: string) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** 옵션 라벨이 동적(배정 건수 등)이므로 이름이 들어간 옵션의 value로 고른다. */
export async function selectOptionContaining(select: Locator, text: string) {
  const value = await select.locator("option", { hasText: text }).first().getAttribute("value");
  if (!value) throw new Error(`option containing "${text}" not found`);
  await select.selectOption(value);
}

/** 관리자: 작업 보드에서 작업을 직원에게 배정한다. */
export async function assignTask(page: Page, roomNumber: string, type: string, staff: string) {
  const card = taskCard(page, roomNumber, type);
  await card.getByRole("button", { name: /^(배정|재배정)$/ }).click();
  const dialog = page.getByRole("dialog");
  await selectOptionContaining(dialog.getByRole("combobox"), staff);
  await dialog.locator("button[type=submit]").click();
  await expect(dialog).toBeHidden();
  await expect(card).toContainText(staff);
}
