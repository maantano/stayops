# 02. Domain & API Contract — v4.1

> 구현 기준 문서. 이 문서의 규칙과 상태표를 BE/FE/QA가 동일하게 따른다. 실제 운영 정책을 대변하지 않는다.
> v4 대비 변경점은 `08_V4.1_CHANGE_LOG.md`의 C1~C10, N1~N3을 따른다. 본문의 `[v4.1]` 표시는 v4에서 바뀐 부분이다.

## 1. 핵심 설계 원칙
1. Room 상태와 Task 상태를 분리한다.
2. Room operational status와 availability status를 분리한다.
3. 모든 상태 변경은 command로 수행한다.
4. 상태 변경, event, 필요한 task 생성은 한 DB transaction으로 묶는다.
5. 외부 부수 효과는 transaction 안에서 직접 호출하지 않는다.
6. 입력 누락·모호함은 임의 추정하지 않고 UNKNOWN/validation error로 표현한다.
7. 모든 시간은 Instant로 저장하고 Property timezone으로 표시한다.
8. `[v4.1]` 저장된 상태끼리 모순되면 임의로 보정하지 않고 `STATE_INCONSISTENT`로 거부하며 진단 로그를 남긴다.

## 2. Aggregate와 엔티티

### Property
`id, name, timezone(IANA), defaultCheckInTime, defaultCheckOutTime, operationalBufferMinutes, createdAt, updatedAt, version`

### Room
`id, propertyId, roomNumber, roomType, floor, operationalStatus, availabilityStatus, availabilityReason(nullable), availabilityChangedAt, createdAt, updatedAt, version`

OperationalStatus: `VACANT_DIRTY`, `CLEANING`, `INSPECTING`, `READY`, `OCCUPIED`, `STAYOVER_CLEANING`

AvailabilityStatus: `AVAILABLE`, `OUT_OF_SERVICE`

두 상태는 독립이다. READY + OUT_OF_SERVICE 가능. Check-in은 READY AND AVAILABLE 모두 필요.

`[v4.1]` 조회 응답에는 서버가 계산한 읽기 전용 `workflowStage`를 포함한다(§2.10). 저장 enum은 바꾸지 않는다.

### Reservation
`id, propertyId, roomId, status, scheduledCheckInAt, scheduledCheckOutAt, actualCheckInAt, actualCheckOutAt, guestCount, createdAt, updatedAt, version`
Status: `CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, NO_SHOW`.

예약은 시간 구간 `[checkIn, checkOut)`으로 취급한다. 시간 중복 검증은 Instant 구간으로 수행한다. 저장 제약은 exclusion constraint 또는 transaction lock 중 선택하고(OPEN, §11) 통합 테스트로 증명한다.

### Staff / Shift
Staff: `id, propertyId, displayName, role, active, skills, version`
`[v4.1]` Role: `FRONT_DESK, HOUSEKEEPER, INSPECTOR, MANAGER`.

배정 가능 역할 `[v4.1]`:
| Task type | 배정 가능 role |
|---|---|
| CHECKOUT_CLEANING, RE_CLEANING, STAYOVER_CLEANING | HOUSEKEEPER |
| INSPECTION | INSPECTOR |

MANAGER와 FRONT_DESK는 task 담당자가 될 수 없다(MVP). MANAGER는 관리 명령(배정·취소·재정비 생성 등)만 수행한다.

Shift: `id, staffId, startsAt, endsAt, breakMinutes, version`.
스케줄이 없으면 담당자 가용성을 추정하지 않고 `UNKNOWN` 또는 명시적 demo schedule을 사용한다.

### HousekeepingTask
`[v4.1]` 필드:
```text
id, propertyId, roomId, reservationId(nullable), sourceTaskId(nullable),
type, workflowGroup, origin, originReason(nullable),
status, statusBeforeBlocked(nullable),
scheduledAt, dueAt(nullable), estimatedDurationMinutes, durationSource,
actualStartedAt(nullable), actualCompletedAt(nullable),
cancellationCode(nullable), cancellationReason(nullable),
createdBy, createdAt, updatedAt, version
```

| 필드 | 값 / 규칙 |
|---|---|
| type | `CHECKOUT_CLEANING, STAYOVER_CLEANING, RE_CLEANING, INSPECTION` |
| status | `PENDING, ASSIGNED, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED` |
| workflowGroup | 현재 모든 type = `ROOM_WORKFLOW` (§2.9) |
| origin | `CHECKOUT_COMMAND, CLEANING_COMPLETED, INSPECTION_FAILED, STAYOVER_REQUEST, MANAGER_REWORK` |
| originReason | origin=MANAGER_REWORK이면 필수 |
| statusBeforeBlocked | BLOCKED일 때만 필수(`PENDING/ASSIGNED/IN_PROGRESS`), 그 외 null |
| durationSource | `USER_INPUT, CONFIG_DEFAULT` |
| cancellationCode | `MANUAL, CHECKOUT`. CANCELLED일 때 필수 |
| cancellationReason | 자유 텍스트. MANUAL이면 필수 |

`sourceTaskId` 의미 `[v4.1]`:
| task | sourceTaskId |
|---|---|
| INSPECTION (자동·수동 모두) | 이 검사가 확인하는 cleaning task |
| RE_CLEANING (origin=INSPECTION_FAILED) | 실패한 INSPECTION task |
| RE_CLEANING (origin=MANAGER_REWORK) | null |
| CHECKOUT_CLEANING, STAYOVER_CLEANING | null |

