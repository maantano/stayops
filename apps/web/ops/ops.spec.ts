import path from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { OPS_API } from "../playwright.ops.config";
import { assignTask, expectNoSeriousA11yViolations, expectToast, loginAs, openTasks, STAFF, taskCard } from "../e2e/support";

/**
 * 운영 테스트 — docs/qa/TEST_CASES.md의 케이스를 실행 중인 데모 서비스에서 그대로 수행한다.
 * 테스트 이름의 앞부분이 케이스 ID다. 순서대로 데모 데이터를 바꾸므로 직렬로 실행한다.
 */
test.describe.configure({ mode: "serial" });

const EVIDENCE = path.resolve(__dirname, "../ops-results/evidence");
const shot = (page: Page, name: string) => page.screenshot({ path: `${EVIDENCE}/${name}.png`, fullPage: false });
const nav = (page: Page, name: string) => page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link", { name, exact: true }).click();
const arrival = (page: Page, room: string) => page.getByRole("region", { name: "도착 예정" }).getByRole("listitem").filter({ hasText: `${room}호` });
const inHouse = (page: Page, room: string) => page.getByRole("region", { name: "투숙 중" }).getByRole("listitem").filter({ hasText: `${room}호` });

// ------------------------------------------------------------------ 접속·역할

test("TC-A01 데모 사용자 선택 화면: 직원 6명, 데모 데이터 표시, 사용 설명서 링크", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(/데모 사용자 선택/);
  await expect(page.getByText("데모 데이터").first()).toBeVisible();
  await expect(page.getByText(/합성/)).toHaveCount(0);
  for (const name of Object.values(STAFF)) await expect(page.getByRole("button", { name: new RegExp(`^${name}`) })).toBeVisible();
  await expect(page.getByRole("button", { name: /^HK-03/ })).toBeVisible();
  await expect(page.getByRole("link", { name: "사용 설명서" })).toBeVisible();
  await shot(page, "TC-A01-login");
});

test("TC-A02 역할별 메뉴: 관리자·프런트 5개, 하우스키핑·검사 2개", async ({ page }) => {
  const expected: [string, string[]][] = [
    [STAFF.manager, ["대시보드", "객실", "작업", "예약", "시뮬레이션"]],
    [STAFF.frontDesk, ["대시보드", "객실", "작업", "예약", "시뮬레이션"]],
    [STAFF.hk1, ["객실", "작업"]],
    [STAFF.inspector, ["객실", "작업"]],
  ];
  for (const [who, menus] of expected) {
    await loginAs(page, who);
    await expect(page.getByRole("navigation", { name: "주 메뉴" }).getByRole("link")).toHaveText(menus);
  }
});

test("TC-A03 하우스키핑은 자기 작업만 본다", async ({ page }) => {
  await loginAs(page, STAFF.hk1);
  await openTasks(page);
  await expect(page.getByText("본인에게 배정된 작업만 표시합니다.")).toBeVisible();
  const cards = page.getByRole("article");
  await expect(cards.first()).toBeVisible();
  for (const card of await cards.all()) await expect(card).toContainText("HK-01");
  await shot(page, "TC-A03-hk-own-tasks");
});

// ------------------------------------------------------------------ 객실 준비 흐름

test("TC-T01 근무가 끝난 직원 배정은 한국어 안내와 함께 거부된다", async ({ page }) => {
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await taskCard(page, "202", "체크아웃 청소").getByRole("button", { name: "배정" }).click();
  const dialog = page.getByRole("dialog");
  const select = dialog.getByRole("combobox");
  const value = await select.locator("option", { hasText: "HK-03" }).getAttribute("value");
  await select.selectOption(value!);
  await dialog.locator("button[type=submit]").click();
  const alert = dialog.getByRole("alert");
  await expect(alert).toContainText("선택한 직원이 작업 예정 시각에 근무하지 않습니다");
  await expect(alert).toContainText("추적 ID");
  await shot(page, "TC-T01-shift-conflict");
  await dialog.getByRole("button", { name: "닫기" }).click();
});

