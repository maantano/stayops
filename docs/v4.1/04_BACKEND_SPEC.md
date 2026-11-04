# 04. Backend Specification (v4.1)

> v4 대비 변경: lock 정책 확정, 트랜잭션 경계 추가(checkout×stayover, rework), RBAC 갱신(started 기준 취소, MANAGER 대리 실행 금지, rework), 오류 코드는 02 §7.1 참조로 일원화, 상태 모순 처리.

## 1. 모듈러 모놀리스
초기 패키지:
- property
- room
- reservation
- staff
- housekeeping
- readiness
- simulation
- operations(event/audit)
- identity/authorization

모듈 간 의존은 application service/API contract로 제한한다. JPA entity를 모듈 외부에 직접 노출하지 않는다.

## 2. 트랜잭션 경계
하나의 command에서 관련 aggregate와 event를 일관되게 변경한다.
- checkout: Reservation + Room + (unstarted stayover Task 자동 취소 + 그 assignment·blocker 종결) + checkout Task + Event
- cleaning complete: Task + assignment 종료 + Room + inspection Task + Event
- inspection result: InspectionRecord + inspection Task + Room + optional RE_CLEANING Task + Event
- stayover start/complete/cancel: Task + Room + Event
- blocker resolve: Blocker + Task + optional assignment end + Event
- task cancel: Task + assignment 종료 + 미해결 blocker 종결 + Room 복귀 + Event
- rework create: Task (+ 선택적 assignment) + Event (Room 불변)

외부 API/이메일/메시지 publish를 DB transaction 안에서 호출하지 않는다. 외부 전달 요구가 생기면 transactional outbox를 ADR로 추가한다.

## 3. Command 처리 파이프라인
1. 인증 principal 및 property scope (scope 밖이면 404)
2. Idempotency-Key 확인 (누락 시 400, 저장된 결과 있으면 즉시 반환)
3. aggregate load + lock (02 §10.1 순서: Room → Reservation → Task → Assignment/Blocker) / version 확인
4. authorization
5. domain precondition
6. state transition
7. DB constraints 검증
8. OperationalEvent append
9. idempotency result 저장
10. commit
11. DTO 반환

오류 시 전체 rollback. 동일 idempotency key + 동일 request hash는 기존 응답을 반환. 같은 키에 다른 request hash는 409.

## 4. 동시성
- `[v4.1 확정]` room workflow command는 Room 행 pessimistic lock(`SELECT … FOR UPDATE`)으로 객실 단위 직렬화. 잠금 순서는 Room → Reservation → Task → Assignment/Blocker로 고정해 교착을 방지한다.
- 그 외 aggregate는 optimistic `version`. 클라이언트가 보낸 stale version은 409 `VERSION_CONFLICT`.
- 예약 시간 겹침은 DB exclusion constraint가 우선 후보. 적용 불가하면 property+room 단위 lock으로 serialize하고 race test 작성.
- 객실당 active ROOM_WORKFLOW task 1개: Room lock + 서비스 검증 + partial unique index(최종 방어선). index 위반은 409 `ACTIVE_WORKFLOW_EXISTS`로 변환.
- task start/cancel/complete 동시 실행에서 결과는 직렬 실행과 같다. 같은 task의 start 재요청은 상태 기반 멱등(200 `alreadyStarted`).
- `STATE_INCONSISTENT`: 저장 상태 모순을 발견하면 보정하지 않고 409 반환, ERROR 로그(correlationId, aggregate id들, 기대 상태, 실제 상태), metric `state_inconsistent_total` 증가.
- event aggregateVersion은 단조 증가.
- optimistic conflict는 409 `VERSION_CONFLICT`.

## 5. 권한 모델 초안
| Action | FRONT_DESK | HOUSEKEEPER | INSPECTOR | MANAGER |
|---|---:|---:|---:|---:|
| dashboard/room/task read | O | 본인/필요 범위 | 검사 범위 | O |
| check-in/out | O | X | X | O |
| stayover create | O | X | X | O |
| assignment create/reassign | X | X | X | O |
| assigned task start/complete | X | 본인 | 본인 검사 | X (대리 실행 금지) |
| blocker create/resolve | X | 본인 task | 본인 검사 | O |
| inspection result | X | X | 본인 검사 | X |
| unstarted task cancel | stayover만 | X | X | O |
| **started task cancel** (`actualStartedAt` 존재, status 무관) | X | X | X | O + reason |
| rework create | X | X | X | O |
| availability OOS/restore | X | X | X | O |
| simulation | 읽기 제한 | X | X | O |