### TaskAssignment
`id, taskId, staffId, assignedAt, unassignedAt(nullable), assignedBy, unassignedReason(nullable), unassignedNote(nullable)`.
`[v4.1]` unassignedReason: `REASSIGNED, ASSIGNEE_INVALID_ON_RESUME, TASK_CANCELLED, TASK_COMPLETED`.
이력 append-only. task당 활성 assignment 최대 1개. task 완료 시 활성 assignment를 `TASK_COMPLETED`로 종료한다.

### TaskBlocker
`id, taskId, type, description, blockedAt, estimatedResolutionAt(nullable), resolvedAt(nullable), resolvedBy(nullable), resolutionType(nullable), resolutionNote(nullable)`.
Types: `DND, LINEN_SHORTAGE, SUPPLY_SHORTAGE, MAINTENANCE, STAFF_UNAVAILABLE, OTHER`.
`[v4.1]` resolutionType: `RESOLVED, TASK_CANCELLED`. resolvedAt과 함께 설정된다.
복수 blocker를 지원한다. 삭제하지 않는다.

### InspectionRecord
`id, taskId(unique), result, failureReason(nullable), checklistSnapshot(JSONB), inspectedBy, inspectedAt`.
Result: `PASSED, FAILED`. FAILED는 failureReason 필수. 검사 task 하나당 결과 한 건.

### OperationalEvent
`id, propertyId, aggregateType, aggregateId, eventType, aggregateVersion, actorType, actorId(nullable), correlationId, idempotencyKey(nullable), payloadSchemaVersion, payload(JSONB), occurredAt`.
상태 변경 transaction에서 기록. 외부 전달이 필요해지는 시점에 outbox를 도입한다(ADR).

### SimulationRun
`id, propertyId, seed, generatorVersion, policyVersion, parameters(JSONB), inputSnapshot(JSONB or immutable reference), metrics(JSONB), status, startedAt, completedAt, createdAt`.
동일 seed + 동일 input snapshot + 동일 generator/policy version에서만 재현성을 주장한다.

### 2.8 `[v4.1]` 공통 용어

| 용어 | 정의 |
|---|---|
| active task | status ∈ {PENDING, ASSIGNED, IN_PROGRESS, BLOCKED} |
| terminal task | status ∈ {COMPLETED, CANCELLED} |
| **started task** | `actualStartedAt IS NOT NULL`. 현재 status와 무관하다. |
| cleaning task | type ∈ {CHECKOUT_CLEANING, RE_CLEANING} |

**"시작 전/후"는 모든 규칙에서 `actualStartedAt`으로 판정한다**(cancel matrix, 취소 권한, 체크아웃 시 stayover 처리, 재시작). 한 번 시작한 task는 차단·담당자 무효로 PENDING/ASSIGNED가 되어도 started다.

### 2.9 `[v4.1]` 객실 workflow 충돌 규칙

| active ↓ / 새로 생성·시작 → | CHECKOUT_CLEANING | RE_CLEANING | INSPECTION | STAYOVER_CLEANING |
|---|---|---|---|---|
| CHECKOUT_CLEANING | ✕ | ✕ | ✕ | ✕ |
| RE_CLEANING | ✕ | ✕ | ✕ | ✕ |
| INSPECTION | ✕ | ✕ | ✕ | ✕ |
| STAYOVER_CLEANING | ✕ | ✕ | ✕ | ✕ |

- 현재 모든 type은 `workflowGroup=ROOM_WORKFLOW`이고, **객실당 active task는 최대 1개**(PENDING 포함)다.
- DB: `UNIQUE (room_id, workflow_group) WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS','BLOCKED')`.
- 병행 가능한 type을 추가할 때는 다른 group을 부여하고 이 표를 갱신한다.
- 위반 시 409 `ACTIVE_WORKFLOW_EXISTS`(기존 task id·type·status 포함).

### 2.10 `[v4.1]` 검사 대기 판정과 workflowStage

```text
isAwaitingInspection(room) =
  room.operationalStatus == VACANT_DIRTY
  AND C = 해당 객실의 가장 최근 started cleaning task 가 존재
  AND C.status == COMPLETED
  AND sourceTaskId = C 인 INSPECTION 중 InspectionRecord 가 있는 것이 없음
  AND 객실에 active INSPECTION 없음
```
- READY에 도달하는 유일한 경로가 inspection PASSED이므로 "C 이후 검사 결과 없음"은 "C 이후 재오염 없음"을 함축한다.
- 시작 전 취소된 cleaning task는 무시한다. 시작 후 취소된 cleaning이 가장 최근이면 검사 대기가 아니다(부분 청소를 완료로 간주하지 않음).
- 이 함수는 단일 도메인 서비스로 구현하고 재정비 생성(§4)과 workflowStage에서 공유한다. FE는 다시 계산하지 않는다.

`workflowStage` (읽기 전용, 서버 계산):
| stage | 판정 |
|---|---|
| NEEDS_CLEANING | VACANT_DIRTY이며 AWAITING_INSPECTION 조건 불충족 |
| CLEANING | operationalStatus = CLEANING |
| AWAITING_INSPECTION | VACANT_DIRTY이며 (`isAwaitingInspection` 또는 시작 전 active INSPECTION 존재) |
| INSPECTING | operationalStatus = INSPECTING |
| READY | operationalStatus = READY |
| OCCUPIED | operationalStatus = OCCUPIED |
| STAYOVER_CLEANING | operationalStatus = STAYOVER_CLEANING |

## 3. 상태 전이

