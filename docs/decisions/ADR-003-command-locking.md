# ADR-003: Command 동시성 제어 (lock 정책)

- 상태: Accepted
- 날짜: 2026-11-04
- 관련: v4.1 02 §10.1, 04 §4, 08 §1.4

## 맥락
체크아웃·작업 시작·차단·취소·재정비 생성 등은 같은 객실(Room)의 상태와 그 객실의 Reservation·Task를 함께 바꾼다. 동시 요청(예: 체크아웃과 투숙 중 청소 시작)이 들어오면 결과가 "어느 하나가 먼저 실행된 것"과 같아야 하며, 부분 반영이나 교착이 없어야 한다.

## 결정
1. **room workflow command는 Room 행 비관적 잠금(`SELECT … FOR UPDATE`)으로 객실 단위 직렬화한다.**
   대상: check-in, check-out, stayover create, task start/complete/cancel, blocker add/resolve, inspection-result, rework create, availability 변경.
2. **잠금 순서를 고정한다:** `Room → Reservation → HousekeepingTask → TaskAssignment/TaskBlocker`. 모든 command가 같은 순서를 따르므로 교착이 생기지 않는다.
3. 그 외 aggregate(Property, Staff, Shift 등)는 낙관적 잠금(`version`)을 쓴다.
4. 클라이언트가 보낸 stale version은 잠금 방식과 무관하게 409 `VERSION_CONFLICT`.
5. 객실당 active task 1개 규칙은 Room 잠금 + 서비스 검증 + **partial unique index**(최종 방어선)로 보장한다.
6. 잠금 획득 후 선행조건을 평가한다. 두 번째 요청은 첫 번째가 commit한 최신 상태로 평가된다.

## 대안
| 대안 | 기각 이유 |
|---|---|
| 낙관적 잠금만 사용 | 경쟁 시 한쪽이 `VERSION_CONFLICT`로 실패하고 클라이언트 재시도가 필요. 체크아웃×start 같은 경우 결과가 순서에 따라 결정되어야 하는데 재시도 정책에 의존하게 됨 |
| SERIALIZABLE 격리 수준 | 직렬화 실패 재시도 로직이 모든 command에 필요, 원인 진단이 어려움 |
| 애플리케이션 메모리 락 | 다중 인스턴스 배포(ECS)에서 무효 |
| PostgreSQL advisory lock | 행 잠금으로 충분하며 잠금 대상이 도메인 데이터와 분리되어 추적이 어려움 |

## 영향
- 같은 객실에 대한 command는 순차 처리되므로 객실 단위 처리량이 제한된다. 객실 하나에 동시 요청이 많지 않은 도메인이라 허용 가능하다고 판단한다(`ASSUMPTION`).
- QA: v4.1 05 §4의 race·deadlock 테스트로 검증한다.

## 개정 (2026-11-04, P2.5 구현)
1. **잠금 대기 상한:** 연결 초기화 시 `SET lock_timeout = '${stayops.db.lock-timeout}'` (기본 5s). 초과·deadlock·낙관적 잠금 충돌은 모두 **409 `VERSION_CONFLICT`**로 응답한다. 클라이언트 처리(재조회 후 재시도)가 같으므로 별도 코드를 만들지 않는다.
2. **잠금 획득 방식:** `EntityManager.find(..., PESSIMISTIC_WRITE)`. 이미 같은 transaction에서 읽은 엔티티라도 JPA 명세상 version 검사가 수행되므로 오래된 상태로 판단하지 않는다. id만 아는 command(task/reservation 기준)는 roomId를 스칼라 조회한 뒤 Room부터 잠근다.
3. **순서 강제:** `RoomLockScope`가 Room → Reservation → Task 순서를 어기면 `IllegalStateException`(500)으로 즉시 실패한다. 코드 버그를 운영 전에 드러내기 위함이다.
4. **Idempotency-Key 직렬화:** 같은 key의 동시 요청은 `pg_advisory_xact_lock(hash(actor, endpoint, key))`으로 직렬화한다. 도메인 잠금이 아니라 중복 요청 처리용이므로 위 "advisory lock 기각"과 충돌하지 않는다.
5. **검증:** `CommandInfrastructureTests`(같은 객실 command 직렬화, 같은 key 동시 요청 1회 실행), `LockTimeoutTests`(lock_timeout 초과 → 409, 부분 반영 없음).

## 개정 2 (2026-11-04, P6에서 발견한 결함)
6. **command 시각은 잠금 이후에 정한다.** `CommandContext.now`는 처음 읽을 때 한 번 정해지며, 서비스는 객실 잠금을 잡은 뒤에만 읽는다.
   - 발견: 무작위 동시 실행 테스트(seed 101, 102)에서 같은 task에 대한 동시 재배정이 500을 냈다. 늦게 잠금을 얻은 command가 잠금 대기 전 시각으로 앞선 배정을 종료해 `unassigned_at < assigned_at`(`assignment_interval_valid`) 위반.
   - 원칙: 잠금으로 직렬화된 실행 순서와 기록 시각의 순서가 일치해야 한다. 이벤트 `occurredAt`, 상태 전이 시각, 이력 구간 모두 이 시각을 쓴다.
   - 검증: `TaskLifecycleTests$Concurrency` 회귀 테스트(수정을 되돌리면 실패, 적용하면 통과 확인), 모든 command 함수가 잠금 호출 뒤에 시각을 읽는지 점검.