demo RBAC다. 실제 인증 방식은 OPEN. API에서 매 요청 서버 검증.
- MANAGER는 task 담당자가 될 수 없고 대리 start/complete도 할 수 없다. 단, 이미 IN_PROGRESS인 task에 대한 MANAGER의 start 재요청은 200 `alreadyStarted`로 응답한다(02 §4 판정 3번).
- 취소 권한을 status가 아니라 started 여부로 판단하는 이유: v4에서는 진행 중 task에 blocker를 하나 붙이면 MANAGER 없이 취소할 수 있었다.

## 6. Validation rules
- property/room/reservation/task/staff 동일 property scope.
- Room과 Reservation의 관계 일치.
- Room 상태별 허용 task start.
- task status별 허용 command.
- blocker 활성 상태 검증.
- staff active/role/skill/shift 검증.
- 예약 interval 및 시간대 검증.
- duration, scheduledAt, dueAt validation.
- inspection result type/required reason.
- cancel reason required.
- OOS reason required.
- rework reason required, kind별 생성 조건(02 §4 Rework create).
- INSPECTION type의 `/complete` 거부.
- staff role별 배정 가능 task type (02 §2 Staff).
- check-in READY + AVAILABLE + CONFIRMED 및 reservation time eligibility.

## 7. 이벤트/감사
- event는 business audit 목적이며 domain table 대체 아님.
- actor, correlationId, aggregateVersion, event type, timestamp, payload schema version 포함.
- 민감 개인정보/게스트 연락처는 event payload에 저장하지 않는다.
- 상태 변경과 event는 같은 transaction.
- event payload는 schema version을 가진다.
- 이벤트를 삭제/수정하지 않고 보정 이벤트를 추가한다.

## 8. 시간과 시간대
- DB: TIMESTAMPTZ/Instant.
- Property: IANA timezone.
- API는 RFC 3339 offset timestamp 수용, 저장은 Instant.
- 화면/업무일 집계는 property timezone.
- DST gap/overlap 테스트.
- date-only 예약 입력을 허용한다면 property timezone에서 Instant 구간으로 명확히 변환.
- scheduledAt이 과거인 task 허용 여부는 command별 정책으로 검증.

## 9. 오류 계약
RFC 9457 Problem Details 기반.
필드: `type, title, status, detail, instance, code, correlationId, fieldErrors` + code별 확장 필드(예: `STAYOVER_IN_PROGRESS`의 `blockingTask`, `resolutionOptions` / `ACTIVE_WORKFLOW_EXISTS`의 `existingTaskId`, `existingTaskType`, `existingTaskStatus` / `INVALID_STATE`의 `currentStatus`).

**오류 코드 목록은 `02_DOMAIN_AND_API.md` §7.1이 유일한 기준이다.** 이 문서에서 별도 목록을 유지하지 않는다. `PROPERTY_SCOPE_VIOLATION`은 사용하지 않고 scope 위반은 404 + 서버 로그로 처리한다.

오류 detail에 내부 stack trace, SQL, secret 노출 금지.

## 10. Pagination / filtering
- cursor pagination.
- 안정적 정렬 tie-breaker 포함 (예: scheduledAt + id).
- 최대 page size 제한.
- property scope 필수.
- filter/sort 허용 목록화.
- dashboard aggregate와 상세 목록 간 데이터 시각 차이를 UI에서 고려.

## 11. Observability
- structured log: correlationId, actorId(내부 식별자), propertyId, aggregateId, command, result code, duration.
- 개인정보·토큰·게스트 이름/연락처 미기록.
- metrics: command latency/error, conflict count, task transition count, readiness unknown count, state_inconsistent_total, idempotency replay count.
- health/readiness endpoint 분리.
- 운영 데이터가 아닌 demo mode임을 environment/config에 명시.

## 12. Security / privacy
- MVP는 합성 게스트/예약만 사용.
- 최소 권한 및 property scope.
- secret env/secret manager.
- CORS allowlist.
- CSRF 전략은 인증 방식에 맞춰 설정.
- rate limit은 공개 command/API 배포 시 적용.
- 입력 sanitization, output encoding, ORM parameter binding.
- dependency scanning 및 secret scanning.
- audit log에 민감정보 저장 금지.

## 13. DB migration
- Flyway versioned migration 추가만 허용.
- 적용된 migration 수정 금지.
- unique/partial/exclusion constraints는 실제 PostgreSQL에서 검증.
- migration forward test와 fresh DB test.
- 운영 데이터 destructive migration 금지.
- rollback은 자동 down migration을 가정하지 말고 forward-fix 절차 문서화.

## 14. Backend 완료 기준
- domain transition unit tests
- repository/constraint integration tests
- transaction rollback tests
- idempotency replay tests
- concurrent command tests (Room lock 직렬화, partial unique index 방어)
- check constraint tests (statusBeforeBlocked, cancellationCode, originReason, resolutionType)
- authorization/property scope tests
- OpenAPI schema validation
- event consistency tests
- DST/timezone tests
- migration fresh-install test