### 3.1 Room operational status
| 현재 | Command/조건 | 다음 |
|---|---|---|
| OCCUPIED | checkout 성공 | VACANT_DIRTY |
| VACANT_DIRTY | checkout/re-clean task 최초 start | CLEANING |
| CLEANING | cleaning task complete | VACANT_DIRTY |
| VACANT_DIRTY | inspection task 최초 start (`isAwaitingInspection`) | INSPECTING |
| INSPECTING | inspection PASSED | READY |
| INSPECTING | inspection FAILED | VACANT_DIRTY |
| READY | check-in 성공 | OCCUPIED |
| OCCUPIED | stayover task 최초 start | STAYOVER_CLEANING |
| STAYOVER_CLEANING | stayover complete / started stayover cancel | OCCUPIED |

취소(`[v4.1]` 시작 전/후 = `actualStartedAt` 기준):
- 시작 전 task 취소: Room 상태 변경 없음.
- 시작 후 checkout/re-clean 취소: VACANT_DIRTY.
- 시작 후 inspection 취소: VACANT_DIRTY. 청소 완료 사실은 유지되므로 재검사만 생성할 수 있다(§4 재정비).
- 시작 후 stayover 취소: OCCUPIED.
- 완료된 task는 취소 불가.

`[v4.1]` READY 객실을 다시 VACANT_DIRTY로 돌리는 명령은 MVP에 없다(OPEN-1, v4.2 후보).

### 3.2 Task lifecycle
```text
PENDING -> ASSIGNED -> IN_PROGRESS -> COMPLETED
PENDING/ASSIGNED/IN_PROGRESS/BLOCKED -> BLOCKED (blocker 추가)
BLOCKED -> statusBeforeBlocked (마지막 blocker 해제, 담당자 유효 시)
BLOCKED -> PENDING (마지막 blocker 해제, 담당자 무효 시; assignment 종료)
active -> CANCELLED
```
- blocker 추가는 active 상태에서 허용. BLOCKED 중 추가 blocker도 가능(statusBeforeBlocked는 최초 진입 값 유지).
- BLOCKED는 활성 blocker가 1개 이상일 때만 허용.
- 복귀 시 기존 assignment가 활성·역할 적합·근무 중인지 재검증. 실패하면 assignment를 `ASSIGNEE_INVALID_ON_RESUME`으로 종료하고 PENDING. **actualStartedAt은 유지**한다.
- blocker가 남아 있으면 start/complete 불가.
- COMPLETED/CANCELLED는 terminal.
- task cancel 시 활성 assignment는 `TASK_CANCELLED`로 종료, 미해결 blocker는 `resolutionType=TASK_CANCELLED`로 종결.
- `[v4.1]` 취소 권한: **started task는 status와 무관하게 MANAGER + reason 필요**. unstarted task는 FRONT_DESK(stayover만)·MANAGER.

### 3.3 Room × Task lifecycle matrix
| Task type | 최초 start | 재시작 | Blocked 또는 담당자 무효 복귀 | Complete | Cancel after start |
|---|---|---|---|---|---|
| CHECKOUT_CLEANING | CLEANING | CLEANING 유지 | CLEANING 유지 | VACANT_DIRTY + INSPECTION 생성 | VACANT_DIRTY |
| RE_CLEANING | CLEANING | CLEANING 유지 | CLEANING 유지 | VACANT_DIRTY + INSPECTION 생성 | VACANT_DIRTY |
| INSPECTION | INSPECTING | INSPECTING 유지 | INSPECTING 유지 | PASSED=READY / FAILED=VACANT_DIRTY + RE_CLEANING | VACANT_DIRTY |
| STAYOVER_CLEANING | STAYOVER_CLEANING | 유지 | 유지 | OCCUPIED | OCCUPIED |

- Task 생성/배정은 Room 상태를 바꾸지 않는다.
- 최초 start만 Room 상태를 바꾼다.
- `[v4.1]` 담당자 무효로 PENDING이 된 started task는 Room이 진행 상태로 남는다(OPEN-5 결정: 유지). 대시보드는 "담당자 없음 · 진행 중단" 경고를 표시한다.

### 3.4 Reservation lifecycle
| 현재 | 명령 | 조건 | 다음 |
|---|---|---|---|
| CONFIRMED | check-in | Room READY + AVAILABLE, 예약 유효, 시간/권한 검증 | CHECKED_IN |
| CHECKED_IN | check-out | 예약과 Room 점유 일치, started stayover 없음 | CHECKED_OUT |
| CONFIRMED | cancel | check-in 전, 정책 허용 | CANCELLED |
| CONFIRMED | mark-no-show | cutoff 정책 충족, 관리자 권한 | NO_SHOW |

CHECKED_IN 예약은 stayover 작업 중에도 CHECKED_IN 유지. check-in 이후 예약 취소는 MVP에서 금지한다.

## 4. 명령별 선행조건과 부수효과

모든 room workflow command는 §10.1의 lock 순서를 따른다.

### Check-out `[v4.1]`
```text
lock Room → Reservation → active stayover task(있으면)
1. Reservation CHECKED_IN, roomId 일치                      실패 → INVALID_STATE
2. active stayover task S 조회
   S.actualStartedAt != null                               → 409 STAYOVER_IN_PROGRESS (전체 rollback)
3. Room OCCUPIED                                           실패 → STATE_INCONSISTENT
4. S 존재 시 자동 취소: status CANCELLED, cancellationCode CHECKOUT, statusBeforeBlocked null,
   활성 assignment 종료(TASK_CANCELLED), 미해결 blocker 종결(resolutionType TASK_CANCELLED, resolvedBy=actor)
5. Reservation CHECKED_OUT, actualCheckOutAt
6. Room VACANT_DIRTY
7. CHECKOUT_CLEANING 생성 (origin CHECKOUT_COMMAND, unique(reservationId, type))
8. Event: [TASK_CANCELLED], RESERVATION_CHECKED_OUT, ROOM_STATUS_CHANGED, HOUSEKEEPING_TASK_CREATED (같은 correlationId)
9. idempotency 결과 저장 → commit
```
어느 단계든 실패하면 전부 rollback한다.

