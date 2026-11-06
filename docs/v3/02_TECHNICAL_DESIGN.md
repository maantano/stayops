# StayOps — 기술 설계 및 도메인 규칙

> 버전: v3 / 기준일: 2026-11-04  
> 목적: FE·BE·시뮬레이터가 동일한 업무 규칙을 구현하도록 하는 단일 기준 문서.  
> 주의: 이 문서의 규칙은 이 프로젝트의 도메인 결정이다. 실제 숙박 운영사의 내부 프로세스라고 주장하지 않는다.

## 1. 기술 스택과 아키텍처

| 영역 | 선택 |
|---|---|
| Frontend | Next.js App Router, React, TypeScript strict |
| UI/state/form | Tailwind, shadcn/ui, TanStack Query, React Hook Form, Zod |
| Backend | Kotlin, Spring Boot, Spring Data JPA |
| DB/migration | PostgreSQL, Flyway |
| Contract | REST, OpenAPI |
| Offline tools | Python, uv, pytest |
| Tests | JUnit 5, Testcontainers, Vitest, RTL, Playwright |
| Local/deploy | Docker Compose, Vercel, AWS ECS |
| IaC | Terraform |

초기 구조는 모듈러 모놀리스다. 모듈: property, room, reservation, staff, housekeeping, readiness, simulation, operations. 초기 마이크로서비스·메시지 브로커·캐시는 도입하지 않는다.

### Python 경계
- 온라인 API 및 사용자 요청 기반 시뮬레이션 실행/저장은 Kotlin 담당.
- Python은 합성 데이터 생성, 오프라인 대량 실험, KPI 분석.
- 초기 Python 상시 API 또는 Kotlin subprocess 호출 없음.
- 이 선택은 구현 단순화를 위한 `DECISION`; 향후 별도 ADR로 변경 가능.

## 2. 저장소 및 문서 경로

현재 기준 문서 경로는 `docs/v3/`다. 목표 구조:
```text
workspace/
├── docs/
│   ├── README.md
│   ├── v1/                 # 보관
│   ├── v2/                 # 보관
│   ├── v3/                 # 현재 기준
│   └── decisions/
├── apps/web/
├── apps/api/
├── tools/simulator/
├── infra/
└── docker-compose.yml
```
실제 작업 경로·Git 상태·기존 구조는 Phase 0에서 조사한다. Claude Code는 문서가 예상하는 구조를 실제라고 가정하지 않는다. v1/v2 삭제·이동·덮어쓰기 금지. 문서 파일 이동이나 Git 초기화는 조사 결과 보고 후 승인받는다.

## 3. 공통 데이터 규칙
- PK UUID.
- DB 시각 `TIMESTAMPTZ`, Kotlin `Instant`, 화면은 Property IANA timezone.
- JSONB는 event payload 및 simulation parameters/metrics 등 부가 데이터에 제한.
- 주요 aggregate optimistic locking `version`.
- 상태 변경과 OperationalEvent는 동일 transaction.
- 명령 API에 Idempotency-Key 적용 또는 도메인 unique constraint로 중복 방지.
- soft delete 기본 미사용. 취소는 상태로 표현.
- property scope/권한은 서버에서 검증.

## 4. 도메인 모델

### 4.1 Property
`id, name, timezone, defaultCheckInTime, defaultCheckOutTime, operationalBufferMinutes, createdAt, updatedAt, version`

### 4.2 Room
`id, propertyId, roomNumber, roomType, floor, operationalStatus, availabilityStatus, createdAt, updatedAt, version`

`RoomOperationalStatus`:
- `VACANT_DIRTY`: 퇴실 후 청소/검사 전 또는 재청소 필요
- `CLEANING`: checkout/re-clean 작업 진행 단계
- `INSPECTING`: 검사 작업이 실제 시작됨
- `READY`: 청소 및 필수 검사 완료
- `OCCUPIED`: 투숙 중, stayover 작업 외에는 점유
- `STAYOVER_CLEANING`: 투숙 중 청소 진행 단계

`RoomAvailabilityStatus`:
- `AVAILABLE`
- `OUT_OF_SERVICE`

**중요: 두 축은 독립이다.** OperationalStatus는 업무 단계, AvailabilityStatus는 판매/투숙 가능 여부다. `READY + OUT_OF_SERVICE`는 유효한 조합이며 체크인은 거부된다. OUT_OF_SERVICE 해제는 availability만 변경하며 operationalStatus를 READY로 승격하지 않는다.

