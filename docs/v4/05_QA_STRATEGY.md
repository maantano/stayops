# 05. QA Strategy & Acceptance Criteria

## 1. QA 목표
화면이 정상적으로 보이는 것보다 **객실·예약·작업 상태가 어떤 명령 순서에서도 모순되지 않는 것**을 우선한다. 핵심 위험은 상태 불일치, 중복 task, 동시 명령 race, 잘못된 check-in 허용, 잘못된 readiness 확신이다.

## 2. 테스트 피라미드

| 레벨 | 범위 | 도구 |
|---|---|---|
| Domain unit | state transition, policy, readiness | JUnit 5 |
| Repository integration | constraints, locks, queries | Testcontainers PostgreSQL |
| API integration | auth, validation, idempotency, transaction | Spring test |
| Contract | OpenAPI ↔ FE DTO | OpenAPI validator/contract tests |
| FE unit/component | forms, badges, query cache, error state | Vitest, RTL |
| E2E | 핵심 업무 시나리오 | Playwright |
| Simulation regression | seed/snapshot reproducibility | Kotlin tests + Python pytest |

H2/HSQL 등 PostgreSQL과 동작이 다른 DB만으로 제약 테스트를 대체하지 않는다.

## 3. 필수 도메인 테스트 매트릭스

### Room/Availability
- checkout은 OCCUPIED에서만 성공.
- checkout 성공은 Reservation CHECKED_OUT, Room VACANT_DIRTY, checkout task 1개를 원자 반영.
- cleaning complete 직후 VACANT_DIRTY.
- inspection 생성/배정만으로 INSPECTING 금지.
- inspection start 시 INSPECTING.
- PASSED → READY.
- FAILED → VACANT_DIRTY + RE_CLEANING.
- READY + OUT_OF_SERVICE check-in 거부.
- OOS 해제만으로 READY 전환 금지.
- OOS 설정이 기존 CHECKED_IN 예약을 자동 checkout/cancel하지 않음.

### Task lifecycle
- PENDING/ASSIGNED/IN_PROGRESS/BLOCKED에서 허용된 blocker 추가.
- BLOCKED 상태에서 두 번째 blocker 추가.
- blocker 일부 해제 시 BLOCKED 유지.
- 마지막 blocker 해제 후 saved state 복귀.
- 복귀 시 assignment invalid → PENDING + assignment 종료.
- task cancel 시 미해결 blocker 자동 종결.
- COMPLETED/CANCELLED terminal.
- task당 활성 assignment 1개.
- 동일 room의 incompatible active workflow 중복 금지.

### Cancel matrix
- 시작 전 CHECKOUT_CLEANING 취소: Room unchanged.
- 시작 후 CHECKOUT_CLEANING 취소: VACANT_DIRTY.
- 시작 후 RE_CLEANING 취소: VACANT_DIRTY.
- 시작 후 INSPECTION 취소: VACANT_DIRTY, READY 금지.
- 시작 후 STAYOVER_CLEANING 취소: OCCUPIED.
- IN_PROGRESS cancel은 MANAGER만.
- cancel reason 필수.

### Reservation
- check-in은 CONFIRMED + READY + AVAILABLE 요구.
- check-in 이후 Room OCCUPIED.
- checkout 이후 CHECKED_OUT.
- 중복 check-in/out 재요청은 멱등.
- 같은 객실 예약 구간 겹침 race test.
- No-show/cancel 정책에 맞는 상태 전이.
- check-in 예정 이전/이후 허용 window 테스트.

### Stayover
- OCCUPIED + CHECKED_IN에서만 생성.
- 생성 시 Room OCCUPIED, Task PENDING.
- 중복 활성 stayover 거부.
- start 시 STAYOVER_CLEANING.
- BLOCKED 중 STAYOVER_CLEANING 유지.
- complete/cancel 시 OCCUPIED.
- Reservation은 CHECKED_IN 유지.