test("TC-F01 202호 전체 흐름: 배정→시작→차단·해제→완료→검사 실패→재청소→재검사 통과→체크인", async ({ page }) => {
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await assignTask(page, "202", "체크아웃 청소", STAFF.hk2);

  await loginAs(page, STAFF.hk2);
  await openTasks(page);
  const cleaning = taskCard(page, "202", "체크아웃 청소");
  await cleaning.getByRole("button", { name: "시작" }).click();
  await expect(cleaning).toContainText("진행 중");
  await cleaning.getByRole("button", { name: "차단 등록" }).click();
  const block = page.getByRole("dialog", { name: "202호 작업 차단 등록" });
  await block.getByLabel("설명").fill("린넨 카트 대기 (운영 테스트)");
  await block.getByLabel("예상 해제까지 (분, 선택)").fill("15");
  await block.locator("button[type=submit]").click();
  await expect(cleaning).toContainText("차단됨");
  await shot(page, "TC-F01-blocked");
  await cleaning.getByRole("button", { name: "해제" }).click();
  await expect(cleaning).toContainText("진행 중");
  await cleaning.getByRole("button", { name: "완료" }).click();
  await expectToast(page, "작업을 완료했습니다");

  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await assignTask(page, "202", "검사", STAFF.inspector);

  await loginAs(page, STAFF.inspector);
  await openTasks(page);
  const inspection = taskCard(page, "202", "검사");
  await inspection.getByRole("button", { name: "시작" }).click();
  await inspection.getByRole("button", { name: "검사 결과 입력" }).click();
  const fail = page.getByRole("dialog", { name: "202호 검사 결과" });
  await fail.getByLabel("욕실").uncheck();
  await fail.getByLabel(/실패 → 재청소/).check();
  await fail.locator("button[type=submit]").click();
  await expect(fail.getByText("실패 사유를 입력하세요")).toBeVisible(); // 사유 없이 실패 제출 불가
  await fail.getByLabel("실패 사유").fill("욕실 물기 (운영 테스트)");
  await fail.locator("button[type=submit]").click();
  await expect(fail).toBeHidden();

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
  await loginAs(page, STAFF.inspector);
  await openTasks(page);
  const reInspection = taskCard(page, "202", "검사");
  await reInspection.getByRole("button", { name: "시작" }).click();
  await reInspection.getByRole("button", { name: "검사 결과 입력" }).click();
  await page.getByRole("dialog", { name: "202호 검사 결과" }).locator("button[type=submit]").click();

  await loginAs(page, STAFF.frontDesk);
  await page.goto("/rooms");
  await page.getByRole("link", { name: "202호", exact: true }).first().click();
  await expect(page.getByText("운영 상태: 준비 완료")).toBeVisible();
  const timeline = page.getByRole("region", { name: "이벤트 타임라인" });
  await expect(timeline).toContainText(/검사/);
  await shot(page, "TC-F01-room-ready-timeline");
  await nav(page, "예약");
  await arrival(page, "202").getByRole("button", { name: "체크인" }).click();
  await expectToast(page, "202호 체크인했습니다");
});

// ------------------------------------------------------------------ 예약

test("TC-R01 준비 안 된 객실은 체크인 버튼이 꺼지고 남은 일을 알려 준다 / 준비된 101호는 체크인된다", async ({ page }) => {
  await loginAs(page, STAFF.frontDesk);
  await nav(page, "예약");
  const room204 = arrival(page, "204");
  await expect(room204.getByRole("button", { name: "체크인" })).toBeDisabled();
  await expect(room204).toContainText("객실 청소 중");
  await expect(room204).toContainText("청소 완료와 검사가 남았습니다");
  await shot(page, "TC-R01-checkin-hints");
  const room101 = arrival(page, "101");
  await expect(room101).toContainText("객실 준비 완료");
  await room101.getByRole("button", { name: "체크인" }).click();
  await expectToast(page, "101호 체크인했습니다");
});

