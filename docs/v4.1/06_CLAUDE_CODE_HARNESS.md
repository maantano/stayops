# 06. Claude Code Harness (v4.1)

## 1. 목적
Claude Code는 실제 저장소 조사와 구현을 담당한다. 문서에 적힌 목표 구조를 현재 저장소 사실로 간주하지 않는다. 이 문서의 기준은 `docs/README.md`가 가리키는 v4.1이다.

### 1.1 진행 현황 (2026-11-04)
| 항목 | 상태 |
|---|---|
| Phase 0 저장소 조사 | 완료 (v1~v4 문서만 존재, 코드 없음) |
| 문서 v4 → v4.1 | 승인 완료 (`08_V4.1_CHANGE_LOG.md`) |
| 개발 환경 | OrbStack(Docker 29.8.2, Compose v5.5.0), uv 0.13.7, OpenJDK 21.0.13 (Homebrew `openjdk@21`, `~/Library/Java/JavaVirtualMachines`에 등록) |
| Git | `handys/`에서 `git init -b main`, `.gitignore` 작성, 문서 baseline 커밋 |
| 다음 | P1 — ADR 작성 및 scaffold |

## 2. 필수 원칙
- Phase 0은 read-only.
- 승인 전 Git init, dependency install, scaffold, 파일 이동/삭제, migration 실행 금지.
- 기존 작업물/working tree 보존.
- 구현하지 않은 기능을 구현 완료로 보고하지 않음.
- 실행하지 않은 테스트를 통과로 보고하지 않음.
- 합성 데이터를 실제 운영 데이터로 표현하지 않음.
- 외부 PMS/OTA/고객 개인정보 연결 금지.
- 문서 충돌/도메인 미결정 발견 시 임의 결정 대신 OPEN으로 보고.
- 각 변경은 목적·파일·검증·리스크를 함께 보고.

## 3. Phase 0 — Read-only reconnaissance (완료, v4 기준으로 수행)
1. `docs/README.md` 및 `docs/v4/` 전부 읽기.
2. pwd, 디렉터리 구조, Git branch/status/log 확인.
3. 기존 uncommitted changes 확인.
4. Node/JDK/Kotlin/Python 및 package manager 확인.
5. 기존 app/api/db/infra 구조 확인.
6. migration 및 Docker Compose 상태 확인.
7. 현재 코드와 Room×Task matrix 차이 확인.
8. 테스트/lint/typecheck/build 명령 확인.
9. 환경변수/secret 파일 존재만 확인하고 값 출력 금지.
10. v1/v2/v3 문서 위치 확인. 이동/삭제 금지.
11. OPEN decision과 구현 영향 목록 작성.

Phase 0 결과:
- 관찰한 사실과 추정 구분
- 변경 파일 0개 증명
- 기존 변경사항 목록
- 충돌/리스크/OPEN
- Phase 1 제안
- 사용자 승인 대기

## 4. 구현 진행 게이트
각 Phase마다:
1. 목표 및 acceptance criteria 요약
2. 변경 파일 예상 목록
3. 영향 받는 도메인/API/FE/QA 계약
4. 구현
5. 관련 unit/integration/contract/E2E 실행
6. 문서 변경 필요성 확인
7. 완료/미완료 및 잔여 리스크 보고
8. 다음 Phase 승인 대기