`STAYOVER_IN_PROGRESS` 응답은 `blockingTask`(taskId, status, statusBeforeBlocked, actualStartedAt, assignee, activeBlockers)와 요청자 권한 기준 `resolutionOptions`(`RESOLVE_BLOCKERS_AND_COMPLETE`, `CANCEL_TASK_AS_MANAGER`)를 포함한다. 별도 정리 명령은 두지 않는다.

### Check-in
1. Reservation CONFIRMED
2. Room READY, AVAILABLE
3. 동일 객실 충돌 활성 투숙 예약 없음
4. Reservation CHECKED_IN, actualCheckInAt / Room OCCUPIED / event

`[P3 구현]` (`StayCommandService`)
- 권한: FRONT_DESK, MANAGER. 요청 본문 없음, 시각은 서버 기준.
- 판정 순서: 예약 상태(CONFIRMED) → 숙박 기간 종료 여부 → 다른 투숙 → availability(OOS면 `ROOM_NOT_AVAILABLE`) → operational(READY 아니면 `INVALID_STATE` + `roomOperationalStatus`).
- 예정 체크인 이전의 조기 체크인은 객실이 READY·AVAILABLE이면 허용한다 (`ASSUMPTION`). 예정 체크아웃 시각이 지난 예약은 `INVALID_STATE`. 상세 허용 시간대·late arrival 정책은 OPEN 유지.
- 이벤트: `RESERVATION_CHECKED_IN`, `ROOM_STATUS_CHANGED`(cause CHECK_IN).
- 응답(check-in/out 공통): `reservationId, reservationStatus, reservationVersion, roomId, roomOperationalStatus, roomVersion, checkoutCleaningTaskId?, autoCancelledStayoverTaskId?, occurredAt, correlationId`.

`[P3 구현]` Check-out 세부
- 권한: FRONT_DESK, MANAGER.
- 객실의 active task가 STAYOVER_CLEANING이 아니면(체크인 중인 객실에 turnover task) `STATE_INCONSISTENT`.
- checkout cleaning 예상 소요는 설정 기본값(`stayops.housekeeping.default-duration-minutes`, `ASSUMPTION`), `durationSource=CONFIG_DEFAULT`, `scheduledAt`=체크아웃 시각, `dueAt`은 비움(다음 도착 기준 계산은 readiness P7).
- 이벤트: `[TASK_CANCELLED]`, `RESERVATION_CHECKED_OUT`, `ROOM_STATUS_CHANGED`(cause CHECK_OUT), `HOUSEKEEPING_TASK_CREATED`.
- `resolutionOptions`: 모든 요청자에게 `RESOLVE_BLOCKERS_AND_COMPLETE`(담당자가 수행), MANAGER에게만 `CANCEL_TASK_AS_MANAGER` 추가.

### Task create
- checkout task: checkout command 내부에서만 생성.
- RE_CLEANING(origin INSPECTION_FAILED): inspection FAILED 결과 command 내부에서 생성.
- INSPECTION(origin CLEANING_COMPLETED): cleaning complete command 내부에서 생성.
- STAYOVER_CLEANING: stayover create API.
- `[v4.1]` 재정비(origin MANAGER_REWORK): rework API.
- 그 외 임의 type task 생성 API는 노출하지 않는다.

### Rework create `[v4.1]`
`POST /api/v1/rooms/{roomId}/rework-tasks` — MANAGER 전용, Idempotency-Key 필수.
```json
{
  "kind": "CLEANING",
  "reason": "체크아웃 청소가 시작 후 취소되어 재청소 필요",
  "estimatedDurationMinutes": 40,
  "scheduledAt": "2026-11-05T13:00:00+09:00",
  "assigneeStaffId": null
}
```
| 필드 | 필수 | 규칙 |
|---|---|---|
| kind | ✓ | `CLEANING` \| `INSPECTION` |
| reason | ✓ | trim 후 1~500자 |
| estimatedDurationMinutes | – | 생략 시 type별 설정 기본값, `durationSource=CONFIG_DEFAULT` |
| scheduledAt | – | 생략 시 command 시각. 과거 시각은 VALIDATION_ERROR |
| assigneeStaffId | – | 지정 시 같은 transaction에서 assign 규칙 검증 후 ASSIGNED로 생성. 실패 시 전체 거부 |

| kind | 조건 | 생성 | Room |
|---|---|---|---|
| CLEANING | VACANT_DIRTY, active task 없음, 해당 객실 CHECKED_IN 예약 없음(있으면 STATE_INCONSISTENT) | RE_CLEANING, origin MANAGER_REWORK, sourceTaskId null | 불변 |
| INSPECTION | `isAwaitingInspection` = true, active task 없음 | INSPECTION, origin MANAGER_REWORK, sourceTaskId = C | 불변 |

- 예약/체크아웃 이력은 확인하지 않는다(VACANT_DIRTY는 여러 경로로 발생).
- availability(OOS)는 조건에 넣지 않는다.
- 위반 시 409 `ACTIVE_WORKFLOW_EXISTS` 또는 `INVALID_STATE`(detail에 사유).

