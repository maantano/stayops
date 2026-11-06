# 07. Cross-functional Deep Dive Review

> 리뷰 관점: Product / Frontend / Backend / QA / Operations & Data  
> 방식: 현재 v3 설계에 대한 독립 역할별 체크리스트 리뷰를 문서화하고 상충 규칙을 통합했다. 실제 병렬 subagent 실행이나 외부 전문가 검토를 수행했다고 주장하지 않는다.

## 1. 리뷰 범위와 한계

이번 검토는 v3 문서를 기준으로 다음 관점에서 교차 검토했다.
- Product: 사용자·업무·범위·측정 가능성
- FE: 정보 구조·상태 표시·명령 UX·접근성·실패 복구
- BE: 도메인 불변식·transaction·동시성·보안·시간
- QA: 상태 조합·경계·race·회귀·재현성
- Operations/Data: 현장 흐름의 모호성·지표·합성 데이터·설명 가능성

이는 역할별 리뷰 프레임을 적용한 설계 검토다. Claude Code/IDE의 실제 subagent를 병렬 실행한 결과가 아니다. 운영자 인터뷰, 실제 운영사 내부 시스템 접근, 실제 운영 데이터 검증은 수행하지 않았다.

## 2. v3에서 재발견한 추가 빈틈

| ID | 관점 | 빈틈 | v4 반영 |
|---|---|---|---|
| X01 | Domain | Task 생성 시 Room 상태를 언제 바꾸는지 모호 | 생성/배정은 상태 불변, start만 workflow 전환 |
| X02 | BE/QA | BLOCKED 중 추가 blocker 생성 불가 문구와 복수 blocker 처리 충돌 | BLOCKED에서도 blocker 추가 허용 |
| X03 | BE | task 취소 시 미해결 blocker와 활성 assignment 잔존 | 취소 시 assignment 종료, blocker TASK_CANCELLED 종결 |
| X04 | BE/QA | 동일 객실의 서로 충돌하는 활성 task 중복 방지 미정 | active workflow invariant + DB/lock 보호 요구 |
| X05 | FE/BE | task start가 PENDING에서도 가능한지, assignment 필수인지 불명 | MVP는 ASSIGNED + 유효 assignment + blocker 없음 |
| X06 | Product/BE | No-show, 예약 취소, 체크인 허용 시간 정책 누락 | lifecycle/API 추가, cutoff는 OPEN |
| X07 | BE | 예약 객실 시간 구간·시간대·DST 정의 부족 | [checkIn, checkOut), Instant 저장, property timezone |
| X08 | BE/FE | 재시도/timeout 시 명령 결과 불명 및 중복 실행 | Idempotency-Key + request hash + 기존 결과 반환 |
| X09 | BE | 이벤트 원자성은 있으나 외부 전달/스키마 진화 기준 부족 | payload schema version, outbox는 외부 전달 시 ADR |
| X10 | FE | stale state/409/timeout/더블클릭 대응 부족 | command UX contract 추가 |
| X11 | FE | Room 상태와 availability의 표시 혼동 | 독립 badge/필드, READY+OOS 별도 표현 |
| X12 | QA | 실제 PostgreSQL 제약 및 race 검증 부족 | Testcontainers, concurrent command tests |
| X13 | Product/Data | readiness가 정밀 예측처럼 오해될 위험 | 입력 snapshot, breakdown, UNKNOWN, 한계 표시 |
| X14 | Simulation | seed만 같으면 재현된다는 과도한 단순화 | input snapshot + generator/policy version 포함 |
| X15 | Security | 권한 모델, property isolation, PII/logging 누락 | demo RBAC matrix, scope check, 합성 데이터 only |
| X16 | FE/QA | timezone, DST, keyboard/a11y 고려 부족 | FE/BE/QA 공통 기준 추가 |
| X17 | Operations | OOS 중 이미 투숙한 예약의 처리 불명 | 자동 checkout/cancel 금지, 신규 check-in만 차단 |
| X18 | Operations | stayover 검사/maintenance 세부 정책 미결정 | MVP 기본 정책과 OPEN 분리 |
| X19 | API | pagination/filter/sort 및 오류 계약 미정 | cursor, stable sort, RFC 9457, error codes |
| X20 | Data | KPI 분모, 취소/No-show 처리 불명 | KPI 정의 및 집계 조건 명시 |

## 3. 상태 모델의 가장 중요한 원칙

### 3.1 세 개의 독립된 축
1. Reservation: 고객의 예약/투숙 lifecycle
2. Room: 물리 객실의 업무 단계 + 판매/투숙 가용성
3. Task: 개별 작업의 실행 lifecycle

어느 한 축의 enum을 다른 축의 대체물로 사용하지 않는다.