test("TC-R02 판매 중단 객실(102)은 체크인 불가 → 관리자 판매 재개 → 체크인", async ({ page }) => {
  await loginAs(page, STAFF.frontDesk);
  await nav(page, "예약");
  await expect(arrival(page, "102").getByRole("button", { name: "체크인" })).toBeDisabled();
  await expect(arrival(page, "102")).toContainText("판매 중단된 객실이라 체크인할 수 없습니다");

  await loginAs(page, STAFF.manager);
  await page.goto("/rooms");
  await page.getByRole("link", { name: "102호", exact: true }).first().click();
  await page.getByRole("button", { name: "판매 재개" }).click();
  const dialog = page.getByRole("dialog", { name: "102호 판매 재개" });
  await dialog.locator("button[type=submit]").click();
  await expect(dialog.getByText("사유를 입력하세요")).toBeVisible(); // 사유 필수
  await dialog.getByLabel("사유").fill("점검 완료 (운영 테스트)");
  await dialog.locator("button[type=submit]").click();
  await expect(page.getByText("판매 상태: 판매 가능")).toBeVisible();

  await loginAs(page, STAFF.frontDesk);
  await nav(page, "예약");
  await arrival(page, "102").getByRole("button", { name: "체크인" }).click();
  await expectToast(page, "102호 체크인했습니다");
});

test("TC-R03 투숙 중 청소가 진행 중이면 체크아웃이 거부되고 해결 방법을 보여 준다(104)", async ({ page }) => {
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  await assignTask(page, "104", "투숙 중 청소", STAFF.hk1);
  await loginAs(page, STAFF.hk1);
  await openTasks(page);
  await taskCard(page, "104", "투숙 중 청소").getByRole("button", { name: "시작" }).click();

  await loginAs(page, STAFF.frontDesk);
  await nav(page, "예약");
  await expect(inHouse(page, "104")).toContainText("투숙 중 청소가 진행 중이라 지금은 체크아웃이 거부됩니다");
  await inHouse(page, "104").getByRole("button", { name: "체크아웃" }).click();
  const refusal = inHouse(page, "104").getByRole("alert");
  await expect(refusal).toContainText("시작된 투숙 중 청소가 있어 체크아웃할 수 없습니다");
  await expect(refusal).toContainText("담당자가 차단을 해제하고 청소를 완료");
  await shot(page, "TC-R03-checkout-refused");

  await loginAs(page, STAFF.hk1);
  await openTasks(page);
  await taskCard(page, "104", "투숙 중 청소").getByRole("button", { name: "완료" }).click();
  await expectToast(page, "작업을 완료했습니다");
  await loginAs(page, STAFF.frontDesk);
  await nav(page, "예약");
  await inHouse(page, "104").getByRole("button", { name: "체크아웃" }).click();
  await expectToast(page, "104호 체크아웃했습니다");
});

// ------------------------------------------------------------------ 작업 예외

test("TC-T02 시작 전 재청소 취소 후 관리자가 재청소 작업을 다시 만든다(303)", async ({ page }) => {
  await loginAs(page, STAFF.manager);
  await openTasks(page);
  const reClean = taskCard(page, "303", "재청소");
  await reClean.getByRole("button", { name: "작업 취소" }).click();
  const cancel = page.getByRole("dialog");
  await cancel.getByLabel("취소 사유").fill("운영 계획 변경 (운영 테스트)");
  await cancel.locator("button[type=submit]").click();
  await expect(reClean).toHaveCount(0);

  await page.goto("/rooms");
  await page.getByRole("link", { name: "303호", exact: true }).first().click();
  await expect(page.getByText("업무 단계: 청소 필요").first()).toBeVisible();
  await page.getByRole("button", { name: "재청소 작업 만들기" }).click();
  const rework = page.getByRole("dialog");
  await rework.getByLabel("사유").fill("취소 후 재작업 (운영 테스트)");
  await rework.locator("button[type=submit]").click();
  await expect(page.getByRole("region", { name: "진행 중인 작업" })).toContainText("재청소");
  await shot(page, "TC-T02-rework-created");
});

// ------------------------------------------------------------------ 준비 예측·대시보드

