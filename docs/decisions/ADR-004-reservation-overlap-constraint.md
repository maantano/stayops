# ADR-004: 예약 시간 겹침 방지 — PostgreSQL exclusion constraint

- 상태: Accepted
- 날짜: 2026-11-04
- 관련: v4.1 02 §2 Reservation, §11 OPEN "exclusion constraint vs transaction lock", 04 §4

## 맥락
같은 객실에 시간이 겹치는 활성 예약이 두 개 생기면 안 된다. v4.1은 이를 OPEN으로 두되 "DB exclusion constraint가 우선 후보, 적용 불가하면 객실 단위 lock"이라고 기술했다. P2에서 스키마를 만들기 위해 결정이 필요하다.

## 결정
PostgreSQL exclusion constraint를 사용한다.

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE reservation ADD CONSTRAINT reservation_no_overlap
  EXCLUDE USING gist (
    room_id WITH =,
    tstzrange(scheduled_check_in_at, scheduled_check_out_at, '[)') WITH &&
  ) WHERE (status IN ('CONFIRMED', 'CHECKED_IN'));
```

- 구간은 v4.1 정의대로 `[checkIn, checkOut)`. 앞 예약의 체크아웃 시각과 뒤 예약의 체크인 시각이 같아도 겹치지 않는다.
- CANCELLED, NO_SHOW, CHECKED_OUT 예약은 검사 대상에서 제외한다.
- 같은 원리로 **staff 근무시간 겹침**도 exclusion constraint로 막는다(`staff_shift_no_overlap`).
- 추가로 객실당 `CHECKED_IN` 예약은 최대 1개(partial unique index).

## 적용 가능성 확인
- `btree_gist`는 PostgreSQL 공식 이미지(alpine 포함)에 contrib로 포함되어 있다. P2 통합 테스트에서 실제 PostgreSQL 17로 검증한다.
- 클라우드 배포(RDS 등)에서도 `btree_gist`는 지원 확장 목록에 있다 (배포 단계에서 재확인, `OPEN`).

## 대안
| 대안 | 기각 이유 |
|---|---|
| 객실 단위 lock + 애플리케이션 검증 | 동시성 정확성이 모든 쓰기 경로의 코드 규율에 의존. DB가 최종 보장을 못 함 |
| SERIALIZABLE | ADR-003과 같은 이유 |

## 영향
- 위반 시 SQLState `23P01`(exclusion_violation) → 409 `SCHEDULE_CONFLICT`로 변환한다(P2.5 오류 매핑).
- 예약 시각 수정(room move, 연장)은 MVP 범위 밖이며, 추가 시에도 같은 제약이 적용된다.
