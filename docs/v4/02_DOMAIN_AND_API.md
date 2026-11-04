# 02. Domain & API Contract

> 구현 기준 문서. 이 문서의 규칙과 상태표를 BE/FE/QA가 동일하게 따른다. 실제 운영 정책을 대변하지 않는다.

## 1. 핵심 설계 원칙
1. Room 상태와 Task 상태를 분리한다.
2. Room operational status와 availability status를 분리한다.
3. 모든 상태 변경은 command로 수행한다.
4. 상태 변경, event, 필요한 task 생성은 한 DB transaction으로 묶는다.
5. 외부 부수 효과는 transaction 안에서 직접 호출하지 않는다.
6. 입력 누락·모호함은 임의 추정하지 않고 UNKNOWN/validation error로 표현한다.
7. 모든 시간은 Instant로 저장하고 Property timezone으로 표시한다.

## 2. Aggregate와 엔티티

### Property
`id, name, timezone(IANA), defaultCheckInTime, defaultCheckOutTime, operationalBufferMinutes, createdAt, updatedAt, version`

### Room
`id, propertyId, roomNumber, roomType, floor, operationalStatus, availabilityStatus, availabilityReason(nullable), availabilityChangedAt, version`

OperationalStatus:
- `VACANT_DIRTY`
- `CLEANING`
- `INSPECTING`
- `READY`
- `OCCUPIED`
- `STAYOVER_CLEANING`

AvailabilityStatus:
- `AVAILABLE`
- `OUT_OF_SERVICE`

두 상태는 독립이다. READY + OUT_OF_SERVICE 가능. Check-in은 READY AND AVAILABLE 모두 필요.

### Reservation
`id, propertyId, roomId, status, scheduledCheckInAt, scheduledCheckOutAt, actualCheckInAt, actualCheckOutAt, guestCount, version`
Status: `CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, NO_SHOW`.

예약은 시간 구간 `[checkIn, checkOut)`으로 취급한다. 시간 중복 검증은 property timezone 변환 후 Instant 구간으로 수행한다. 실제 저장 제약은 exclusion constraint 또는 transaction lock 중 선택하고 통합 테스트로 증명한다.

### Staff / Shift
Staff: `id, propertyId, displayName, role, active, skills, version`
Role: `HOUSEKEEPER, INSPECTOR, MANAGER`.
Shift: `id, staffId, startsAt, endsAt, breakMinutes, version`.
MVP에서 휴게시간을 capacity에서 차감할 경우 계산 규칙을 명시한다. 스케줄이 없으면 담당자 가용성을 추정하지 않고 `UNKNOWN` 또는 명시적 demo schedule을 사용한다.

### HousekeepingTask
`id, propertyId, roomId, reservationId(nullable), sourceTaskId(nullable), type, status, scheduledAt, dueAt(nullable), estimatedDurationMinutes, actualStartedAt, actualCompletedAt, cancellationReason(nullable), createdBy, version`
Types: `CHECKOUT_CLEANING, STAYOVER_CLEANING, RE_CLEANING, INSPECTION`.
Statuses: `PENDING, ASSIGNED, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED`.

### TaskAssignment
`id, taskId, staffId, assignedAt, unassignedAt(nullable), assignedBy, unassignedReason(nullable)`.
assignment 이력 append-only. task당 활성 assignment 최대 1개.

### TaskBlocker
`id, taskId, type, description, blockedAt, estimatedResolutionAt(nullable), resolvedAt(nullable), resolvedBy(nullable), resolutionNote(nullable)`.
Types: `DND, LINEN_SHORTAGE, SUPPLY_SHORTAGE, MAINTENANCE, STAFF_UNAVAILABLE, OTHER`.
복수 blocker를 지원한다.

### InspectionRecord
`id, taskId(unique), result, failureReason(nullable), checklistSnapshot(JSONB), inspectedBy, inspectedAt`.
Result: `PASSED, FAILED`. FAILED는 failureReason 필수. 검사 task 하나당 결과 한 건.