### 4.3 Reservation
`id, propertyId, roomId, status, scheduledCheckInAt, scheduledCheckOutAt, actualCheckInAt, actualCheckOutAt, guestCount, createdAt, updatedAt, version`

상태: `CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, NO_SHOW`.

동일 객실 시간 중복 예약은 DB exclusion constraint 또는 transaction lock 기반 검증으로 방지한다. 선택은 Phase 0 이후 구현 전 확정하고 테스트한다.

### 4.4 Staff / StaffShift
Staff: `id, propertyId, name, role, skills, active, version`
Role: `HOUSEKEEPER, INSPECTOR, MANAGER`
StaffShift: `id, staffId, workDate, startsAt, endsAt`

배정/시작 시 역할·활성·근무시간 검증. 담당자가 비활성 또는 근무 종료 상태면 차단 해제 후 기존 배정으로 자동 복귀시키지 않는다.

### 4.5 HousekeepingTask
`id, propertyId, roomId, reservationId(nullable), sourceTaskId(nullable), type, status, estimatedDurationMinutes, actualStartedAt, actualCompletedAt, statusBeforeBlocked(nullable), createdAt, updatedAt, version`

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

### 4.6 TaskAssignment
`id, taskId, staffId, assignedAt, unassignedAt(nullable), assignedBy`
- assignment 이력은 append-only로 보존.
- 현재 활성 배정은 `unassignedAt IS NULL`.
- task당 활성 배정은 최대 1개.
- 재배정 시 이전 assignment를 종료하고 새 레코드 생성.

### 4.7 TaskBlocker
`id, taskId, type, description, blockedAt, estimatedResolutionAt(nullable), resolvedAt(nullable), resolvedBy(nullable)`
유형: `DND, LINEN_SHORTAGE, SUPPLY_SHORTAGE, MAINTENANCE, STAFF_UNAVAILABLE, OTHER`.
PENDING/ASSIGNED/IN_PROGRESS에 blocker 등록 가능. 활성 blocker가 하나 이상이면 BLOCKED. 마지막 blocker 해제 시 이전 상태 복귀.

### 4.8 InspectionRecord
`id, taskId(unique), result, failureReason(nullable), inspectedBy, inspectedAt`
result: `PASSED, FAILED`.
- INSPECTION task는 검사 업무의 배정·시작·완료 lifecycle.
- InspectionRecord는 검사 결과.
- 검사 task 하나에 결과 1건. 재검사는 새 inspection task.
- FAILED이면 failureReason 필수, 원 inspection task COMPLETED, RE_CLEANING task 생성.

### 4.9 OperationalEvent
`id, propertyId, entityType, entityId, eventType, actorType, actorId(nullable), correlationId, idempotencyKey(nullable), payload(JSONB), occurredAt`
상태 변경과 같은 transaction에서 기록. 도메인 테이블의 대체물이 아님.

### 4.10 SimulationRun
`id, propertyId, seed, policy, parameters(JSONB), metrics(JSONB), status, startedAt, completedAt, createdAt`
status: `QUEUED, RUNNING, COMPLETED, FAILED`. 초기 온라인 실행은 Kotlin. 장시간 실행이 확인되면 비동기 worker ADR 검토.

## 5. Room 상태 규칙

### 5.1 Checkout cleaning / inspection
```text
OCCUPIED --checkout--> VACANT_DIRTY
VACANT_DIRTY --checkout cleaning start--> CLEANING
CLEANING --cleaning complete--> VACANT_DIRTY
VACANT_DIRTY --inspection task start--> INSPECTING
INSPECTING --PASSED--> READY
INSPECTING --FAILED--> VACANT_DIRTY (+ RE_CLEANING task)
```
청소 완료는 검사 시작이 아니다. 검사 task 생성/배정만으로 INSPECTING이 되지 않는다. 검사자가 실제 `start` command를 실행할 때만 INSPECTING으로 전환한다.

### 5.2 Stayover cleaning
```text
OCCUPIED --stayover task start--> STAYOVER_CLEANING
STAYOVER_CLEANING --task complete--> OCCUPIED
```
예약은 계속 CHECKED_IN. stayover 완료 후 READY로 전환하지 않는다.

### 5.3 Check-in
```text
READY + AVAILABLE + CONFIRMED reservation --> OCCUPIED + CHECKED_IN
```
READY만으로 체크인 가능한 것이 아니다. availability, 예약 유효성, 시간 충돌, property scope도 검증한다.

### 5.4 Room × Task lifecycle 동기화 행렬

