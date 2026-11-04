# StayOps — 기술 설계·도메인 모델

> 목적: 프론트엔드·백엔드·시뮬레이터가 같은 업무 규칙을 구현하도록 하는 기술 기준  
> 설계 상태: 아래 7개 이슈의 해결안은 사용자 승인 전까지 `PROPOSAL`이다. 구현 전에 승인/수정이 필요하다.  
> 아키텍처 원칙: 모듈러 모놀리스, 서버 중심 도메인 규칙, 명시적 Command API, 트랜잭션 내 이벤트 기록

## 1. 기술 스택

| 영역 | 선택 | 목적 |
|---|---|---|
| Frontend | Next.js App Router, React, TypeScript strict | 운영 화면 |
| UI | Tailwind CSS, shadcn/ui | UI 시스템 |
| Server state | TanStack Query | API 캐시/동기화 |
| Form | React Hook Form, Zod | 폼 검증 |
| Backend | Kotlin, Spring Boot | REST·도메인 로직 |
| Persistence | Spring Data JPA | 저장소 |
| DB | PostgreSQL | 관계형 데이터 및 JSONB |
| Migration | Flyway | 스키마 이력 |
| Contract | REST, OpenAPI | FE/BE 계약 |
| Automation | Python, uv, pytest | 합성 데이터·오프라인 실험 |
| Tests | JUnit 5, Testcontainers, Vitest, RTL, Playwright | 단위·통합·E2E |
| Local | Docker Compose | 로컬 환경 |
| Deploy | Vercel, AWS ECS | FE/BE 배포 |
| IaC | Terraform | 인프라 선언 |

GCP 등은 초기 MVP에서 도입하지 않는다. 채용 공고에 언급된 기술을 모두 억지로 사용하는 것이 목표가 아니다.

## 2. 아키텍처

### 2.1 모듈러 모놀리스

단일 Spring Boot 애플리케이션과 PostgreSQL을 사용한다. 도메인 패키지 경계를 두되 네트워크 기반 서비스 분리는 하지 않는다.

모듈:
- property
- room
- reservation
- staff
- housekeeping
- readiness
- simulation
- operations

객실·예약·작업의 상태 변경이 원자적으로 이뤄져야 하므로 초기 분산 아키텍처를 피한다.

### 2.2 Python 경계

**권장안 (`PROPOSAL`):**
- 온라인 운영 API와 SimulationRun의 생성·상태·결과 저장은 Kotlin 백엔드가 담당한다.
- Python은 오프라인 합성 데이터 생성, 대량 실험, KPI 분석 도구로 사용한다.
- 초기에는 Python 상시 API 서버나 Kotlin의 Python subprocess 호출을 만들지 않는다.
- MVP 온라인 시뮬레이션은 Kotlin의 순수 도메인/애플리케이션 코드로 수행한다. Python 실험 결과를 가져오는 경우 파일 기반 명시적 import로 제한한다.

이렇게 하면 실행 위치가 불명확한 API를 만들지 않고, 초기 배포·관측·장애 지점을 줄일 수 있다. 사용자가 Python 엔진을 온라인 실행의 기준으로 선택하면 ADR을 갱신한다.

## 3. 저장소 구조

프로젝트 루트는 현재 작업 디렉터리 `handys/`를 후보로 한다. 실제 저장소 여부·기존 파일은 Phase 0에서 확인한다.

```text
handys/
├── docs/
│   ├── 01_PRODUCT_DISCOVERY.md
│   ├── 02_TECHNICAL_DESIGN.md
│   ├── 03_CLAUDE_CODE_HARNESS.md
│   ├── 04_SEVEN_DESIGN_GAPS.md
│   └── decisions/
├── apps/
│   ├── web/
│   └── api/
├── tools/
│   └── simulator/
├── infra/
│   ├── docker/
│   └── terraform/
├── docker-compose.yml
└── README.md
```

이 구조는 목표 구조다. 실제 폴더가 다르면 기존 구조를 보존하고 사용자에게 차이를 보고한다. Git 저장소가 아니면 Phase 0에서 초기화 필요 여부를 보고하고 확인을 기다린다.

## 4. 공통 데이터 규칙