### 3.2 Room state transition 요약
| 이벤트 | Reservation | Room operational | Room availability | Task |
|---|---|---|---|---|
| checkout | CHECKED_OUT | VACANT_DIRTY | 유지 | checkout PENDING 생성 |
| cleaning start | 유지 | CLEANING | 유지 | IN_PROGRESS |
| cleaning complete | 유지 | VACANT_DIRTY | 유지 | COMPLETED + inspection PENDING |
| inspection start | 유지 | INSPECTING | 유지 | IN_PROGRESS |
| inspection pass | 유지 | READY | 유지 | COMPLETED |
| inspection fail | 유지 | VACANT_DIRTY | 유지 | COMPLETED + re-clean PENDING |
| check-in | CHECKED_IN | OCCUPIED | AVAILABLE 필수 | 변경 없음 |
| stayover create | CHECKED_IN | OCCUPIED | AVAILABLE 필수 | PENDING |
| stayover start | CHECKED_IN | STAYOVER_CLEANING | 유지 | IN_PROGRESS |
| stayover finish | CHECKED_IN | OCCUPIED | 유지 | COMPLETED |
| OOS set | 유지 | 유지 | OUT_OF_SERVICE | 자동 변경 없음 |

## 4. 교차 기능 수용 조건

### Product
- 문제를 사실로 과장하지 않는다.
- 사용자별 job과 권한을 정의한다.
- MVP에 결제/PMS integration/ML을 끌어들이지 않는다.
- 위험/UNKNOWN/정상 상태를 구분한다.
- KPI 분모와 한계를 공개한다.

### FE
- Room/Task/Reservation/Availability 축을 별도로 표시한다.
- 서버 응답을 source of truth로 한다.
- stale, timeout, 409, 403, 422 처리.
- idempotent retry.
- timezone/DST, accessibility, responsive.

### BE
- command precondition을 단일 domain service에서 검증.
- aggregate lock/version.
- domain state + event + derived task atomic.
- property scope/RBAC.
- active workflow uniqueness.
- migration 및 DB constraint 검증.

### QA
- lifecycle matrix 모든 행을 검증.
- 모든 command의 중복/동시 요청 검증.
- 상태 조합 pairwise/property-based test.
- PostgreSQL Testcontainers.
- seed/snapshot/version 재현성.

## 5. P0/P1 리스크

### P0 — 구현 전에 반드시 해결
1. 동일 객실에서 상충하는 workflow가 동시에 시작되지 않도록 보호.
2. Reservation/Room/Task 상태 변경의 원자성.
3. check-in은 READY + AVAILABLE를 모두 요구.
4. inspection start/finish의 정확한 전이.
5. task cancel/blocker 복귀 후 Room 상태 보존.
6. 동일 command 재시도와 concurrent request 중복 방지.
7. property scope/role authorization.
8. DB 제약 및 동시성 테스트.

### P1 — MVP 배포 전 해결
1. timezone/DST 및 check-in window.
2. pagination/stable sort.
3. event payload schema version.
4. readiness UNKNOWN 및 계산 breakdown.
5. accessibility 및 FE error recovery.
6. KPI 분모/취소/No-show 처리.
7. simulation snapshot/version reproducibility.
8. audit log와 개인정보 제외.

## 6. 명시적으로 남긴 OPEN
- 실제 운영사의 운영 프로세스와 내부 시스템 연동 범위
- 예약 check-in 허용 시간 및 late arrival policy
- No-show cutoff와 권한
- 객실 이동/room reassignment 지원
- 동일 직원 동시 task 허용 및 이동시간
- 검사 실패 후 재검사 횟수/정책
- stayover inspection 요구 여부
- OOS 중 진행 중인 task를 계속할지 중단할지
- 시설 maintenance task 범위
- blocker의 직렬/병렬 계산
- auth provider 및 세션 방식
- 예약 중복 방지 구현 전략
- online simulation 비동기 전환 임계치

OPEN 항목은 구현자가 임의로 결정하지 않는다. 관련 기능 구현 전 ADR 또는 명시적 제품 결정을 요구한다.

## 7. 리뷰의 잔여 한계
- FE/BE/QA의 실제 코드 리뷰는 아직 불가능하다. 저장소와 구현 코드가 제공되지 않았다.
- 역할별 검토는 설계 문서에 대한 사전 리뷰다.
- 실제 운영자 인터뷰 및 현장 관찰이 없다.
- 외부 PMS API 계약 및 실제 운영사 내부 시스템을 확인하지 않았다.
- v4가 모든 미래 빈틈이 없음을 보장하지 않는다. 대신 발견 시 문서·API·상태표·테스트를 함께 수정하도록 절차화한다.