| Task type | 시작 시 Room | BLOCKED 중 Room | 완료 시 Room | 취소 시 Room |
|---|---|---|---|---|
| CHECKOUT_CLEANING | CLEANING | CLEANING 유지 | VACANT_DIRTY | VACANT_DIRTY |
| RE_CLEANING | CLEANING | CLEANING 유지 | VACANT_DIRTY | VACANT_DIRTY |
| INSPECTION | INSPECTING | INSPECTING 유지 | 결과에 따라 READY 또는 VACANT_DIRTY | VACANT_DIRTY |
| STAYOVER_CLEANING | STAYOVER_CLEANING | STAYOVER_CLEANING 유지 | OCCUPIED | OCCUPIED |

세부 규칙:
- Room은 객실의 업무 단계, Task는 개별 작업의 실행 상태를 표현한다.
- Task가 BLOCKED 또는 PENDING으로 돌아가더라도 진행 중 workflow의 Room 상태는 유지한다. 예: 청소 task BLOCKED → Room CLEANING 유지.
- 담당자 무효로 task가 PENDING으로 복귀해도 Room 상태를 임의로 VACANT_DIRTY로 되돌리지 않는다. 객실은 여전히 해당 workflow 단계에 있다.
- 취소 시 위 표의 복귀 상태를 적용한다. 취소는 진행 중 작업의 결과를 완료로 간주하지 않는다.
- PENDING/ASSIGNED 상태에서 아직 시작하지 않은 task 취소는 Room 상태를 변경하지 않는다. 다만 선행 command가 Room을 workflow 상태로 이미 전환한 경우에는 task type별 취소 복귀 상태를 적용한다.
- 검사 취소는 READY로 만들지 않는다.
- 취소된 task의 재개는 금지. 필요하면 새 task 생성.

## 6. Task lifecycle 규칙

### 6.1 정상/차단/취소
```text
PENDING -> ASSIGNED -> IN_PROGRESS -> COMPLETED
PENDING    -> BLOCKED -> PENDING
ASSIGNED   -> BLOCKED -> ASSIGNED (담당자 유효 시)
IN_PROGRESS-> BLOCKED -> IN_PROGRESS (담당자 유효 시)
PENDING/ASSIGNED/BLOCKED -> CANCELLED
IN_PROGRESS -> CANCELLED (MANAGER 권한 + 사유)
```
- BLOCKED 진입 시 `statusBeforeBlocked` 저장.
- BLOCKED 상태에서는 활성 blocker 1개 이상.
- blocker 일부만 해제되면 BLOCKED 유지.
- 마지막 blocker 해제 시 이전 상태 복원.
- 복귀 시 assignment 유효성 검증. 담당자가 비활성/근무 종료/역할 부적합이면 assignment 종료, task PENDING 복귀.
- COMPLETED/CANCELLED는 terminal state.
- blocker가 남아 있으면 start/complete 불가.

### 6.2 취소 정책
취소 API는 `reason` 필수. IN_PROGRESS 취소는 MANAGER 권한 필요.
- 아직 시작하지 않은 PENDING/ASSIGNED task 취소: Room은 기존 상태 유지. 단 task 생성 자체가 별도의 command에서 workflow 상태를 이미 바꿨다면 task type 복귀 규칙으로 정리.
- 실행 중 또는 BLOCKED task 취소: Room × Task 행렬의 취소 상태 적용.
- assignment 종료 시각 및 blocker 이력을 보존.
- 취소 event에 actor, reason, previous task status, resulting room status 기록.

## 7. Stayover cleaning 생성 규칙

MVP에서는 **운영자가 수동 생성**한다. 자동 일일 청소 스케줄러는 제외한다.

```http
POST /api/v1/rooms/{roomId}/stayover-cleanings
```
요청 예:
```json
{
  "scheduledAt": "2026-11-05T10:00:00+09:00",
  "estimatedDurationMinutes": 25,
  "notes": "고객 요청에 따른 객실 정비"
}
```
서버 검증:
1. Room operationalStatus가 OCCUPIED
2. 연결된 Reservation이 CHECKED_IN
3. 동일 객실에 활성 STAYOVER_CLEANING task가 없음
4. availability가 AVAILABLE (OUT_OF_SERVICE는 생성 거부)
5. 요청자 권한 확인
6. scheduledAt 및 duration 유효성 확인

생성 시 task는 PENDING이며 Room은 OCCUPIED 유지. 실제 task start 시 STAYOVER_CLEANING으로 전환한다. 투숙 예약은 CHECKED_IN 유지.