- PK: UUID
- 시각 저장: PostgreSQL `TIMESTAMPTZ`, Kotlin `Instant`
- 호텔 현지 날짜/시간: Property의 IANA timezone 기준
- DB에는 UTC instant 저장, UI에는 property timezone으로 표시
- JSONB는 이벤트 payload 및 시뮬레이션 파라미터/메트릭 등 부가 데이터에 제한
- 중요 aggregate에 optimistic locking `version`
- `created_at`, `updated_at`
- soft delete 기본 미사용; 취소/비활성은 도메인 상태로 표현
- 애플리케이션 검증과 DB constraint 병행
- 명령 API는 가능한 경우 `Idempotency-Key` 지원
- 상태 변경과 이벤트는 동일 DB transaction 안에서 기록

## 5. 도메인 모델

### 5.1 Property

`id, name, timezone, defaultCheckInTime, defaultCheckOutTime, operationalBufferMinutes, createdAt, updatedAt, version`

### 5.2 Room

`id, propertyId, roomNumber, roomType, floor, operationalStatus, availabilityStatus, createdAt, updatedAt, version`

`RoomOperationalStatus`:
- `VACANT_DIRTY`
- `CLEANING`
- `INSPECTING`
- `READY`
- `OCCUPIED`
- `STAYOVER_CLEANING`

`RoomAvailabilityStatus`:
- `AVAILABLE`
- `OUT_OF_SERVICE`

**의미 분리:** 운영 상태는 객실이 현재 어떤 업무 단계인지, availability는 객실을 판매/사용할 수 있는지 나타낸다. `OUT_OF_SERVICE`는 운영 상태와 별도 축이다. 시설 복구만으로 READY가 되지 않는다.

`STAYOVER_CLEANING`은 투숙 중 청소가 실제 진행 중임을 나타내는 상태다. 작업 완료 후 `OCCUPIED`로 복귀한다. `READY`로 전환하지 않는다.

### 5.3 Reservation

`id, propertyId, roomId, status, scheduledCheckInAt, scheduledCheckOutAt, actualCheckInAt, actualCheckOutAt, guestCount, createdAt, updatedAt, version`

`ReservationStatus`: `CONFIRMED`, `CHECKED_IN`, `CHECKED_OUT`, `CANCELLED`, `NO_SHOW`

시간이 겹치는 활성 예약의 동일 객실 중복 배정을 방지한다. PostgreSQL exclusion constraint 적용 가능성을 검토한다. 구현이 복잡하면 동일 객실 예약 생성/배정 유스케이스에서 트랜잭션 잠금과 검증을 적용하고 테스트한다.

### 5.4 Staff / StaffShift

Staff:
`id, propertyId, name, role, skills, active, version`

역할: `HOUSEKEEPER`, `INSPECTOR`, `MANAGER`

StaffShift:
`id, staffId, workDate, startsAt, endsAt`

작업 배정/시작 시 역할, 활성 여부, 근무시간을 검증한다. 초기 정책은 근무시간 외 신규 작업 시작을 거부한다. 예상 완료가 근무 종료 이후인 경우 경고 또는 거부 정책은 별도 설정한다.

### 5.5 HousekeepingTask

필드:
- `id, propertyId, roomId, reservationId(nullable), sourceTaskId(nullable)`
- `type, status, estimatedDurationMinutes`
- `actualStartedAt, actualCompletedAt`
- `statusBeforeBlocked(nullable)`
- `createdAt, updatedAt, version`

TaskType:
- `CHECKOUT_CLEANING`
- `STAYOVER_CLEANING`
- `RE_CLEANING`
- `INSPECTION`

TaskStatus:
- `PENDING`
- `ASSIGNED`
- `IN_PROGRESS`
- `BLOCKED`
- `COMPLETED`
- `CANCELLED`

`statusBeforeBlocked`는 BLOCKED 진입 직전 상태(`PENDING`, `ASSIGNED`, `IN_PROGRESS`)를 저장한다. BLOCKED 외 상태에서는 null이어야 한다.

### 5.6 TaskAssignment

`id, taskId, staffId, assignedAt, unassignedAt(nullable), assignedBy`