test("TC-D01 대시보드 준비 예측: 쉬운 등급 이름과 예측 불가 이유(304 DND, 201 퇴실 지연)", async ({ page }) => {
  await loginAs(page, STAFF.manager);
  const section = page.getByRole("region", { name: /준비 예측/ });
  await expect(section).toContainText("손님 도착 전에 객실이 준비될까요?");
  for (const grade of ["늦을 예정", "예측 불가", "빠듯함", "여유 있음", "준비 완료"]) await expect(section).toContainText(grade);
  const list = page.getByRole("list", { name: "확인이 필요한 객실" });
  await expect(list.getByRole("listitem").filter({ hasText: "304호" })).toContainText("차단 해제 예상 시각 없음");
  await expect(list.getByRole("listitem").filter({ hasText: "201호" })).toContainText("예정 체크아웃이 지났지만 아직 투숙 중");
  await expect(page.getByText(/준비 위험|판정 불가/)).toHaveCount(0);
  await shot(page, "TC-D01-dashboard");
});

// ------------------------------------------------------------------ 시뮬레이션

test("TC-S01 시뮬레이션: 관리자가 80실·14일 데모 데이터셋으로 3개 방식 비교, 프런트는 보기만", async ({ page }) => {
  const dataset = path.resolve(__dirname, "../../../tools/simulator/output/dataset-42.json");
  await loginAs(page, STAFF.manager);
  await nav(page, "시뮬레이션");
  await page.getByLabel("데모 데이터셋 올리기 (gen-v1 JSON)").setInputFiles(dataset);
  await expect(page.getByLabel("데이터셋").locator("option", { hasText: "데모 호텔 A" }).first()).toBeAttached();
  await page.getByLabel("대안 A: 준비 마감이 급한 객실부터").check();
  await page.getByLabel("대안 B: 하우스키핑 교대 시작을 모두 09:00으로").check();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const table = page.getByRole("region", { name: "비교 결과" }).getByRole("table");
  await expect(table.getByRole("row", { name: /정시 준비율/ })).toContainText("81.5%"); // DATASET.md 기록값(seed 42, 327/401)과 같은지
  await expect(table.getByRole("row", { name: /정시 준비율/ })).toContainText("96.3%");
  await shot(page, "TC-S01-simulation");

  await loginAs(page, STAFF.frontDesk);
  await nav(page, "시뮬레이션");
  await expect(page.getByRole("heading", { name: "정책 비교 실행" })).toHaveCount(0);
});

// ------------------------------------------------------------------ 가이드·화면

