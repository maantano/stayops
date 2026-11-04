/**
 * 데모 사용자 세션 (ADR-005). 선택한 직원 id와 소속 property id를 브라우저에 보관한다.
 * 보안 수단이 아니며, 저장소 접근이 막힌 환경(시크릿 모드 등)에서도 메모리 값으로 동작한다.
 */
export type DemoSession = { staffId: string; propertyId: string; displayName?: string; role?: string };

const STORAGE_KEY = "stayops.demoSession";
let memory: DemoSession | null = null;

export function loadDemoSession(): DemoSession | null {
  if (memory) return memory;
  try {
    const raw = typeof window === "undefined" ? null : window.localStorage.getItem(STORAGE_KEY);
    memory = raw ? (JSON.parse(raw) as DemoSession) : null;
  } catch {
    memory = null;
  }
  return memory;
}

export function saveDemoSession(session: DemoSession | null): void {
  memory = session;
  try {
    if (typeof window === "undefined") return;
    if (session) window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // 저장 실패는 무시한다(메모리 세션은 유지).
  }
}

/** 사용자 행동 1회당 하나. 같은 행동의 재시도에는 같은 값을 쓴다 (02 §7). */
export function newIdempotencyKey(): string {
  return `web-${crypto.randomUUID()}`;
}
