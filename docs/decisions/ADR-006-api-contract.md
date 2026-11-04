# ADR-006: API 계약 — 서버 생성 OpenAPI 스냅샷 + FE 타입 생성

- 상태: Accepted
- 날짜: 2026-11-04
- 관련: v4.1 02 §6·§7, 03 §6, 05 §2 Contract, 06 P8

## 맥락
FE와 BE가 같은 요청·응답·오류 계약을 써야 한다. 계약이 코드와 따로 놀면(수기 문서, 수기 타입) 변경이 누락된다. 동시에 계약 변경은 리뷰에서 눈에 보여야 한다.

## 결정
1. **서버 코드가 계약의 원천.** `springdoc-openapi-starter-webmvc-api 3.1.1`(Spring Boot 4.1 기반, UI 없음)로 `/v3/api-docs`를 생성한다.
   - 헤더에서 해석되는 인자(`Actor`, `CommandRequest`)는 파라미터에서 숨기고 헤더로 문서화한다: `X-Demo-Staff-Id`(필수), command의 `Idempotency-Key`(필수), `X-Correlation-Id`(선택).
   - 오류는 `Problem` 스키마(`application/problem+json`)로 400/401/403/404(+command 409)를 문서화한다.
   - 응답 기본 media type은 `application/json`, 서버 URL은 상대 경로 `/`(실행 포트가 스냅샷에 섞이지 않게).
2. **저장소에 스냅샷을 커밋한다:** `docs/api/openapi.json` (키 정렬·들여쓰기 정규화). `OpenApiContractTests`가 생성 결과와 스냅샷이 다르면 실패한다 → API 변경이 diff로 드러난다.
   - 갱신: `STAYOPS_WRITE_OPENAPI=true ./gradlew test --tests '*OpenApiContractTests*'`
   - 같은 테스트가 "모든 command에 Idempotency-Key 필수, 모든 endpoint에 데모 요청자 헤더, 내부 인자가 query로 새지 않음, command에 409 문서화"를 검사한다.
3. **FE 타입은 스냅샷에서 생성한다:** `openapi-typescript 7.13.0` → `apps/web/src/api/schema.d.ts` (`pnpm api:types`). 호출은 `openapi-fetch 0.17.0`으로 경로·파라미터·본문 타입을 컴파일 시점에 검사한다.
4. 의존성 버전은 확인 시점 기준 2주 이상 지난 안정 버전을 고정한다.

## 대안
| 대안 | 기각 이유 |
|---|---|
| 계약 우선(OpenAPI 수기 작성 → 서버 코드 생성) | Kotlin 서버 코드 생성기 도입 비용, 도메인 오류 확장 필드 표현이 번거로움 |
| 수기 TypeScript 타입 | 서버 변경과 어긋나도 감지되지 않음 |
| springdoc UI 포함 | 데모 범위에서 불필요, 노출면 증가 |
| orval 등 hooks까지 생성 | Idempotency-Key 재사용·무효화 규칙(03 §5·§6)을 직접 통제하기 어려움 → hooks는 수작성 |

## 영향
- API를 바꾸는 PR은 `docs/api/openapi.json`과 `schema.d.ts` 변경을 함께 포함해야 한다(테스트가 강제).
- 중첩 DTO 이름이 스키마 이름이 된다(`Row`, `Shift`, `Blocker` 등). 충돌이 생기면 DTO 이름을 바꾼다.