## 8. Reservation lifecycle

| Command | 선행 조건 | Reservation | Room | 부수 효과 |
|---|---|---|---|---|
| check-in | CONFIRMED, READY, AVAILABLE, 예약 충돌 없음 | CHECKED_IN | OCCUPIED | event |
| check-out | CHECKED_IN, room/reservation 일치 | CHECKED_OUT | VACANT_DIRTY | checkout cleaning task + event |

Check-in은 다음 조건을 모두 만족해야 한다.
- reservation CONFIRMED
- room READY
- room availability AVAILABLE
- 동일 객실 시간 중복 활성 투숙 예약 없음
- property scope/권한 충족

## 9. Command API

### Reservation
```http
POST /api/v1/reservations/{reservationId}/check-in
POST /api/v1/reservations/{reservationId}/check-out
```

### Task
```http
POST   /api/v1/properties/{propertyId}/tasks
POST   /api/v1/tasks/{taskId}/assignments
DELETE /api/v1/tasks/{taskId}/assignments/{assignmentId}
POST   /api/v1/tasks/{taskId}/start
POST   /api/v1/tasks/{taskId}/blockers
POST   /api/v1/tasks/{taskId}/blockers/{blockerId}/resolve
POST   /api/v1/tasks/{taskId}/complete
POST   /api/v1/tasks/{taskId}/cancel
```

### Stayover
```http
POST /api/v1/rooms/{roomId}/stayover-cleanings
```

### Inspection result
```http
POST /api/v1/tasks/{inspectionTaskId}/inspections
```
검사 시작은 공통 `/start`. 결과 제출은 `result`, FAILED인 경우 필수 `failureReason`.

### Room availability
별도 command로 OUT_OF_SERVICE 설정/해제를 추가할 경우 권한·사유·이벤트를 필수로 한다. 시설 복구 명령은 operationalStatus를 READY로 직접 바꾸지 않는다. MVP에 maintenance workflow가 없다면 API 노출 전 범위를 명시한다.

### Query
```http
GET /api/v1/properties/{propertyId}/dashboard
GET /api/v1/properties/{propertyId}/rooms
GET /api/v1/properties/{propertyId}/rooms/{roomId}
GET /api/v1/properties/{propertyId}/rooms/{roomId}/timeline
GET /api/v1/properties/{propertyId}/reservations
GET /api/v1/properties/{propertyId}/tasks
GET /api/v1/properties/{propertyId}/readiness
GET /api/v1/properties/{propertyId}/simulations/{simulationId}
POST /api/v1/properties/{propertyId}/simulations
```

모든 command는 권한·property scope·상태·입력값을 서버에서 검증한다. 상태 변경 및 event는 동일 transaction.

## 10. Readiness 계산

입력:
- check-in 예정 시각
- 작업 시작 예상 시각
- 남은 청소 시간
- blocker 영향/해제 예상
- 검사 시간
- operational buffer
- 데이터 완전성

```text
expectedReadyAt =
  estimatedTaskStartAt
  + remainingCleaningDuration
  + effectiveBlockerWait
  + inspectionDuration

riskDeadline = checkInAt - operationalBuffer
riskMinutes = expectedReadyAt - riskDeadline
```
- 경과 시간 중복 가산 금지.
- 병렬 blocker 시간 단순 합산 금지.
- blocker의 병렬/직렬 관계가 불명확하면 UNKNOWN 또는 보수적 계산.
- 해제 예상이 없는 blocker를 임의 분으로 치환하지 않는다.
- 필수 입력 누락 시 UNKNOWN.
- 결과에 expectedReadyAt, deadline, riskMinutes, 등급, 원인별 영향, dataCompleteness, calculatedAt 포함.

## 11. DB 제약·동시성

필수/권장:
- task당 활성 assignment 최대 1개: partial unique index
- inspection task당 InspectionRecord 최대 1개: unique
- checkout reservation + task type 중복 방지 unique key
- 활성 stayover task의 객실별 중복 방지 partial unique index
- BLOCKED 상태와 활성 blocker 관계는 transaction 검증 및 통합 테스트
- statusBeforeBlocked는 BLOCKED일 때만 non-null
- 예약 객실 시간 중복은 exclusion constraint 또는 lock 기반 검증
- Room/Reservation/Task version 또는 pessimistic lock
- 상태 변경 명령 중복 재전송은 idempotency key/도메인 키로 방지
- 동시 상태 변경은 409 Conflict 등 안정적인 오류 코드 반환