### Task assign
- task PENDING 또는 ASSIGNED(재배정).
- staff active, 같은 property, §2 배정 가능 역할, shift가 작업 예정 구간과 양립.
- 활성 assignment가 있으면 `REASSIGNED`로 종료 후 새 레코드.
- IN_PROGRESS task 재배정은 MVP에서 금지.

`[P4 구현]` Task assign / unassign 세부
- 권한: MANAGER. 배정 대상 task는 PENDING 또는 ASSIGNED (BLOCKED·IN_PROGRESS 불가).
- 담당자 검증(`AssigneePolicy`): 같은 property, 활성, task type별 역할(§2 Staff), **작업 예정 시각(`max(scheduledAt, now)`)을 포함하는 근무**. 실패 시 `ASSIGNMENT_INVALID`(reason: STAFF_NOT_FOUND / STAFF_INACTIVE / ROLE_MISMATCH) 또는 `SHIFT_CONFLICT`.
- 예상 종료가 근무 종료 이후면 거부하지 않고 응답 `warnings`에 `EXPECTED_END_AFTER_SHIFT_END`.
- 같은 담당자 재배정은 변경 없음(이벤트 없음).
- 배정 해제 `DELETE /tasks/{id}/assignments/{assignmentId}`: ASSIGNED → PENDING, 종료 사유 `MANUAL_UNASSIGN`(V3 migration).

### Task start `[v4.1]`
공통 `POST /api/v1/tasks/{id}/start`. 위에서부터 평가한다.

| # | 조건 | 결과 | 변경 |
|---|---|---|---|
| 1 | 같은 Idempotency-Key 재전송 | 저장된 최초 응답 | 없음 |
| 2 | task terminal | 409 `INVALID_STATE` (`currentStatus` 포함) | 없음 |
| 3 | IN_PROGRESS, 요청자 = 현재 담당자 또는 MANAGER | 200 `alreadyStarted: true` | 없음 (updatedAt·version·event 불변) |
| 4 | IN_PROGRESS, 다른 staff | 403 `FORBIDDEN` | 없음 |
| 5 | BLOCKED | 409 `ACTIVE_BLOCKER_EXISTS` + 활성 blocker | 없음 |
| 6 | PENDING | 409 `ASSIGNMENT_INVALID` | 없음 |
| 7 | ASSIGNED, 담당자 무효 | 409 `ASSIGNMENT_INVALID` / `SHIFT_CONFLICT` | 없음 |
| 8 | 객실에 다른 active task | 409 `ACTIVE_WORKFLOW_EXISTS` | 없음 |
| 9 | ASSIGNED, `actualStartedAt` null (최초) | Room 시작 전 상태 확인 후 전이 | IN_PROGRESS, actualStartedAt=now, Room 전이, `TASK_STARTED` |
| 10 | ASSIGNED, `actualStartedAt` not null (재시작) | Room이 이미 해당 진행 상태인지 확인 | IN_PROGRESS, actualStartedAt 보존, Room 불변, `TASK_RESUMED` |
| 11 | 9·10의 Room 조건 불일치 | 409 `STATE_INCONSISTENT` + ERROR 로그 | 없음 (보정 금지) |

| type | 최초 start Room 조건 → 전이 | 재시작 Room 조건 |
|---|---|---|
| CHECKOUT_CLEANING / RE_CLEANING | VACANT_DIRTY → CLEANING | CLEANING |
| INSPECTION | VACANT_DIRTY + `isAwaitingInspection` → INSPECTING | INSPECTING |
| STAYOVER_CLEANING | OCCUPIED + Reservation CHECKED_IN → STAYOVER_CLEANING | STAYOVER_CLEANING |

공통: 요청자는 담당자 본인(MANAGER는 3번 응답만 해당, 대리 start 불가 — OPEN-4 결정), 근무시간·역할 유효.

`[P4 구현]` start 세부
- 7번 "담당자 무효"는 요청자가 담당자가 아니면 `FORBIDDEN`, 담당자 본인이지만 지금 근무 중이 아니거나 역할·활성 조건을 못 맞추면 `ASSIGNMENT_INVALID`/`SHIFT_CONFLICT`.
- 검사 task 최초 시작 조건: 객실 VACANT_DIRTY이고, 검사의 `sourceTaskId`가 `isAwaitingInspection` 판정의 대상 cleaning(가장 최근 started cleaning, COMPLETED, 검사 결과 없음)과 같아야 한다 (`InspectionEligibility`).
- 이벤트: 최초 `TASK_STARTED` + `ROOM_STATUS_CHANGED`, 재시작 `TASK_RESUMED`.

`[P4 구현]` blocker 세부
- 권한: MANAGER 또는 현재 담당자(담당자 없는 task는 MANAGER만). FRONT_DESK 불가.
- OTHER는 description 필수, `estimatedResolutionAt`은 미래 시각.
- 마지막 blocker 해제 시 복귀 대상의 담당자 검증 시각: ASSIGNED 복귀는 `max(scheduledAt, now)`(배정과 같은 기준), IN_PROGRESS 복귀는 now. 무효면 assignment를 `ASSIGNEE_INVALID_ON_RESUME`으로 종료하고 PENDING, 응답 `warnings`에 `ASSIGNEE_INVALID_ON_RESUME`.
- 이벤트: `BLOCKER_ADDED`, `BLOCKER_RESOLVED`(remainingActiveBlockers, restoredStatus, assigneeInvalidReason).