test("TC-U01 사용 설명서: 5개 탭, 링크로 탭 열기, 정리된 표현", async ({ page }) => {
  await page.goto("/guide");
  await expect(page.getByRole("tab")).toHaveText(["빠른 시작", "역할별", "상태별", "메뉴별", "문제 해결"]);
  await page.getByRole("tab", { name: "역할별" }).click();
  await expect(page).toHaveURL(/#roles$/);
  await expect(page.getByRole("tabpanel", { name: "역할별" })).toContainText("하루 흐름");
  await shot(page, "TC-U01-guide-roles");
  await page.goto("/guide#readiness");
  await expect(page.getByRole("tab", { name: "상태별" })).toHaveAttribute("aria-selected", "true");
  await page.getByRole("tab", { name: "문제 해결" }).click();
  await expect(page.getByRole("heading", { name: "막혔을 때 해결 방법" })).toBeVisible();
  await shot(page, "TC-U02-guide-help");
  await expect(page.getByText(/합성|자주 만나는|준비 위험|판정 불가/)).toHaveCount(0);
});

for (const colorScheme of ["light", "dark"] as const) {
  test(`TC-U03 ${colorScheme === "light" ? "라이트" : "다크"} 모드: 주요 화면 접근성(axe serious·critical 0)과 캡처`, async ({ browser }) => {
    const context = await browser.newContext({ colorScheme, viewport: { width: 1280, height: 900 }, locale: "ko-KR", timezoneId: "Asia/Seoul" });
    const page = await context.newPage();
    await loginAs(page, STAFF.manager);
    for (const [menu, file] of [["대시보드", "dashboard"], ["객실", "rooms"], ["작업", "tasks"], ["예약", "reservations"], ["시뮬레이션", "simulations"]] as const) {
      await nav(page, menu);
      await page.waitForLoadState("networkidle");
      await expectNoSeriousA11yViolations(page);
      await shot(page, `TC-U03-${colorScheme}-${file}`);
    }
    await page.goto("/guide#states");
    await expectNoSeriousA11yViolations(page);
    await shot(page, `TC-U03-${colorScheme}-guide-states`);
    await context.close();
  });
}

// ------------------------------------------------------------------ API 보안·계약 (실행 중인 API에 직접 요청)

test("TC-X01 API 경계: 요청자 없음 401 · 권한 없음 403 · 다른 호텔 404 · 키 없는 명령 400 · 큰 본문 413 · 재전송 멱등", async ({ request }) => {
  const actors = await (await request.get(`${OPS_API}/api/v1/demo/actors`)).json();
  const hotel = actors[0];
  const id = (role: string) => hotel.actors.find((a: { role: string }) => a.role === role).staffId as string;
  const base = `${OPS_API}/api/v1/properties/${hotel.propertyId}`;

  const noActor = await request.get(`${base}/rooms`);
  expect(noActor.status()).toBe(401);
  expect((await noActor.json()).code).toBe("UNAUTHENTICATED");

  const forbidden = await request.get(`${base}/reservations`, { headers: { "X-Demo-Staff-Id": id("HOUSEKEEPER") } });
  expect(forbidden.status()).toBe(403);

  const otherHotel = await request.get(`${OPS_API}/api/v1/properties/00000000-0000-0000-0000-000000000000/rooms`, { headers: { "X-Demo-Staff-Id": id("MANAGER") } });
  expect(otherHotel.status()).toBe(404);

  const rooms = await (await request.get(`${base}/rooms`, { headers: { "X-Demo-Staff-Id": id("MANAGER") } })).json();
  const room = rooms.items.find((r: { roomNumber: string }) => r.roomNumber === "301");
  const noKey = await request.post(`${OPS_API}/api/v1/rooms/${room.roomId}/availability`, {
    headers: { "X-Demo-Staff-Id": id("MANAGER") },
    data: { status: "OUT_OF_SERVICE", reason: "운영 테스트" },
  });
  expect(noKey.status()).toBe(400);
  expect((await noKey.json()).code).toBe("VALIDATION_ERROR");

  const tooLarge = await request.post(`${base}/simulation-datasets`, {
    headers: { "X-Demo-Staff-Id": id("MANAGER"), "Idempotency-Key": crypto.randomUUID(), "Content-Type": "application/json" },
    data: JSON.stringify({ padding: "x".repeat(6 * 1024 * 1024) }),
  });
  expect(tooLarge.status()).toBe(413);
  expect((await tooLarge.json()).code).toBe("PAYLOAD_TOO_LARGE");

  // 같은 키로 두 번 보내면 두 번째는 최초 응답을 재생하고 상태를 다시 바꾸지 않는다 (301은 원래 판매 가능 → 중단 → 같은 키 재전송)
  const key = crypto.randomUUID();
  const send = () =>
    request.post(`${OPS_API}/api/v1/rooms/${room.roomId}/availability`, {
      headers: { "X-Demo-Staff-Id": id("MANAGER"), "Idempotency-Key": key },
      data: { status: "OUT_OF_SERVICE", reason: "운영 테스트 멱등" },
    });
  const first = await send();
  const second = await send();
  expect(first.status()).toBe(200);
  expect(second.status()).toBe(200);
  expect(second.headers()["idempotency-replayed"]).toBe("true");
  expect(await second.json()).toEqual(await first.json());
  // 원래대로 돌려 둔다
  await request.post(`${OPS_API}/api/v1/rooms/${room.roomId}/availability`, {
    headers: { "X-Demo-Staff-Id": id("MANAGER"), "Idempotency-Key": crypto.randomUUID() },
    data: { status: "AVAILABLE", reason: "운영 테스트 원복" },
  });
});