## 12. 화면 구성
- Dashboard: 객실 상태, 체크인/체크아웃, 위험/UNKNOWN, 작업 대기·진행·차단, 작업자 부하
- Room detail: 예약, 현재 작업, blocker, 검사, timeline, readiness 산정 근거
- Task board: 유형/상태/담당자/층 필터, 배정·재배정·시작·차단·해제·완료·취소
- Simulation: seed/parameter, 기준·대안 정책, KPI 차이, 합성 데이터 표시

## 13. 보안
- 실제 고객 개인정보 미사용
- property scope 및 role authorization
- 로그에 개인정보/토큰 미기록
- secret manager/env 사용
- CORS 환경별 제한
- Flyway migration 추가 방식
- 외부 PMS/API 연동 MVP 제외

## 14. 테스트 수용 기준

### Room state
- 청소 완료 직후 VACANT_DIRTY
- 검사 task 생성/배정만으로 INSPECTING 아님
- 검사 start 시 INSPECTING
- PASSED 시 READY 가능
- FAILED 시 VACANT_DIRTY + RE_CLEANING
- READY + OUT_OF_SERVICE check-in 거부
- OUT_OF_SERVICE 해제만으로 READY 전환 금지

### Task/Room matrix
- 청소 task BLOCKED 중 Room CLEANING 유지
- 담당자 무효로 PENDING 복귀해도 Room workflow state 유지
- 진행 중 checkout/re-clean 취소 → VACANT_DIRTY
- 진행 중 stayover 취소 → OCCUPIED
- 진행 중 inspection 취소 → VACANT_DIRTY, READY 금지
- stayover 완료 후 OCCUPIED, reservation CHECKED_IN 유지
- 시작 전 취소는 Room 상태 불변

### Reservation/commands
- checkout transaction 성공/rollback
- 중복 checkout task 방지
- check-in의 READY/AVAILABLE 검증
- 동시 예약/checkout 충돌

### Blocker
- PENDING/ASSIGNED/IN_PROGRESS 모두 등록 가능
- 복수 blocker 중 일부 해제 시 BLOCKED 유지
- 마지막 blocker 해제 시 이전 상태 복원
- 담당자 무효면 assignment 종료 및 PENDING

### API/E2E 핵심 시나리오
1. CHECKED_IN 예약 checkout → CHECKED_OUT + VACANT_DIRTY + checkout task 1개
2. PENDING task blocker 등록/해제 → PENDING
3. 배정·시작 → blocker 등록/해제 → IN_PROGRESS
4. 청소 완료 → VACANT_DIRTY, inspection task 생성
5. 검사 task 배정 상태에서 Room VACANT_DIRTY
6. 검사 start → INSPECTING
7. FAILED → RE_CLEANING 생성, Room VACANT_DIRTY
8. 재청소 완료 → 새 검사 → PASSED → READY
9. READY + OUT_OF_SERVICE check-in 실패
10. AVAILABLE 복구 후 check-in 성공
11. stayover task 수동 생성 → PENDING, Room OCCUPIED
12. start → STAYOVER_CLEANING → BLOCKED여도 상태 유지 → resolve → 완료 → OCCUPIED
13. 시작 전 취소와 진행 중 취소 각각 Room 상태 확인
14. 동일 명령 재전송 및 동시 요청의 무결성 확인

## 15. 구현 순서
1. Phase 0 저장소 조사 및 문서/제안 검토
2. scaffold 및 로컬 DB
3. Property/Room/Reservation + check-in/out
4. Staff/Shift
5. Task/Assignment/Blocker/Cancel
6. Event/Idempotency
7. Inspection/Re-clean
8. Stayover manual creation
9. Readiness
10. OpenAPI
11. Python synthetic data/offline analysis
12. Frontend operations UI
13. Kotlin online simulation
14. E2E/deploy

## 16. 미결정
- 예약 시간 중복 제약을 exclusion constraint로 구현할지 lock으로 구현할지
- IN_PROGRESS 취소 권한을 MANAGER로 제한하는 세부 RBAC
- 시설 blocker가 청소 시작 전/중일 때 task를 차단할지 객실만 OUT_OF_SERVICE로 둘지
- maintenance workflow/API를 MVP에 포함할지
- readiness 병렬 blocker 모델의 세부 수식
- stayover cleaning 완료 시 inspection을 요구할지 (MVP 기본은 검사 없음, 별도 결정)