### OperationalEvent
`id, propertyId, aggregateType, aggregateId, eventType, aggregateVersion, actorType, actorId(nullable), correlationId, idempotencyKey(nullable), payload(JSONB), occurredAt`.
상태 변경 transaction에서 기록. 외부 전달이 필요해지는 시점에 outbox를 도입한다.

### SimulationRun
`id, propertyId, seed, generatorVersion, policyVersion, parameters(JSONB), inputSnapshot(JSONB or immutable reference), metrics(JSONB), status, startedAt, completedAt, createdAt`.
동일 seed뿐 아니라 동일 input snapshot, generator/policy version에서만 재현성을 주장한다.

## 3. 상태 전이

### 3.1 Room operational status
| 현재 | Command/조건 | 다음 |
|---|---|---|
| OCCUPIED | checkout 성공 | VACANT_DIRTY |
| VACANT_DIRTY | checkout/re-clean task start | CLEANING |
| CLEANING | cleaning task complete | VACANT_DIRTY |
| VACANT_DIRTY | inspection task start | INSPECTING |
| INSPECTING | inspection PASSED | READY |
| INSPECTING | inspection FAILED | VACANT_DIRTY |
| READY | check-in 성공 | OCCUPIED |
| OCCUPIED | stayover task start | STAYOVER_CLEANING |
| STAYOVER_CLEANING | stayover complete/cancel | OCCUPIED |

취소:
- 시작 전 task 취소: Room 상태 변경 없음.
- 시작 후 checkout/re-clean 취소: VACANT_DIRTY.
- 시작 후 inspection 취소: VACANT_DIRTY.
- 시작 후 stayover 취소: OCCUPIED.
- 완료된 task는 취소 불가.

### 3.2 Task lifecycle
```text
PENDING -> ASSIGNED -> IN_PROGRESS -> COMPLETED
PENDING/ASSIGNED/IN_PROGRESS -> BLOCKED
BLOCKED -> saved prior status, if still valid
PENDING/ASSIGNED/BLOCKED -> CANCELLED
IN_PROGRESS -> CANCELLED (MANAGER + reason)
```
- blocker 추가는 PENDING/ASSIGNED/IN_PROGRESS/BLOCKED에서 허용. BLOCKED 중 추가 blocker도 가능.
- BLOCKED는 활성 blocker가 1개 이상일 때만 허용.
- 마지막 blocker 해제 시 statusBeforeBlocked 복귀.
- 복귀 시 기존 assignment가 활성·역할 적합·근무 중인지 재검증. 실패하면 assignment 종료, PENDING.
- blocker가 남아 있으면 start/complete 불가.
- COMPLETED/CANCELLED는 terminal.
- blocker 해제는 blocker record의 resolvedAt을 설정하고 삭제하지 않는다.
- task cancel 시 미해결 blocker는 `TASK_CANCELLED` 사유로 종결한다.

### 3.3 Room × Task lifecycle matrix
| Task type | Start | Blocked 또는 담당자 무효 복귀 | Complete | Cancel after start |
|---|---|---|---|---|
| CHECKOUT_CLEANING | CLEANING | CLEANING 유지 | VACANT_DIRTY | VACANT_DIRTY |
| RE_CLEANING | CLEANING | CLEANING 유지 | VACANT_DIRTY | VACANT_DIRTY |
| INSPECTION | INSPECTING | INSPECTING 유지 | PASSED=READY / FAILED=VACANT_DIRTY | VACANT_DIRTY |
| STAYOVER_CLEANING | STAYOVER_CLEANING | STAYOVER_CLEANING 유지 | OCCUPIED | OCCUPIED |

**중요한 구현 규칙**
- Task 생성/배정은 Room workflow status를 바꾸지 않는다.
- Task start가 workflow status를 바꾼다.
- Task가 BLOCKED/PENDING으로 바뀌어도 이미 시작한 workflow status를 유지한다.
- Task 취소는 위 matrix의 복귀 상태로 바꾼다.
- 시작 전 취소는 Room 상태를 변경하지 않는다.
- task type별 활성 workflow 중복을 막는다.