## 5. 구현 순서
- P0: repository reconnaissance (완료)
- P1: ADR 확정, project scaffold 승인
- P2: schema/core domain + migration
- **P2.5 `[v4.1]`: command 인프라** — Idempotency-Key 저장소, OperationalEvent append, Room lock helper(02 §10.1 순서), RFC 9457 Problem Details + 오류 코드(02 §7.1), correlationId 전파, STATE_INCONSISTENT 로깅
- P3: reservation check-in/out (checkout × stayover 포함)
- P4: task/assignment/blocker/cancel, start 판정 표·재시작
- P5: inspection/re-clean/stayover/OOS/**rework create**, workflowStage
- P6: 동시성 강화 테스트 (race, property-based, deadlock)
- P7: readiness calculation
- P8: API contract + FE query layer
- P9: dashboard/room/task UI
- P10: synthetic generator/offline analysis
- P11: simulation
- P12: E2E, accessibility, security, deployment

## 6. 변경 금지 규칙
- 이미 적용된 Flyway migration 수정 금지.
- v1/v2/v3/v4 문서 덮어쓰기 금지.
- 저장 상태 모순을 코드에서 조용히 보정 금지 (STATE_INCONSISTENT로 노출).
- "시작 전/후" 판단에 status를 쓰지 말 것 — 항상 `actualStartedAt`.
- Room/Task/Reservation enum을 문서 갱신 없이 임의 변경 금지.
- 상태 전이 로직을 Controller나 FE에 중복 구현 금지.
- JPA entity를 API response로 직접 노출 금지.
- 시뮬레이션 baseline input mutate 금지.
- 실패한 테스트를 삭제하거나 skip 처리해 녹색으로 만들지 말 것.
- flaky test는 원인과 재현 조건을 보고.

## 7. 실행 프롬프트

### 7.1 다음 세션 (P1)
```text
StayOps docs/v4.1 기준으로 P1을 진행한다.
docs/README.md와 docs/v4.1/ 문서 8개를 모두 읽어라.

1. docs/decisions/에 ADR을 작성해 승인을 받는다: 저장소 구조(handys/ 루트, apps/web, apps/api, tools/simulator),
   런타임 버전(JDK 21, Node 22 LTS, Python 3.11+, uv), 패키지 매니저(pnpm), Docker 런타임(OrbStack), lock 정책(02 §10.1).
2. 승인 후 scaffold: Next.js(App Router, TS strict), Spring Boot Kotlin(Gradle wrapper, toolchain 21),
   uv 프로젝트, docker-compose PostgreSQL, .env.example, health endpoint, README 실행 방법.
3. build/lint/test와 health check를 실제로 실행하고 결과를 보고한다.
도메인 코드는 P2 이전에 작성하지 않는다.
```

### 7.2 (이력) v4 Phase 0 프롬프트
```text
StayOps docs/v4 기준으로 Phase 0 read-only repository reconnaissance만 수행해라.

먼저 docs/README.md를 읽고 현재 기준 버전을 확인한 뒤 docs/v4/ 문서 7개를 모두 읽어라.

절대 코드/문서를 수정하지 마라. 파일 생성·삭제·이동, Git init, dependency install, scaffold, migration 실행도 금지한다.

실제 저장소 구조, Git 상태와 uncommitted changes, toolchain, 기존 web/api/db/infra, migration, 테스트 명령, secret 파일 존재(값은 출력 금지), v4 도메인 규칙과 기존 코드의 차이를 조사해라.

특히 다음을 확인해라:
- Room operationalStatus와 availabilityStatus 분리
- Room × Task lifecycle matrix
- inspection task start 시점의 INSPECTING 전환
- task 생성/배정이 Room 상태를 바꾸지 않는지
- blocker 추가/복수 blocker/해제/취소
- task cancel과 Room 복귀
- stayover 수동 생성
- READY + OUT_OF_SERVICE check-in 거부
- idempotency 및 동시성 보호
- event와 domain transaction 원자성

사실/추측/미결정을 분리하고, 파일 변경 0개인 상태로 보고한 뒤 중단해라. 사용자 확인 전 다음 Phase를 시작하지 마라.
```

## 8. 리뷰 체크리스트
### 기획 리뷰
- 실제 문제라고 과장하지 않았는가?
- 사용자 역할/권한/업무 순서가 설명되는가?
- MVP 범위가 과도하지 않은가?
- 성공 지표의 분모와 한계가 명시되었는가?

### FE 리뷰
- 모든 상태/오류/빈 화면이 정의됐는가?
- command race/stale data/retry를 처리하는가?
- Room과 Task 상태를 혼동하지 않는가?
- timezone/accessibility가 고려됐는가?

### BE 리뷰
- transaction, idempotency, locking, constraints가 있는가?
- 권한/property scope가 서버에서 검증되는가?
- 이벤트·상태가 원자적으로 변경되는가?
- 시간·중복·동시성·취소 규칙이 명확한가?

### QA 리뷰
- 정상뿐 아니라 취소/차단/경쟁 조건을 검증하는가?
- P0/P1 기준이 있는가?
- DB 제약을 실제 PostgreSQL로 테스트하는가?
- 같은 입력 재현성과 계산 설명이 검증되는가?

## 9. 완료 보고
```text
## 작업 요약
## 변경 파일
## 구현한 acceptance criteria
## 실행한 검증 (명령/결과)
## 실행하지 못한 검증과 이유
## 알려진 결함/OPEN
## 문서 동기화
## 다음 단계 제안
```