이력을 보존한다. 현재 활성 배정은 `unassignedAt IS NULL`. 작업당 활성 배정은 한 명으로 제한한다. 재배정 시 기존 배정을 닫고 새 레코드를 생성한다. 진행 중 재배정은 작업 상태를 유지하되 작업 이벤트에 이전/신규 담당자와 시각을 남긴다.

### 5.7 TaskBlocker

`id, taskId, type, description, blockedAt, estimatedResolutionAt(nullable), resolvedAt(nullable), resolvedBy(nullable)`

BlockerType:
- `DND`
- `LINEN_SHORTAGE`
- `SUPPLY_SHORTAGE`
- `MAINTENANCE`
- `STAFF_UNAVAILABLE`
- `OTHER`

한 작업에 여러 blocker가 존재할 수 있다. blocker는 PENDING, ASSIGNED, IN_PROGRESS 상태 작업에 등록할 수 있다. 활성 blocker가 하나라도 있으면 task는 BLOCKED다. 개별 blocker를 해결하고 남은 활성 blocker가 0개일 때만 이전 상태로 복귀한다.

### 5.8 InspectionRecord

`id, taskId(unique), result, failureReason(nullable), inspectedBy, inspectedAt`

result: `PASSED`, `FAILED`

검사는 별도 `HousekeepingTask(type=INSPECTION)`로 관리하고 `InspectionRecord`는 검사 결과를 저장한다. 검사 시작은 task start 명령으로 처리하며, 결과 제출은 별도 inspection 명령으로 처리한다. 검사 task 하나당 결과 레코드 하나를 둔다. 재검사는 새로운 INSPECTION task를 생성한다.

FAILED이면 `failureReason` 필수이며, 원 검사 task를 `COMPLETED` 처리하고 후속 `RE_CLEANING` task를 생성한다. 재청소 완료 후 새로운 검사 task가 생성된다.

### 5.9 OperationalEvent

`id, propertyId, entityType, entityId, eventType, actorType, actorId(nullable), correlationId, idempotencyKey(nullable), payload(JSONB), occurredAt`

상태 변경과 같은 transaction에서 기록한다. 이벤트는 타임라인과 사후 분석용이며 도메인 테이블의 대체물이 아니다.

### 5.10 SimulationRun

`id, propertyId, seed, policy, parameters(JSONB), metrics(JSONB), status, startedAt, completedAt, createdAt`

상태: `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED` (동기 실행이면 QUEUED/RUNNING이 짧게 유지될 수 있음). 초기에는 단일 백엔드 프로세스 내 실행으로 단순화하고, 긴 실행이 확인되면 비동기 worker를 검토한다.

## 6. 상태 전이

### 6.1 객실 상태

체크아웃:
```text
OCCUPIED --checkout--> VACANT_DIRTY
VACANT_DIRTY --cleaning starts--> CLEANING
CLEANING --cleaning complete--> INSPECTING
INSPECTING --inspection passed--> READY
INSPECTING --inspection failed--> VACANT_DIRTY (RE_CLEANING 작업 생성)
```

투숙 중 청소:
```text
OCCUPIED --stayover cleaning starts--> STAYOVER_CLEANING
STAYOVER_CLEANING --stayover cleaning complete--> OCCUPIED
```

체크인:
```text
READY --check-in--> OCCUPIED
```

`OUT_OF_SERVICE`는 별도 availability 축이다. 해당 객실은 체크인 대상에서 제외한다. 복구 시 기존 operationalStatus를 보존하되, 실제 청소/검사 완료 여부를 재확인한다. `OUT_OF_SERVICE` 해제만으로 READY를 설정하지 않는다.

### 6.2 작업 상태

정상:
```text
PENDING -> ASSIGNED -> IN_PROGRESS -> COMPLETED
```

차단:
```text
PENDING    -> BLOCKED -> PENDING
ASSIGNED   -> BLOCKED -> ASSIGNED
IN_PROGRESS-> BLOCKED -> IN_PROGRESS
```

취소:
- `PENDING`, `ASSIGNED`, `BLOCKED`는 취소 가능
- `IN_PROGRESS` 취소는 관리자 권한 및 사유 필요
- `COMPLETED`는 취소/재시작 불가