### 3.4 Reservation lifecycle
| 현재 | 명령 | 조건 | 다음 |
|---|---|---|---|
| CONFIRMED | check-in | Room READY + AVAILABLE, 예약 유효, 시간/권한 검증 | CHECKED_IN |
| CHECKED_IN | check-out | 예약과 Room 점유 일치 | CHECKED_OUT |
| CONFIRMED | cancel | check-in 전, 정책 허용 | CANCELLED |
| CONFIRMED | mark-no-show | cutoff 정책 충족, 관리자 권한 | NO_SHOW |

CHECKED_IN 예약은 stayover 작업 중에도 CHECKED_IN 유지. check-in 이후 예약 취소는 MVP에서 금지한다. 퇴실은 check-out command만 사용.

## 4. 명령별 선행조건과 부수효과

### Check-out
- Reservation CHECKED_IN
- Room OCCUPIED
- 예약의 roomId와 command room 일치
- checkout command 한 번만 성공
- transaction: reservation CHECKED_OUT, room VACANT_DIRTY, checkout task PENDING, event 기록
- 동일 요청 재전송은 동일 결과 반환 또는 idempotent conflict. 중복 task 금지.

### Task create
- checkout task는 checkout command 내부에서만 생성.
- RE_CLEANING은 inspection FAILED 결과 command 내부에서 생성.
- INSPECTION은 checkout/re-clean cleaning 완료 후 생성.
- STAYOVER_CLEANING은 별도 수동 API.
- 임의 type task 생성 API는 노출하지 않는다.

### Task assign
- task PENDING 또는 ASSIGNED (재배정).
- staff active, property 동일, role/skill 적합, shift가 작업 시작 예정 구간과 양립.
- 활성 assignment가 있으면 먼저 종료.
- 재배정 이력 보존.
- 이미 IN_PROGRESS task의 재배정은 MVP에서 금지(중단 후 manager가 blocker/취소 흐름으로 처리). 필요 시 별도 결정.

### Task start
공통 `POST /tasks/{id}/start`.
- task ASSIGNED (MVP 기본) 및 활성 assignment 필요.
- blocker 없음.
- staff role/active/shift 유효.
- task type별 Room/reservation/availability precondition 충족.
- Room status와 task status를 같은 transaction에서 변경.
- 동일 start 재요청은 멱등 결과.
- PENDING direct start는 금지.

### Task complete
- task IN_PROGRESS, blocker 없음, 활성 assignment 유효.
- checkout/re-clean: Room VACANT_DIRTY, 후속 inspection task PENDING 생성.
- inspection: InspectionRecord 필수, PASSED/FAILED 결과에 따라 Room/후속 task 처리.
- stayover: Room OCCUPIED.
- actualCompletedAt 기록.
- transaction 내 event 기록.

### Inspection result
`POST /api/v1/tasks/{taskId}/inspection-result`
```json
{"result":"FAILED","failureReason":"욕실 바닥 물기 잔존","checklist":{"bathroom":false}}
```
- task type INSPECTION, status IN_PROGRESS.
- FAILED는 reason 필수.
- 검사 결과 제출과 task completion, room transition, RE_CLEANING 생성은 원자적.
- 결과 중복 제출 금지.

### Cancel
`POST /api/v1/tasks/{id}/cancel`
```json
{"reason":"현장 운영 계획 변경"}
```
- PENDING/ASSIGNED/BLOCKED는 권한 정책 충족 시 취소.
- IN_PROGRESS는 MANAGER만 가능, reason 필수.
- COMPLETED/CANCELLED는 거부.
- 활성 assignment 종료, 미해결 blocker 종결, event 기록.
- Room 복귀는 matrix 적용.