### Task complete
- task IN_PROGRESS, blocker 없음, 활성 assignment 유효.
- `[v4.1]` **INSPECTION type은 `/complete` 불가 → 409 `INVALID_STATE`** (detail: inspection-result 사용).
- checkout/re-clean: Room VACANT_DIRTY, INSPECTION 생성(origin CLEANING_COMPLETED, sourceTaskId = 이 task).
- stayover: Room OCCUPIED.
- actualCompletedAt 기록, 활성 assignment `TASK_COMPLETED`로 종료, event 기록.

### Inspection result
`POST /api/v1/tasks/{taskId}/inspection-result`
```json
{"result":"FAILED","failureReason":"욕실 바닥 물기 잔존","checklist":{"bathroom":false}}
```
- task type INSPECTION, status IN_PROGRESS, 요청자 = 담당 INSPECTOR.
- FAILED는 reason 필수.
- 결과 기록 + task COMPLETED + Room 전이 + (FAILED 시) RE_CLEANING(origin INSPECTION_FAILED, sourceTaskId = 이 검사) 생성은 원자적.
- 결과 중복 제출 금지(InspectionRecord.taskId unique).

`[P5 구현]` Inspection result 세부
- 권한: 검사 task의 담당 INSPECTOR 본인만 (MANAGER 대리 제출 불가, 04 RBAC).
- 판정 순서: FAILED 사유 누락(400) → task type(INSPECTION 아니면 INVALID_STATE) → BLOCKED(ACTIVE_BLOCKER_EXISTS) → IN_PROGRESS 아님(INVALID_STATE) → 담당자(FORBIDDEN) → Room INSPECTING 아님(STATE_INCONSISTENT).
- PASSED는 availability와 무관하게 READY (READY+OOS 허용, §2 Room). `checklist`는 `Map<String, Boolean>`(최대 50항목)으로 받아 JSONB 스냅샷으로 저장.
- 응답에 `inspectionRecordId`, FAILED면 `createdTaskId`(RE_CLEANING). 이벤트: `INSPECTION_RECORDED`, `ROOM_STATUS_CHANGED`(cause INSPECTION_PASSED/FAILED), FAILED면 `HOUSEKEEPING_TASK_CREATED`.

### Cancel
`POST /api/v1/tasks/{id}/cancel`
```json
{"reason":"현장 운영 계획 변경"}
```
- `[v4.1]` unstarted task: MANAGER, 또는 FRONT_DESK(STAYOVER_CLEANING만). started task: MANAGER만, reason 필수.
- terminal은 거부.
- cancellationCode MANUAL, 활성 assignment 종료, 미해결 blocker 종결, event 기록.
- Room 복귀는 §3.1/§3.3 matrix 적용.

### Stayover create
`POST /api/v1/rooms/{roomId}/stayover-cleanings`
```json
{"scheduledAt":"2026-11-05T10:00:00+09:00","estimatedDurationMinutes":25,"notes":"고객 요청에 따른 객실 정비"}
```
- Room OCCUPIED, Reservation CHECKED_IN, AVAILABLE, active task 없음(§2.9).
- task PENDING(origin STAYOVER_REQUEST), Room OCCUPIED 유지.
- 자동 daily scheduler 제외.

`[P5 구현]` Stayover create 세부
- 권한: FRONT_DESK, MANAGER. 판정 순서: active task 존재(ACTIVE_WORKFLOW_EXISTS + existingTaskId) → OOS(ROOM_NOT_AVAILABLE) → OCCUPIED 아님(INVALID_STATE) → CHECKED_IN 예약 없음(STATE_INCONSISTENT).
- `scheduledAt` 생략 시 지금. 5분 넘게 과거이거나 예약의 예정 체크아웃 이후면 VALIDATION_ERROR (5분 허용치는 `ASSUMPTION`). `estimatedDurationMinutes` 1~480, 생략 시 설정 기본값. `notes`는 V4 migration의 `housekeeping_task.notes`에 저장(게스트 개인정보 금지).

`[P5 구현]` Rework create 세부
- 판정 순서: active task 존재 → kind별 조건(CLEANING: VACANT_DIRTY / INSPECTION: VACANT_DIRTY + `uninspectedCompletedCleaning` 존재, 아니면 INVALID_STATE `reason=NOT_AWAITING_INSPECTION`) → CHECKED_IN 예약 존재 시 STATE_INCONSISTENT → 담당자 검증(실패 시 task도 생성하지 않음).
- 응답 201. 담당자를 지정하면 `TASK_ASSIGNED` 이벤트도 함께 기록.

## 5. Availability / maintenance
- OUT_OF_SERVICE는 판매/신규 투숙 가능 여부다. 신규 check-in을 막는다.
- 이미 투숙 중인 예약을 OOS 변경만으로 자동 퇴실/취소하지 않는다.
- OOS 변경에는 사유, actor, 시각, event가 필요하다.
- OOS 해제는 availability만 AVAILABLE로 바꾼다. operationalStatus는 그대로.
- `[v4.1]` 점검/청소가 필요하면 관리자가 재정비 명령(§4)으로 task를 만든다. 대상은 VACANT_DIRTY 객실이며, READY 객실 재작업은 MVP 제외(OPEN-1).
- MAINTENANCE blocker는 작업 중단 원인 기록용이며 설비 수리 완료를 의미하지 않는다.
- OOS 변경이 진행 중 작업을 자동 완료/취소하지 않는다.
- `[P5 구현]` `POST /rooms/{roomId}/availability {status, reason}`: MANAGER, reason은 OOS·해제 모두 필수(감사 이력). 해제 시 `availabilityReason`을 비우고 해제 사유는 이벤트(`ROOM_AVAILABILITY_CHANGED`)에 남긴다. 같은 상태 요청은 `changed=false`, 이벤트 없음.

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
`[P5 구현]` `GET /properties/{propertyId}/rooms/{roomId}` 객실 상세: operational·availability 상태, `workflowStage`, active task 요약(상태, 차단 직전 상태, 최초 시작 시각, 담당자), `alerts`(`WORK_INTERRUPTED_NO_ASSIGNEE`: 시작된 작업에 담당자 없음, OPEN-5). MVP에서는 같은 property의 모든 역할이 조회 가능(`ASSUMPTION`, 역할별 범위는 P8). 나머지 조회 API는 P8.