복귀 정책:
- 첫 활성 blocker 등록 시 현재 상태를 `statusBeforeBlocked`에 저장하고 BLOCKED로 전환
- blocker가 모두 해제되면 저장된 상태로 복귀
- 복귀 시 ASSIGNED/IN_PROGRESS의 활성 assignment가 유효한지 재검증
- 담당자가 비활성/근무 종료 등으로 유효하지 않으면 `PENDING`으로 복귀하고 assignment를 종료한다는 정책을 권장한다. 이 정책은 승인 필요(`PROPOSAL`).

### 6.3 검사 흐름

1. 청소 작업 완료
2. INSPECTION task 생성 (멱등성 보장)
3. 검사자 배정
4. 검사자가 `POST /tasks/{id}/start` 실행
5. 객실 operationalStatus를 `INSPECTING`으로 전환
6. 검사 결과 제출
7. PASSED: 검사 task COMPLETED, 객실 READY (다른 blocker/시설 제한이 없어야 함)
8. FAILED: 검사 task COMPLETED, `InspectionRecord(FAILED)` 생성, RE_CLEANING task 생성, 객실 VACANT_DIRTY
9. 재청소 완료 시 새 검사 task 생성

### 6.4 불변식

1. PENDING task에는 활성 assignment가 없다.
2. ASSIGNED/IN_PROGRESS task에는 유효한 활성 assignment가 하나 있다.
3. BLOCKED task에는 활성 blocker가 하나 이상 있고 `statusBeforeBlocked`가 존재한다.
4. BLOCKED 외 상태에서 `statusBeforeBlocked`는 null이다.
5. 활성 blocker가 남아 있으면 task를 시작/완료할 수 없다.
6. blocker가 모두 해제되기 전 BLOCKED를 해제할 수 없다.
7. 검사 task는 선행 청소 작업 완료 후에만 생성/시작할 수 있다.
8. 검사 통과 전 객실을 READY로 만들 수 없다.
9. 시설 복구만으로 READY가 되지 않는다.
10. checkout 명령은 예약·객실·청소 task·event를 원자적으로 변경한다.
11. stayover cleaning 완료 후 객실은 OCCUPIED로 복귀한다.
12. 동일 checkout/inspection 명령 재전송으로 task가 중복 생성되지 않는다.
13. 작업 배정은 역할·활성 상태·근무시간을 검증한다.
14. 객실 운영 상태와 availability 상태를 합치지 않는다.

## 7. 체크인·체크아웃 명령과 트랜잭션

### 권장 API

```http
POST /api/v1/reservations/{reservationId}/check-in
POST /api/v1/reservations/{reservationId}/check-out
```

요청 body는 `occurredAt`(선택), `notes`(선택) 등으로 제한한다. 실제 체크인/체크아웃 시각은 서버 기준을 기본으로 하고, 관리자가 소급 입력할 수 있다면 권한·감사 이력을 별도 처리한다.

### Checkout transaction

하나의 DB transaction에서:
1. 예약이 CHECKED_IN인지 확인
2. 해당 객실과 예약의 연결 및 상태 확인
3. 예약을 CHECKED_OUT으로 변경하고 actualCheckOutAt 기록
4. 객실을 VACANT_DIRTY로 변경
5. CHECKOUT_CLEANING task를 멱등하게 생성
6. OperationalEvent 기록
7. commit

하나라도 실패하면 전부 rollback한다. 외부 시스템 호출은 transaction 안에서 수행하지 않는다.

### Check-in transaction

1. 예약이 CONFIRMED인지 확인
2. 객실이 READY인지 확인
3. availability가 AVAILABLE인지 확인
4. 동일 객실에 충돌하는 활성 투숙 예약이 없는지 확인
5. 예약 CHECKED_IN, actualCheckInAt 기록
6. 객실 OCCUPIED 전환
7. 이벤트 기록
8. commit

**멱등성:** 동일 명령 재전송 시 같은 결과를 반환하거나 이미 처리된 상태임을 안정적인 오류 코드로 응답한다. Idempotency-Key와 도메인 unique constraint를 함께 고려한다.