### Stayover create
`POST /api/v1/rooms/{roomId}/stayover-cleanings`
```json
{
  "scheduledAt":"2026-11-05T10:00:00+09:00",
  "estimatedDurationMinutes":25,
  "notes":"고객 요청에 따른 객실 정비"
}
```
- Room OCCUPIED, Reservation CHECKED_IN, AVAILABLE.
- 활성 stayover task 없음.
- 요청자 권한 및 property scope 확인.
- task PENDING 생성, Room OCCUPIED 유지.
- 자동 daily scheduler 제외.

## 5. Availability / maintenance
- OUT_OF_SERVICE는 객실의 판매/신규 투숙 가능 여부.
- 이미 투숙 중인 예약을 OOS 변경만으로 자동 퇴실/취소하지 않는다.
- OOS는 신규 check-in을 막는다.
- OOS 변경에는 사유, actor, 시각, event가 필요하다.
- OOS 해제는 availability만 AVAILABLE로 바꾼다. operationalStatus는 그대로.
- 실제 점검/청소가 필요한지는 별도 task를 생성한다.
- MVP maintenance workflow는 제외. MAINTENANCE blocker는 작업 중단 원인을 기록하는 용도이며 설비 수리 완료를 의미하지 않는다.
- OOS 상태 변경과 이미 시작된 작업의 관계는 관리자가 별도 확인한다. 자동으로 task를 완료/취소하지 않는다.

## 6. REST API surface

### Query
```http
GET /api/v1/properties/{propertyId}/dashboard
GET /api/v1/properties/{propertyId}/rooms
GET /api/v1/properties/{propertyId}/rooms/{roomId}
GET /api/v1/properties/{propertyId}/rooms/{roomId}/timeline
GET /api/v1/properties/{propertyId}/reservations
GET /api/v1/properties/{propertyId}/tasks
GET /api/v1/properties/{propertyId}/readiness
GET /api/v1/properties/{propertyId}/staff-workloads
GET /api/v1/properties/{propertyId}/simulations/{simulationId}
```
목록 API는 cursor pagination, 안정적 sort, filter, timezone 명시.

### Command
```http
POST /api/v1/reservations/{id}/check-in
POST /api/v1/reservations/{id}/check-out
POST /api/v1/reservations/{id}/cancel
POST /api/v1/reservations/{id}/mark-no-show

POST /api/v1/tasks/{id}/assignments
DELETE /api/v1/tasks/{id}/assignments/{assignmentId}
POST /api/v1/tasks/{id}/start
POST /api/v1/tasks/{id}/blockers
POST /api/v1/tasks/{id}/blockers/{blockerId}/resolve
POST /api/v1/tasks/{id}/complete
POST /api/v1/tasks/{id}/inspection-result
POST /api/v1/tasks/{id}/cancel

POST /api/v1/rooms/{roomId}/stayover-cleanings
POST /api/v1/rooms/{roomId}/availability
POST /api/v1/properties/{propertyId}/simulations
```
Availability command body는 `{status, reason}`이며 MANAGER 권한. 동일 상태 반복 요청은 idempotent.