목록 API는 cursor pagination, 안정적 sort(tie-breaker id), filter 허용 목록, timezone 명시.

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
POST /api/v1/rooms/{roomId}/rework-tasks          [v4.1]
POST /api/v1/rooms/{roomId}/availability
POST /api/v1/properties/{propertyId}/simulations
```
Availability body는 `{status, reason}`, MANAGER 권한, 동일 상태 반복은 idempotent.

## 7. API 공통 계약
- `[v4.1]` **모든 POST/DELETE command에 `Idempotency-Key` 필수**(OPEN-6 결정). 키 scope: actor + endpoint + key. 같은 키·같은 payload hash → 최초 응답 재반환(이후 상태가 바뀌었어도 최초 응답). 같은 키·다른 payload → 409 `IDEMPOTENCY_CONFLICT`. 누락 시 400 `VALIDATION_ERROR`.
- `[P2.5]` 성공한 command의 최초 응답만 저장한다. 실패한 command는 rollback되어 저장되지 않고, 같은 key로 재시도하면 최신 상태로 다시 평가된다. 재사용 응답에는 `Idempotency-Replayed: true` 헤더. endpoint는 route template(`POST /api/v1/tasks/{id}/start`)이고 path 변수는 payload hash에 포함한다. 같은 key 동시 요청은 advisory lock으로 직렬화(ADR-003 개정). 보관 기간·정리는 OPEN.
- `[P2.5]` 요청자: `X-Demo-Staff-Id` 데모 헤더(ADR-005, 보안 수단 아님). correlation ID: `X-Correlation-Id` 요청 헤더(UUID) 사용, 없으면 생성, 응답 헤더로 반환. Problem `type`은 `urn:stayops:problem:<code-kebab>`.
- 모든 command에 correlation ID 생성/전파.
- 성공 응답: resource id, resulting status, aggregate version, occurredAt, correlationId.
- 오류는 RFC 9457 Problem Details: `type, title, status, detail, instance, code, correlationId, fieldErrors`.
- stale version은 409 `VERSION_CONFLICT`. FE는 재조회 후 사용자가 재시도.
- propertyId는 인증 principal의 접근 범위로 검증.

### 7.1 `[v4.1]` 오류 코드 (단일 목록 — 04는 이 표를 참조한다)
| code | HTTP | 용도 |
|---|---|---|
| VALIDATION_ERROR | 400/422 | 입력 형식·범위, Idempotency-Key 누락 |
| UNAUTHENTICATED | 401 | 요청자 식별 불가 (데모 헤더 없음·무효·비활성 staff) `[P2.5]` |
| NOT_FOUND | 404 | 리소스 없음 **및 다른 property 리소스**(존재 노출 방지) |
| FORBIDDEN | 403 | 같은 property 내 역할 권한 부족 |
| INVALID_STATE | 409 | 상태 전이 불가 |
| ROOM_NOT_AVAILABLE | 409 | OOS 객실 check-in |
| ACTIVE_BLOCKER_EXISTS | 409 | blocker 남음 |
| ASSIGNMENT_INVALID | 409 | 담당자 없음·무효·역할 부적합 |
| SHIFT_CONFLICT | 409 | 근무시간 외 배정·시작 |
| SCHEDULE_CONFLICT | 409 | 예약 시간 겹침 |
| VERSION_CONFLICT | 409 | stale version |
| IDEMPOTENCY_CONFLICT | 409 | 같은 key, 다른 payload |
| ACTIVE_WORKFLOW_EXISTS | 409 | 객실에 active task 존재 |
| STAYOVER_IN_PROGRESS | 409 | started stayover 때문에 checkout 불가 |
| STATE_INCONSISTENT | 409 | 저장 상태 모순, 보정하지 않음 |
| REQUEST_NOT_SUPPORTED | 405/406/415 | 지원하지 않는 method·media type `[P2.5]` |
| INTERNAL_ERROR | 500 | 예상하지 못한 오류, 매핑되지 않은 DB 제약 위반(서비스 검증 누락 버그). 내부 정보 미노출 `[P2.5]` |

`PROPERTY_SCOPE_VIOLATION`은 사용하지 않는다(REJECTED). scope 위반은 서버 로그에만 기록한다.

잠금 대기 초과·deadlock·낙관적 잠금 충돌은 모두 `VERSION_CONFLICT`(ADR-003 개정). 동시 요청 경쟁으로 생기는 DB 제약 위반 매핑은 `ConstraintErrorMapping`(예: `reservation_no_overlap` → SCHEDULE_CONFLICT, `task_one_active_workflow_per_room` → ACTIVE_WORKFLOW_EXISTS).

## 8. Readiness calculation
`expectedReadyAt`는 예측 보장이 아니라 입력 데이터 기반 계산값이다.

- 대상 `[v4.1]`: 평가 시간창 안에 도착 예정(CONFIRMED) 예약이 있는 객실. 대상이 아닌 객실은 결과에 포함하지 않는다.
- `readyDeadline = scheduledCheckInAt - operationalBuffer`
- `expectedReadyAt = criticalPathFinishAt`
- `riskMinutes = expectedReadyAt - readyDeadline`

등급 `[v4.1]`:
| 등급 | 조건 |
|---|---|
| READY | operationalStatus READY (availability는 별도 필드로 함께 반환) |
| AT_RISK | riskMinutes > 0 |
| WARNING | -warningMarginMinutes < riskMinutes ≤ 0 |
| ON_TRACK | riskMinutes ≤ -warningMarginMinutes |
| UNKNOWN | 필수 입력 누락 |

`warningMarginMinutes`는 설정값이며 초기값은 `ASSUMPTION`이다(OPEN-7). 현장 표준처럼 표시하지 않는다.

계산 입력: Room 상태, 남은 workflow task와 dependency, 남은 작업시간(`durationSource` 포함), staff shift와 배정 workload, 활성 blocker와 예상 해제시각, inspection duration, buffer, 데이터 완전성.

규칙:
1. 이미 완료된 구간을 중복 합산하지 않는다.
2. blocker와 작업의 병렬/직렬 모델이 없으면 단순 합산하지 않고 UNKNOWN 또는 보수적 upper bound.
3. 예상 해제시각이 없는 blocker를 임의 분으로 치환하지 않는다.
4. 근무표가 없으면 인력 기반 시작시각을 계산했다고 표현하지 않는다.
5. READY가 아니면서 필수 task/dependency 정보가 없으면 UNKNOWN.
6. READY + OUT_OF_SERVICE는 준비 완료/투숙 불가를 동시에 표시.
7. 결과에 입력 스냅샷, 계산 버전, 계산 시각, 근거별 시간 기여, 누락 필드를 포함.
8. `[v4.1]` started task가 담당자 없이 PENDING이면 근거에 "담당자 없음 · 진행 중단"을 포함한다.

## 9. Simulation contract
- baseline과 scenario는 동일 input snapshot, seed, generator version, policy version에서 비교.
- scenario는 정책 파라미터만 바꾼다. 원본 데이터 mutate 금지.
- 결과에 parameter diff와 metric diff 포함.
- 실패한 simulation은 partial success로 노출하지 않는다.
- 결과는 실제 운영 최적화/성과 보장이 아니다.

## 10. DB constraints / invariants

### 10.1 `[v4.1]` Lock 정책
- room workflow command(check-in/out, stayover create, task start/complete/cancel, blocker add/resolve, inspection-result, rework create, availability): **pessimistic lock**, 순서 `Room → Reservation → HousekeepingTask → TaskAssignment/TaskBlocker`.
- 같은 객실의 command는 Room 행 잠금으로 직렬화된다. 두 번째 요청은 commit된 최신 상태로 선행조건을 다시 평가한다.
- 그 외 aggregate(Property, Staff, Shift 등): optimistic `version`.
- FE가 보낸 stale version은 어느 경우든 409 `VERSION_CONFLICT`.

### 10.2 제약
- FK 및 property consistency.
- Room roomNumber unique per property.
- Reservation checkIn < checkOut. 예약 시간 중복 방지(§11 OPEN).
- **객실당 active ROOM_WORKFLOW task 1개**: partial unique index (§2.9).
- 활성 assignment 최대 1개/task: partial unique index.
- InspectionRecord taskId unique.
- checkout task unique (reservationId) where type = CHECKOUT_CLEANING. STAYOVER_CLEANING은 reservationId 필수, 예약당 여러 번 가능. (P2 구체화, `docs/schema/SCHEMA.md`)
- 예약 시간 겹침: exclusion constraint (ADR-004). 객실당 CHECKED_IN 예약 1개. 직원 근무시간 겹침 금지.
- task type × origin × reservationId/sourceTaskId 일관성 CHECK (§2 sourceTaskId 표).
- `statusBeforeBlocked`: BLOCKED ⇔ not null (check constraint).
- `cancellationCode`: CANCELLED ⇔ not null (check constraint).
- `originReason`: origin=MANAGER_REWORK ⇒ not null (check constraint).
- `resolutionType`: resolvedAt not null ⇔ not null (check constraint).
- BLOCKED ⇔ 활성 blocker ≥ 1: service transaction + consistency test.
- terminal task에 활성 assignment 없음: service + consistency test.
- event aggregateVersion monotonic.

## 11. 아직 결정이 필요한 항목 (OPEN)
- ~~PostgreSQL exclusion constraint vs transaction lock (예약 중복)~~ → ADR-004에서 exclusion constraint로 결정.
- 실제 auth 방식(데모 로그인/외부 IdP).
- 작업자 동시 작업 가능 여부 및 travel/setup time.
- stayover 검사 필요 조건.
- 시설 OOS 중 작업 진행/취소 정책 상세.
- readiness blocker critical path 모델.
- no-show cutoff 정책.
- 객실 이동/room move 지원 여부.
- simulation 비동기 처리 기준.
- `[v4.1]` READY 객실 재작업("READY 해제") 명령 — v4.2 후보 (OPEN-1).
- `[v4.1]` warningMarginMinutes 초기값 (OPEN-7).

각 항목은 ADR로 결정하고 FE/BE/QA 계약을 동시에 갱신한다.