## 8. API 초안

모든 API는 property scope, 권한, 입력값, 상태 전이를 서버에서 검증한다.

### 조회
```text
GET /api/v1/properties/{propertyId}/dashboard
GET /api/v1/properties/{propertyId}/rooms
GET /api/v1/properties/{propertyId}/rooms/{roomId}
GET /api/v1/properties/{propertyId}/rooms/{roomId}/timeline
GET /api/v1/properties/{propertyId}/reservations
GET /api/v1/properties/{propertyId}/tasks
GET /api/v1/properties/{propertyId}/readiness
```

### 예약 명령
```text
POST /api/v1/reservations/{reservationId}/check-in
POST /api/v1/reservations/{reservationId}/check-out
```

### 작업 명령
```text
POST /api/v1/properties/{propertyId}/tasks
POST /api/v1/tasks/{taskId}/assignments
DELETE /api/v1/tasks/{taskId}/assignments/{assignmentId}
POST /api/v1/tasks/{taskId}/start
POST /api/v1/tasks/{taskId}/blockers
POST /api/v1/tasks/{taskId}/blockers/{blockerId}/resolve
POST /api/v1/tasks/{taskId}/complete
POST /api/v1/tasks/{taskId}/cancel
```

### 검사 명령
```text
POST /api/v1/tasks/{inspectionTaskId}/inspections
```
위 endpoint는 검사 결과 제출이다. 검사 시작은 공통 `/start` endpoint를 사용한다. 결과 제출 body에 `result`, `failureReason`을 둔다.

### 시뮬레이션
```text
POST /api/v1/properties/{propertyId}/simulations
GET  /api/v1/properties/{propertyId}/simulations/{simulationId}
```

초기 온라인 실행은 Kotlin에서 담당한다는 권장안이다. API는 OpenAPI로 요청/응답/오류를 확정한 뒤 구현한다.

## 9. Readiness 계산

### 입력
- 체크인 예정 시각
- 작업 시작 예상 시각
- 남은 청소 시간
- blocker의 남은 영향 시간/해제 예상 시각
- 검사 예상 시간
- 운영 buffer
- 데이터 완전성

### 계산 기본형
```text
expectedReadyAt =
  estimatedTaskStartAt
  + remainingCleaningDuration
  + effectiveBlockerWait
  + inspectionDuration

riskDeadline = checkInAt - operationalBuffer
riskMinutes = expectedReadyAt - riskDeadline
```

### blocker 처리 정책

- 이미 경과한 시간은 다시 더하지 않는다.
- 동시에 활성인 blocker 시간을 단순 합산하지 않는다.
- 동일 작업을 막는 병렬 blocker는 가장 늦은 유효 해제 시각을 기준으로 기다림을 산정하는 보수적 초기 정책을 제안한다.
- blocker가 작업과 병렬로 해결될 수 있는지 모델이 모르면 `UNKNOWN` 처리한다.
- 해제 예상 시각이 없는 blocker를 임의의 분으로 환산하지 않는다.
- 필수 입력이 누락되면 위험 점수를 억지로 계산하지 않고 UNKNOWN을 반환한다.

결과에는 `expectedReadyAt`, `riskDeadline`, `riskMinutes`, 등급, 사유별 영향, 데이터 완전성, `calculatedAt`을 포함한다. 계산 로직은 서버에 둔다.

## 10. DB 무결성·동시성 권장사항

- 한 task에 활성 assignment는 최대 1개: partial unique index
- BLOCKED task에는 활성 blocker 존재: 애플리케이션 transaction 검증 + 필요 시 constraint/trigger 검토
- `statusBeforeBlocked`는 BLOCKED일 때만 값 보유
- checkout task는 source reservation + task type 조합으로 중복 방지 unique key 검토
- 검사 결과는 inspection task당 최대 1개
- 예약 시간 중복은 exclusion constraint 또는 잠금 기반 유스케이스 검증
- Room/Reservation/Task aggregate에 version 적용
- 상태 변경 명령은 대상 aggregate를 잠그거나 optimistic locking 충돌을 409로 반환
- 재시도 가능한 명령은 idempotency key 저장

## 11. 화면 구조