## 7. API 공통 계약
- 모든 command에 `Idempotency-Key` 권장/필수. 키 scope: actor + endpoint + key. 동일 키/동일 payload는 기존 결과 반환. 동일 키/다른 payload는 409.
- 모든 command에 correlation ID를 생성/전파.
- 성공 응답은 resource id, resulting status, aggregate version, occurredAt, event/correlation id를 포함.
- 오류는 RFC 9457 Problem Details 기반.
- 공통 오류 코드: `VALIDATION_ERROR`, `NOT_FOUND`, `FORBIDDEN`, `INVALID_STATE`, `ROOM_NOT_AVAILABLE`, `ACTIVE_BLOCKER_EXISTS`, `ASSIGNMENT_INVALID`, `SCHEDULE_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `VERSION_CONFLICT`.
- stale version은 409. FE는 최신 리소스를 재조회하고 사용자가 재시도하도록 한다.
- propertyId는 path만 신뢰하지 않고 인증 principal의 접근 범위를 검증.
- command는 기본적으로 한 aggregate workflow를 변경. checkout/inspection처럼 여러 aggregate를 변경하는 경우 명시적으로 단일 transaction으로 처리.

## 8. Readiness calculation
`expectedReadyAt`는 예측 보장이 아니라 입력 데이터 기반 계산값.

기본 개념:
- `readyDeadline = scheduledCheckInAt - operationalBuffer`
- `expectedReadyAt = criticalPathFinishAt`
- `riskMinutes = expectedReadyAt - readyDeadline`

계산 입력:
- Room operational/availability status
- 남은 workflow task와 task dependency
- 남은 작업시간
- staff shift 및 현재 배정 workload
- 활성 blocker와 예상 해제시각
- inspection duration
- operational buffer
- 데이터 완전성

규칙:
1. 이미 완료된 구간을 남은 시간에 중복 합산하지 않는다.
2. blocker가 작업과 병렬인지 직렬인지 모델이 없으면 단순 합산하지 않고 UNKNOWN 또는 보수적 upper bound로 표현한다.
3. 예상 해제시각이 없는 blocker는 임의 분으로 치환하지 않는다.
4. 작업자/근무표 데이터가 없으면 인력 기반 시작시각을 계산했다고 표현하지 않는다.
5. READY가 아니면서 필수 task/dependency 정보가 없으면 UNKNOWN.
6. READY + OUT_OF_SERVICE는 준비 완료/투숙 불가를 동시에 표시.
7. 결과에는 입력 스냅샷, 계산 버전, 계산 시각, 근거별 시간 기여, 누락 필드를 반환한다.
8. 등급 경계값은 설정으로 관리하고 현장 표준처럼 표시하지 않는다.

## 9. Simulation contract
- baseline과 scenario는 동일 input snapshot, seed, generator version, policy version에서 비교.
- scenario는 변경 가능한 정책 파라미터만 바꾼다. 원본 데이터 mutate 금지.
- 결과에는 baseline/scenario parameter diff와 metric diff 포함.
- 실패한 simulation은 partial success로 노출하지 않는다.
- 같은 snapshot+seed+versions는 같은 결과여야 한다.
- 시뮬레이션 결과는 실제 운영 최적화/성과 보장이 아니다.

## 10. DB constraints / invariants
- FK 및 property consistency 검증.
- Room roomNumber unique per property.
- Reservation interval: checkIn < checkOut.
- 예약 시간 중복: PostgreSQL range exclusion constraint 또는 transaction lock. Phase 0 후 선택하되 동시성 통합 테스트 필수.
- Task active workflow uniqueness: 객실별 호환되지 않는 활성 workflow가 동시에 존재하지 않도록 partial unique constraint 또는 aggregate lock + invariant service.
- 활성 assignment 최대 1개/task.
- inspection record unique/task.
- checkout task unique/source reservation/type.
- active stayover task unique/room.
- BLOCKED iff active blocker count >= 1. DB constraint만으로 불가능하면 service transaction + consistency test.
- `statusBeforeBlocked`는 BLOCKED일 때 필수, 그 외 null.
- COMPLETED/CANCELLED task의 active assignment 없음.
- event aggregateVersion monotonic.
- 모든 시간 범위 유효성 및 property timezone 확인.

## 11. 아직 결정이 필요한 항목
- PostgreSQL exclusion constraint vs transaction lock.
- 실제 auth 방식(데모 로그인/외부 IdP).
- 작업자 동시 작업 가능 여부 및 travel/setup time 모델.
- stayover 검사 필요 조건.
- 시설 OOS 중 작업 진행/취소 정책의 상세.
- readiness blocker critical path 모델.
- no-show cutoff 정책.
- 객실 배정 변경/예약 room move 지원 여부.
- simulation의 비동기 처리 기준.

각 항목은 ADR로 결정하고 FE/BE/QA 계약을 동시에 갱신한다.