## 4. 동시성/복구 테스트
1. 동일 task에 두 worker가 동시에 start: 하나만 성공.
2. 동일 reservation에 두 check-in: 하나만 성공.
3. 동시에 checkout 및 cancel: 하나만 성공, 상태 일관.
4. 동일 room에 checkout과 stayover create 경쟁: 유효한 명령만 성공.
5. blocker resolve와 task cancel 경쟁.
6. inspection result 중복 제출.
7. 같은 Idempotency-Key 같은 payload replay.
8. 같은 key 다른 payload → 409.
9. DB commit 실패 시 domain change/event/idempotency result rollback.
10. 서버 timeout 후 retry에서 중복 task/event 없음.

## 5. Readiness 계산 테스트
- 필수 입력이 모두 있는 정상 case.
- READY 객실.
- READY + OOS.
- 필수 정보 누락 → UNKNOWN.
- blocker 해제 예상시각 없음 → 임의 시간 대입 금지.
- 병렬 blocker 중복 가산 금지.
- 이미 경과한 작업시간 중복 합산 금지.
- staff shift 부족/미등록.
- 작업자가 없는 task.
- dueAt/check-in deadline 경계.
- timezone/DST.
- 동일 snapshot/version 결과 재현.
- 계산 breakdown 합계와 expectedReadyAt 일관성.

## 6. Property-based / invariant tests
가능하면 property-based framework로 다음을 무작위 command sequence에 대해 검증:
- Room은 정의된 enum 외 상태가 될 수 없음.
- BLOCKED task에는 활성 blocker가 최소 1개.
- COMPLETED/CANCELLED task에는 활성 assignment 없음.
- READY check-in은 AVAILABLE일 때만 성공.
- CHECKED_IN reservation과 Room occupancy 관계가 일관.
- 객실별 incompatible active task가 중복되지 않음.
- 모든 status transition은 event를 남김.
- aggregate version은 감소하지 않음.
- event/DB state는 transaction 단위로 함께 commit/rollback.

무작위 테스트 실패 seed와 command sequence를 artifact로 남긴다.

## 7. FE QA
- loading/empty/error/unknown/stale 별도 렌더링.
- 403/404/409/422/500 처리.
- timeout retry는 동일 idempotency key.
- 중복 클릭/폼 중복 제출 방지.
- timezone과 날짜 경계.
- keyboard-only 핵심 흐름.
- dialog focus, accessible label, status text.
- READY + OOS의 시각적 혼동 방지.
- 화면상 task status와 Room workflow status 혼동 방지.

## 8. E2E 핵심 시나리오
1. 예약 check-out → checkout task 생성.
2. task assign/start → blocker 추가/해제.
3. 청소 complete → VACANT_DIRTY.
4. inspection task 생성/배정 → VACANT_DIRTY.
5. inspection start → INSPECTING.
6. inspection FAILED → re-clean task.
7. re-clean complete → 새 inspection → PASSED → READY.
8. READY + OOS → check-in 차단.
9. OOS 복구 후 check-in 성공.
10. 투숙 중 청소 생성 → start → blocker → complete → OCCUPIED 유지.
11. 작업 취소 전/후 Room state matrix 검증.
12. 대시보드 readiness 원인과 Room detail breakdown 일치.
13. baseline/scenario simulation 비교.

## 9. QA release gate
- 모든 P0 테스트 통과.
- P1 미해결 항목은 명시적 승인 없이는 배포 금지.
- migration fresh install 통과.
- lint/typecheck/build 통과.
- API contract test 통과.
- Playwright 핵심 흐름 통과.
- seed 재현성 통과.
- known limitations와 OPEN 목록 릴리즈 노트 포함.

## 10. 결함 우선순위
- P0: 잘못된 check-in, 예약/객실 상태 손상, 데이터 유실, 권한 우회, 중복 업무 생성.
- P1: blocker/취소/검사 흐름 오류, readiness의 허위 정상 판정, event 누락.
- P2: 화면 표시/필터/정렬/접근성 문제로 운영 판단에 영향.
- P3: 미관/경미한 사용성.