### 대시보드
- 객실 수 및 상태별 수
- 당일 체크인/체크아웃
- 준비 위험/UNKNOWN 객실
- 작업 대기·진행·차단
- 담당자별 배정량
- 위험 원인 및 예상 준비 시각

### 객실 상세
- 객실·예약 상태
- 현재 작업·담당자
- blocker 및 해제 예상
- 검사 결과
- 이벤트 타임라인
- readiness 계산 근거

### 작업 관리
- 날짜/상태/유형/담당자/층 필터
- 배정·재배정·시작·차단·해제·완료
- 상태별 허용 액션

### 시뮬레이션
- seed와 파라미터
- 기준/대안 정책
- 정시 준비율·평균 지연·작업자 부하 등 KPI
- 합성 데이터 및 가정값 명시

## 12. 보안 및 운영

- property 범위 데이터 접근 검증
- 실제 고객 개인정보 미사용
- 비밀값은 환경변수/secret manager
- 로그에 인증 토큰·개인정보 미기록
- CORS origin 환경별 제한
- Flyway migration으로 스키마 변경
- 외부 연동은 MVP 제외

## 13. 테스트 기준

### 단위
- 객실/예약/작업 상태 전이
- PENDING에서 blocker 등록/해제
- 복수 blocker의 마지막 해제 시에만 복귀
- 이전 상태 복원 및 assignment 유효성
- inspection start/result/pass/fail/re-clean 흐름
- stayover cleaning 후 OCCUPIED 복귀
- readiness UNKNOWN 및 blocker 중첩

### 통합
- checkout transaction rollback/commit
- checkout 재시도 시 task 중복 방지
- check-in READY/availability 검증
- 이벤트 원자성
- 동시 checkout 및 예약 충돌
- assignment partial unique index
- optimistic lock

### E2E
1. 체크인된 객실을 체크아웃한다.
2. 예약 CHECKED_OUT, 객실 VACANT_DIRTY, 청소 task 1건 생성 확인
3. 작업을 PENDING 상태에서 blocker 등록해 BLOCKED 전환
4. blocker 해제 후 PENDING 복귀
5. 작업 배정·시작
6. 작업 중 blocker 추가·해제 후 IN_PROGRESS 복귀
7. 청소 완료 후 검사 task 생성
8. 검사 시작 시 객실 INSPECTING
9. 검사 실패 → RE_CLEANING 생성
10. 재청소 완료 → 새 검사 → PASSED → READY
11. 체크인 명령으로 OCCUPIED
12. 투숙 중 청소 시작 → STAYOVER_CLEANING → 완료 → OCCUPIED
13. 동일 명령 재전송으로 중복 생성되지 않음
14. readiness 및 시뮬레이션 결과 확인

## 14. 권장 구현 순서

1. Phase 0 저장소 조사 및 설계 승인
2. scaffold 및 로컬 DB
3. Property/Room/Reservation + check-in/out 명령
4. Staff/StaffShift
5. Task/Assignment/Blocker 및 차단 복귀 모델
6. OperationalEvent 및 멱등성
7. Inspection task + InspectionRecord + re-clean
8. stayover cleaning 상태 흐름
9. Readiness calculator
10. REST/OpenAPI
11. 합성 데이터·오프라인 Python 실험
12. 운영 대시보드·객실 상세·작업 관리
13. Kotlin 온라인 시뮬레이션(승인 시)
14. E2E·배포

## 15. 미결정 및 승인 필요

아래는 권장안이며 사용자의 명시적 승인 전에는 `PROPOSAL`이다.

- PENDING blocker 해제 시 PENDING 복귀
- 복귀 시 담당자 무효라면 PENDING 복귀 및 assignment 종료
- FAILED inspection 후 객실 VACANT_DIRTY 설정
- inspection task당 InspectionRecord 1개
- stayover cleaning 시 RoomOperationalStatus에 STAYOVER_CLEANING 사용
- 온라인 시뮬레이션 Kotlin 실행, Python은 오프라인 분석
- 예약 중복 방지의 DB exclusion constraint 적용 여부
- 관리자 권한 및 IN_PROGRESS 취소 정책의 상세 범위
