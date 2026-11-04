# ADR-005: 데모 인증 — `X-Demo-Staff-Id` 헤더

- 상태: Accepted (MVP 데모 한정)
- 날짜: 2026-11-04
- 관련: v4.1 01 §4 "역할 기반 demo access", 02 §11 OPEN "실제 auth 방식", 04 §5 RBAC

## 맥락
모든 command는 요청자가 필요하다. Idempotency-Key 범위(actor + endpoint + key), 이벤트 actor, property 범위 검증, 역할 권한이 모두 요청자에 의존한다. 실제 인증 방식(데모 로그인 / 외부 IdP)은 OPEN이지만, P2.5 이후 command를 만들려면 요청자 모델이 먼저 있어야 한다.

## 결정
- 요청자 모델 `Actor(staffId, propertyId, role)`를 도입한다. 서비스와 정책은 이 타입에만 의존한다.
- MVP에서는 **`X-Demo-Staff-Id` 헤더의 활성 Staff를 요청자로** 본다 (`DemoActorArgumentResolver`).
  - 헤더 없음, UUID 아님, 없는 staff, 비활성 staff → 401 `UNAUTHENTICATED`.
- property 범위: 요청자의 propertyId와 다른 리소스는 404 `NOT_FOUND` (존재 노출 방지).
- 역할 권한: `Actor.requireRole(...)` 위반 → 403 `FORBIDDEN`.

## 이 방식의 한계 (명시)
- **보안 수단이 아니다.** 헤더만 알면 누구든 다른 직원으로 행세할 수 있다. 합성 데이터 데모 환경에서만 쓴다.
- 공개 배포 시에는 최소한 데모 전용 로그인(세션/서명 토큰)으로 교체하거나 접근을 제한해야 한다. → P12에서 ADR-007로 결정.
- 교체 시 `Actor` 해석기만 바꾸면 되도록 서비스 코드에서 헤더를 직접 읽지 않는다.

## 대안
| 대안 | 기각/보류 이유 |
|---|---|
| Spring Security + 데모 로그인 | 화면(P9) 전에 만들면 범위 대비 비용이 큼. P12 전 재검토 |
| 외부 IdP(OIDC) | 과제 범위 초과, 계정 관리 필요 |
| 인증 없이 actor를 body로 받음 | 서비스가 요청 본문을 신뢰하게 되어 경계가 흐려짐 |
