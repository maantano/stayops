# ADR-007: 배포 전 인증 — 데모 헤더 유지, 실제 인증은 도입 조건으로 기록

- 상태: Accepted (P12)
- 날짜: 2026-11-04
- 관련: ADR-005(데모 인증), 02 §11 OPEN "실제 auth 방식", `docs/deploy/DEPLOYMENT.md`, `docs/deploy/SECURITY_REVIEW.md`

## 맥락
P12는 배포 구성(Dockerfile·Terraform·배포 문서)을 만들지만 **실제 배포는 하지 않는다**(데모용 MVP). ADR-005의 `X-Demo-Staff-Id` 헤더는 보안 수단이 아니므로, 배포 구성을 만드는 시점에 인증을 어떻게 다룰지 정해야 한다.

## 결정
1. **MVP의 인증은 ADR-005 데모 헤더를 그대로 쓴다.** 코드 변경 없음.
2. 배포 profile(`aws`)은 **"합성 데이터 공개 데모"** 전용이다. `demo-mode`를 켠 채 운영하고, 화면·응답에 합성 데이터 표시를 유지한다. 실제 호텔 데이터·개인정보를 넣지 않는다.
3. 공개 데모로 실제 배포한다면 최소한 다음 중 하나를 먼저 적용한다(배포 문서의 체크리스트):
   - ALB 앞단 접근 제한(사내 IP 허용 목록, CloudFront + 기본 인증, 또는 Cognito 인증 리스너), 또는
   - 데모 전용 서명 세션(아래 2단계 1).
4. 실제 운영 데이터를 다루기 전에 아래 순서로 실제 인증을 도입한다. 서비스·정책은 `Actor`에만 의존하므로 **바꾸는 곳은 `Actor` 해석기와 FE 세션뿐**이다.

| 단계 | 내용 | 바뀌는 곳 |
|---|---|---|
| 1 | Spring Security 도입, OIDC(예: Cognito/사내 IdP) access token 검증. `sub` → Staff 매핑 테이블 | `DemoActorArgumentResolver` → `JwtActorArgumentResolver`, V6 migration(staff_identity) |
| 2 | `demo-mode=false`면 `/api/v1/demo/**` 비활성(이미 조건부 등록), 헤더 해석기 비활성 | 설정 |
| 3 | FE: Auth.js 등으로 로그인, access token을 `Authorization: Bearer`로 전달. 데모 사용자 선택 화면은 demo 빌드에서만 | `createStayOpsApi`의 header 주입부, `/` 화면 |
| 4 | Idempotency 범위(actor + endpoint + key), 이벤트 actor, property 범위 검증은 그대로 | 없음 |

## 이유
- MVP 범위에서 IdP·계정 관리를 만드는 비용이 크고, 이 프로젝트의 핵심(운영 흐름·동시성·readiness·시뮬레이션)과 무관하다.
- ADR-005에서 헤더 해석을 `Actor` 경계 뒤로 숨겨 두었으므로 교체 비용이 작고 범위가 명확하다.
- "보안 수단이 아님"을 숨기지 않고 배포 profile·문서·화면에 명시하는 편이 반쯤 만든 인증보다 정직하다.

## 결과
- 배포 문서와 보안 점검 문서에 한계를 명시한다. Terraform은 HTTPS 인증서·접근 제한을 변수/체크리스트로 남긴다.
- 실제 인증은 02 §11에서 OPEN을 해제하지 않고 "도입 조건이 정해진 후속 작업"으로 둔다.
