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
- 잠금 대기 시간 상한(`lock_timeout`)을 설정하고, 초과 시 503이 아니라 409 계열로 재시도를 안내할지 P2.5에서 결정한다(`OPEN`).
- QA: v4.1 05 §4의 race·deadlock 테스트로 검증한다.
