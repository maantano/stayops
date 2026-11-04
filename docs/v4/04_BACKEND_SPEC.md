# 04. Backend Specification

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
- checkout: Reservation + Room + checkout Task + Event
- cleaning complete: Task + Room + inspection Task + Event
- inspection result: InspectionRecord + inspection Task + Room + optional RE_CLEANING Task + Event
- stayover start/complete/cancel: Task + Room + Event
- blocker resolve: Blocker + Task + optional assignment end + Event

외부 API/이메일/메시지 publish를 DB transaction 안에서 호출하지 않는다. 외부 전달 요구가 생기면 transactional outbox를 ADR로 추가한다.

## 3. Command 처리 파이프라인
1. 인증 principal 및 property scope
2. Idempotency-Key 확인
3. aggregate load + lock/version
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
- Room/Reservation/Task의 version 또는 pessimistic lock을 선택한다.
- 예약 시간 겹침은 DB exclusion constraint가 우선 후보. 적용 불가하면 property+room 단위 lock으로 serialize하고 race test 작성.
- 같은 객실의 incompatible active workflow task는 aggregate lock + invariant validation 및 가능한 unique index로 보호.
- task start/cancel/complete 동시 실행에서 단 하나만 성공.
- event aggregateVersion은 단조 증가.
- optimistic conflict는 409 `VERSION_CONFLICT`.

## 5. 권한 모델 초안
| Action | FRONT_DESK | HOUSEKEEPER | INSPECTOR | MANAGER |
|---|---:|---:|---:|---:|
| dashboard/room/task read | O | 본인/필요 범위 | 검사 범위 | O |
| check-in/out | O | X | X | O |
| stayover create | O | X | X | O |
| assignment create/reassign | 제한 | X | X | O |
| assigned task start/complete | X | 본인 | 본인 검사 | O |
| blocker create/resolve | X | 본인 task | 본인 검사 | O |
| inspection result | X | X | O | O |
| in-progress cancel | X | X | X | O |
| availability OOS/restore | X | X | X | O |
| simulation | 읽기 제한 | X | X | O |

이는 demo RBAC 제안이다. 실제 role mapping은 인증 구현 전 결정한다. API에서 매 요청 서버 검증.

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
필드 예:
`type, title, status, detail, instance, code, correlationId, fieldErrors`.
도메인 오류:
`INVALID_STATE, ROOM_NOT_AVAILABLE, ACTIVE_BLOCKER_EXISTS, ASSIGNMENT_INVALID, SHIFT_CONFLICT, SCHEDULE_CONFLICT, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, PROPERTY_SCOPE_VIOLATION`.

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
- metrics: command latency/error, conflict count, task transition count, readiness unknown count.
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
- concurrent command tests
- authorization/property scope tests
- OpenAPI schema validation
- event consistency tests
- DST/timezone tests
- migration fresh-install test
