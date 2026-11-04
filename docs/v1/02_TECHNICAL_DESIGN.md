# StayOps — 기술 설계·도메인 모델 문서

> 목적: Claude Code가 일관된 도메인 모델, API, DB, 상태 전이, 계산 규칙으로 MVP를 구현하도록 하는 기술 기준 문서.
>
> 아키텍처 원칙: **모듈러 모놀리스 우선**, 도메인 규칙은 서버에서 보장, 상태 변경은 명시적 명령으로 처리한다.

## 1. 기술 스택

| 영역 | 선택 | 사용 목적 |
|---|---|---|
| Frontend | Next.js App Router, React, TypeScript strict | 운영 대시보드 및 업무 화면 |
| UI | Tailwind CSS, shadcn/ui | 일관된 컴포넌트 시스템 |
| Client state / server state | TanStack Query | 서버 데이터 캐싱·동기화 |
| Form | React Hook Form, Zod | 폼 상태 및 입력 검증 |
| Backend | Kotlin, Spring Boot | REST API 및 도메인 로직 |
| Persistence | Spring Data JPA | 영속성 접근 |
| Database | PostgreSQL | 관계형 데이터, JSONB 이벤트 payload |
| Migration | Flyway | 스키마 변경 이력 |
| API contract | REST, OpenAPI | 프론트·백엔드 계약 |
| Automation | Python, uv, pytest | 합성 데이터 생성·시뮬레이션·KPI 분석 |
| Backend test | JUnit 5, Testcontainers | 도메인/통합 테스트 |
| Frontend test | Vitest, React Testing Library | 컴포넌트 테스트 |
| E2E | Playwright | 핵심 운영 플로우 검증 |
| Local | Docker Compose | 로컬 DB 및 서비스 실행 |
| Deploy | Vercel, AWS ECS | 프론트 및 백엔드 배포 |
| IaC | Terraform | 배포 인프라 선언 |

채용 공고의 기술 요구와 맞닿는 기술을 사용하되, 모든 기술을 억지로 도입하지 않는다. GCP는 초기 MVP에서 제외한다. AWS·Vercel로 배포 경로를 단순화한다.

## 2. 아키텍처 결정

### 2.1 모듈러 모놀리스

초기에는 하나의 Spring Boot 애플리케이션과 하나의 PostgreSQL 데이터베이스를 사용한다. 내부 패키지/모듈 경계는 명확히 나누되 서비스 간 네트워크 호출은 만들지 않는다.

권장 도메인 모듈:
- property
- room
- reservation
- staff
- housekeeping
- readiness
- simulation
- operations (event/audit)

모듈 간 의존은 필요한 인터페이스를 통해서만 허용한다. 순환 의존을 금지한다.

### 2.2 마이크로서비스를 사용하지 않는 이유

- 초기 개발 규모에서 분산 트랜잭션·배포·관측 비용이 제품 가치보다 크다.
- 객실·예약·작업 상태 변경은 원자성이 중요하다.
- 모듈러 모놀리스로 경계를 검증한 뒤 독립 배포·확장 요구가 확인되면 분리한다.

### 2.3 Python의 역할

Python은 초기에는 별도 상시 API 서버가 아니다.
- 합성 데이터 생성
- 시나리오 실행
- KPI 집계 및 결과 비교
- 실험 결과를 JSON/CSV로 출력

핵심 운영 규칙과 readiness 계산의 기준 구현은 Kotlin 백엔드에 둔다. Python에서 동일 규칙을 재현해야 한다면 테스트 fixture와 계약을 공유하고 결과 일치 테스트를 둔다.

## 3. 프로젝트 구조 제안

```text
stayops/
  apps/
    web/                       # Next.js
    api/                       # Kotlin Spring Boot
  tools/
    simulator/                 # Python + uv
  infra/
    docker/
    terraform/
  docs/
    01_PRODUCT_DISCOVERY.md
    02_TECHNICAL_DESIGN.md
    03_CLAUDE_CODE_HARNESS.md
  docker-compose.yml
  README.md
```

백엔드 패키지 예시:

```text
com.stayops
  property/
    api/
    application/
    domain/
    infrastructure/
  room/
  reservation/
  staff/
  housekeeping/
  readiness/
  simulation/
  operations/
  shared/
```

각 모듈은 가능하면 `api`, `application`, `domain`, `infrastructure` 계층을 갖는다. 단순 CRUD까지 과도하게 계층화하지 말고, 도메인 규칙이 있는 곳에 경계를 적용한다.

## 4. 공통 데이터 규칙

- PK: UUID
- 시간: PostgreSQL `TIMESTAMPTZ`, Kotlin `Instant` 또는 `OffsetDateTime` 중 프로젝트 전체에서 일관된 타입 사용. 기본 권장 `Instant`.
- 호텔 현지 날짜/시간 계산은 Property의 IANA timezone을 기준으로 수행한다.
- DB 저장 시 UTC instant로 저장하고, 화면 표시·영업일 계산 시 property timezone을 사용한다.
- 금액 기능은 MVP 범위 밖이지만 추후 추가 시 부동소수점 금지.
- JSON payload는 이벤트/시뮬레이션의 부가 데이터에만 사용한다. 핵심 조회·관계 데이터는 정규화된 컬럼으로 둔다.
- 낙관적 잠금: 변경 충돌이 중요한 aggregate에 `version` 컬럼 적용.
- 생성·수정 시각: `created_at`, `updated_at`.
- soft delete는 기본 도입하지 않는다. 취소/비활성 등 도메인 상태로 표현한다.
- DB 제약조건과 애플리케이션 검증을 함께 적용한다.

## 5. 도메인 모델

### 5.1 Property

필드:
- id
- name
- timezone
- defaultCheckInTime
- defaultCheckOutTime
- operationalBufferMinutes
- createdAt
- updatedAt
- version

`timezone`은 `Asia/Seoul` 같은 IANA zone ID로 저장한다.

### 5.2 Room

필드:
- id
- propertyId
- roomNumber
- roomType
- floor
- operationalStatus
- availabilityStatus
- createdAt
- updatedAt
- version

`RoomOperationalStatus`:
- OCCUPIED
- VACANT_DIRTY
- CLEANING
- INSPECTING
- READY

`RoomAvailabilityStatus`:
- AVAILABLE
- OUT_OF_SERVICE

운영상 상태와 판매/사용 가능 상태를 분리한다. 시설 문제로 판매 불가인 객실이 반드시 청소 중인 것은 아니며, 청소가 끝났다고 판매 가능 상태가 자동으로 되는 것도 아니다.

### 5.3 Reservation

필드:
- id
- propertyId
- roomId
- status
- scheduledCheckInAt
- scheduledCheckOutAt
- actualCheckInAt
- actualCheckOutAt
- guestCount
- createdAt
- updatedAt
- version

`ReservationStatus`:
- CONFIRMED
- CHECKED_IN
- CHECKED_OUT
- CANCELLED
- NO_SHOW

예약 상태 변경과 객실 상태 변경은 하나의 애플리케이션 유스케이스에서 일관되게 처리한다. 예약의 CHECKED_OUT 전환 시 필요한 checkout cleaning task 생성은 중복 생성 방지 키/제약조건을 둔다.

### 5.4 Staff

필드:
- id
- propertyId
- name
- role
- skills
- active
- version

역할 예:
- HOUSEKEEPER
- INSPECTOR
- MANAGER

`skills`는 초기에는 enum 목록 또는 문자열 집합으로 단순화한다. 향후 스킬 기반 배정이 실제로 필요해지면 별도 테이블로 정규화한다.

### 5.5 StaffShift

필드:
- id
- staffId
- workDate
- startsAt
- endsAt

근무 일정은 Staff에 직접 저장하지 않고 별도 엔티티로 관리한다. 한 작업이 근무 종료 이후까지 예상되는 경우 배정 정책에서 경고 또는 배정 거부한다. MVP 기본 정책은 근무 시간 외 신규 작업 시작을 허용하지 않는 것이다.

### 5.6 HousekeepingTask

필드:
- id
- propertyId
- roomId
- reservationId (nullable)
- sourceTaskId (nullable)
- type
- status
- estimatedDurationMinutes
- actualStartedAt
- actualCompletedAt
- createdAt
- updatedAt
- version

`TaskType`:
- CHECKOUT_CLEANING
- STAYOVER_CLEANING
- RE_CLEANING
- INSPECTION

`TaskStatus`:
- PENDING
- ASSIGNED
- IN_PROGRESS
- BLOCKED
- COMPLETED
- CANCELLED

`sourceTaskId`는 재청소/후속 작업이 어떤 기존 작업 또는 검사에서 파생됐는지 연결하는 데 사용한다. 검사 실패 시 생성되는 RE_CLEANING 작업은 원래 청소 작업 또는 검사 작업을 추적할 수 있어야 한다. 구현 시 하나의 의미를 선택해 문서화하고 일관되게 유지한다.

### 5.7 TaskAssignment

필드:
- id
- taskId
- staffId
- assignedAt
- unassignedAt (nullable)
- assignedBy

배정 이력을 보존한다. 현재 배정은 `unassignedAt IS NULL`인 레코드로 표현한다. 작업당 활성 배정은 한 명으로 제한한다. PostgreSQL partial unique index 등을 검토한다.

재배정 시 기존 레코드를 삭제하거나 덮어쓰지 않고 `unassignedAt`을 기록한 뒤 새 배정 레코드를 생성한다.

### 5.8 TaskBlocker

필드:
- id
- taskId
- type
- description
- blockedAt
- estimatedResolutionAt (nullable)
- resolvedAt (nullable)
- resolvedBy (nullable)

`BlockerType`:
- DND
- LINEN_SHORTAGE
- SUPPLY_SHORTAGE
- MAINTENANCE
- STAFF_UNAVAILABLE
- OTHER

하나의 작업에 여러 차단 사유가 동시에 존재할 수 있다. 차단 해제는 개별 blocker 단위로 기록한다.

### 5.9 InspectionRecord

필드:
- id
- taskId
- result
- failureReason (nullable)
- inspectedBy
- inspectedAt

`result`:
- PASSED
- FAILED

FAILED이면 `failureReason`을 필수로 한다. 검사 실패 시 RE_CLEANING 작업을 생성한다. 검수 통과 전 객실을 READY로 바꾸지 않는다.

### 5.10 OperationalEvent

필드:
- id
- propertyId
- entityType
- entityId
- eventType
- actorType
- actorId (nullable)
- correlationId
- payload (JSONB)
- occurredAt

상태 변경과 이벤트 기록은 같은 DB 트랜잭션에서 처리한다. 이벤트는 운영 타임라인과 사후 분석을 위한 기록이다. 이벤트 테이블을 모든 도메인 데이터의 대체 저장소로 사용하지 않는다.

### 5.11 SimulationRun

필드:
- id
- propertyId
- seed
- policy
- parameters
- metrics
- status
- startedAt
- completedAt
- createdAt

`parameters`, `metrics`는 JSONB로 저장 가능하다. seed와 입력 파라미터를 보존해 동일 시뮬레이션을 재현할 수 있어야 한다.

## 6. 상태 전이와 불변식

### 6.1 객실 정상 흐름

```text
READY
  -> OCCUPIED
  -> VACANT_DIRTY
  -> CLEANING
  -> INSPECTING
  -> READY
```

상태 전이는 예약 체크인/체크아웃, 청소 시작/완료, 검사 시작/통과 등의 명시적 유스케이스에서만 수행한다.

### 6.2 검사 실패

검사 실패 시 객실은 READY가 될 수 없다. 기존 검사 작업은 완료 처리하되 FAILED 결과를 남기고 RE_CLEANING 작업을 생성한다. 재청소 후 다시 검사하는 흐름을 지원한다.

### 6.3 작업 상태

정상 흐름:
```text
PENDING -> ASSIGNED -> IN_PROGRESS -> COMPLETED
```

예외 흐름:
```text
ASSIGNED -> BLOCKED
IN_PROGRESS -> BLOCKED
BLOCKED -> IN_PROGRESS
BLOCKED -> ASSIGNED
```

취소는 허용된 상태에서만 `CANCELLED`로 전환한다. 완료된 작업은 재시작할 수 없다.

### 6.4 불변식

1. PENDING 작업에는 활성 담당자가 없어야 한다.
2. ASSIGNED 및 IN_PROGRESS 작업에는 유효한 활성 담당자가 있어야 한다.
3. BLOCKED 작업에는 활성 차단 사유가 하나 이상 있어야 한다.
4. 활성 차단이 남아 있으면 작업을 재개하거나 완료할 수 없다.
5. COMPLETED 작업은 다시 시작할 수 없다.
6. 검사 작업은 선행 청소 완료 이후에만 시작할 수 있다.
7. 검사 통과 전에는 객실을 READY로 변경할 수 없다.
8. 시설 수리 완료는 객실 준비 완료와 동일하지 않다.
9. 신규 작업 시작/배정은 작업자 근무 시간과 역할을 검증한다.
10. 상태 변경은 Controller에서 직접 수행하지 않고 Application Service의 명령으로 수행한다.
11. 중복 요청으로 checkout cleaning이나 inspection task가 중복 생성되지 않아야 한다.
12. 상태 변경과 OperationalEvent 기록은 원자적으로 수행한다.

## 7. Readiness Calculator

### 7.1 입력

- `checkInAt`
- `estimatedTaskStartAt`
- `remainingCleaningMinutes`
- `expectedRemainingBlockerMinutes`
- `inspectionMinutes`
- `operationalBufferMinutes`
- `dataCompleteness`

### 7.2 계산

```text
expectedReadyAt =
  estimatedTaskStartAt
  + remainingCleaningMinutes
  + expectedRemainingBlockerMinutes
  + inspectionMinutes

riskDeadline =
  checkInAt - operationalBufferMinutes

riskMinutes =
  expectedReadyAt - riskDeadline
```

해석:
- `riskMinutes <= 0`: buffer를 포함한 준비 마감 이전 완료 예상
- `riskMinutes > 0`: buffer를 포함한 준비 마감 이후 완료 예상
- 위험 등급 경계는 설정값으로 분리하고 테스트한다. 임의의 상수를 여러 곳에 중복하지 않는다.

### 7.3 계산 주의사항

- 이미 경과한 차단 시간을 다시 더하지 않는다.
- 여러 blocker가 겹쳐 있는 경우 시간을 무조건 합산하지 않는다.
- 병렬/중첩 차단을 어떻게 계산하는지 명시적인 정책을 둔다. 초기 MVP는 보수적으로 가장 늦은 유효 해제 시각을 기준으로 남은 대기 시간을 계산하되, 실제 작업과 차단이 동시에 진행되는지 불명확하면 UNKNOWN으로 처리한다.
- 차단 해제 예상 시각이 없으면 임의의 시간을 생성하지 않는다.
- 필수 데이터가 부족하면 위험 등급을 추정하지 않고 UNKNOWN을 반환한다.
- `estimatedTaskStartAt`이 근무시간·작업자 가용성으로부터 계산되지 않았다면 그 출처를 표시한다.
- 모든 계산 결과에 입력 근거와 `calculatedAt`을 포함한다.

### 7.4 결과 모델 권장

```json
{
  "roomId": "uuid",
  "status": "AT_RISK",
  "riskMinutes": 18,
  "expectedReadyAt": "2026-11-04T06:30:00Z",
  "riskDeadline": "2026-11-04T06:12:00Z",
  "reasons": [
    {
      "type": "LINEN_SHORTAGE",
      "label": "린넨 공급 대기",
      "impactMinutes": 12,
      "sourceId": "uuid"
    }
  ],
  "dataCompleteness": "COMPLETE",
  "calculatedAt": "2026-11-04T05:50:00Z"
}
```

위 JSON은 응답 구조 예시이며 실제 날짜·수치는 예시다.

## 8. API 설계 원칙

- REST 명사형 리소스 경로를 사용한다.
- 상태 변경을 일반 PATCH로 무분별하게 열지 않는다. 명령형 endpoint 또는 명시적 application command를 사용한다.
- 모든 요청에서 property 범위를 검증한다.
- DTO와 JPA Entity를 분리한다.
- 오류 응답 형식을 통일한다.
- 페이지네이션과 정렬을 목록 API에 적용한다.
- 시간은 ISO-8601로 주고받는다.
- 동시성 충돌은 409 Conflict로 표현한다.
- 잘못된 상태 전이는 409 또는 명확한 도메인 오류 코드로 반환한다.

초기 endpoint 후보:

```text
GET    /api/v1/properties/{propertyId}/dashboard
GET    /api/v1/properties/{propertyId}/rooms
GET    /api/v1/properties/{propertyId}/rooms/{roomId}
GET    /api/v1/properties/{propertyId}/rooms/{roomId}/timeline

GET    /api/v1/properties/{propertyId}/tasks
POST   /api/v1/properties/{propertyId}/tasks
POST   /api/v1/tasks/{taskId}/assignments
DELETE /api/v1/tasks/{taskId}/assignments/{assignmentId}
POST   /api/v1/tasks/{taskId}/start
POST   /api/v1/tasks/{taskId}/blockers
POST   /api/v1/tasks/{taskId}/blockers/{blockerId}/resolve
POST   /api/v1/tasks/{taskId}/complete

POST   /api/v1/tasks/{taskId}/inspections
GET    /api/v1/properties/{propertyId}/readiness
POST   /api/v1/properties/{propertyId}/simulations
GET    /api/v1/properties/{propertyId}/simulations/{simulationId}
```

이 endpoint 목록은 후보 계약이다. 구현 전에 OpenAPI로 요청·응답·오류 코드를 구체화한다. endpoint를 추가하거나 변경하면 API 문서와 프론트 타입을 함께 갱신한다.

## 9. 화면 정보 구조

### 9.1 운영 대시보드
- 전체 객실 수 및 상태별 객실 수
- 오늘 체크인/체크아웃
- 준비 위험 객실
- 작업 대기/진행/차단
- 작업자별 배정량
- 위험 사유 및 예상 준비 시각

### 9.2 객실 상세
- 객실 상태 및 예약
- 현재 작업과 담당자
- blocker 목록과 해제 예상 시각
- 검사 결과
- 시간순 이벤트 타임라인
- readiness 계산 근거

### 9.3 작업 관리
- 필터: 날짜, 상태, 작업 유형, 담당자, 층
- 배정/재배정
- 작업 시작/차단/차단 해제/완료
- 상태별 허용 액션 표시

### 9.4 시뮬레이션
- seed 및 정책 파라미터
- 기준 시나리오와 대안 시나리오
- KPI 비교
- 결과와 입력 조건 저장
- 실제 운영 데이터가 아닌 합성 시뮬레이션임을 명확히 표시

## 10. 보안·운영 기본값

- MVP는 인증을 단순화할 수 있으나, property 단위 데이터 접근 검증을 설계에서 누락하지 않는다.
- 비밀값은 환경변수/secret manager로 관리한다.
- 실제 고객 개인정보를 저장하지 않는다. 합성 데이터만 사용한다.
- 로그에 이름·개인정보·인증 토큰을 남기지 않는다.
- API 입력 검증과 DB 제약조건을 함께 적용한다.
- CORS 허용 origin을 환경별로 제한한다.
- 배포 환경에서 DB migration을 명시적으로 실행한다.

## 11. 테스트 전략

### 도메인 단위 테스트
- 허용/불허 상태 전이
- 활성 blocker 존재 시 재개/완료 거부
- 검사 실패 후 재청소 생성
- 근무시간 외 작업 배정/시작 제한
- readiness 계산 및 UNKNOWN 처리
- 중복 task 생성 방지

### 통합 테스트
- PostgreSQL Testcontainers
- 트랜잭션 내 상태 변경과 이벤트 기록
- 낙관적 잠금 충돌
- partial unique constraint로 활성 assignment 중복 방지

### 프론트엔드
- 상태별 버튼/액션 표시
- 로딩/빈 상태/오류 상태
- readiness 이유 렌더링
- 필터 및 페이지네이션

### E2E
1. 체크아웃 완료로 객실 dirty 전환 및 청소 작업 생성
2. 작업 배정 및 시작
3. blocker 등록·해제
4. 청소 완료 및 검사
5. 검사 실패 → 재청소 → 재검사 통과
6. 객실 READY
7. readiness 위험과 시뮬레이션 KPI 확인

## 12. 구현 순서

1. 저장소 scaffold 및 개발환경
2. Docker Compose + PostgreSQL + Flyway
3. Property / Room / Reservation 기본 도메인
4. Staff / StaffShift
5. HousekeepingTask / TaskAssignment / TaskBlocker
6. 상태 전이 Application Service 및 OperationalEvent
7. InspectionRecord 및 재청소 흐름
8. Readiness Calculator와 단위 테스트
9. REST API 및 OpenAPI
10. 합성 데이터 generator
11. Next.js 대시보드와 상세 화면
12. 작업 관리 화면
13. 시뮬레이션·KPI 비교
14. E2E 및 배포

각 단계는 테스트와 실행 가능한 상태를 유지한다. 한 번에 모든 기능을 생성하지 않는다.

## 13. 미결정 사항

- readiness 위험 등급의 구체적인 경계값
- 작업자별 동시 작업 허용 여부
- 여러 blocker의 중첩 계산 정책을 실제 운영 프로세스에 맞춰 조정할 수 있는지
- inspection을 별도 작업과 InspectionRecord로 동시에 표현하는 세부 규칙
- property별 복수 timezone/영업일 경계 정책
- 인증·권한 모델의 MVP 구현 수준
- simulation engine을 Kotlin으로 둘지 Python에서 실행할지에 대한 최종 경계

미결정 사항은 근거 없이 확정하지 말고, MVP에 필요한 최소 결정을 ADR로 남긴다.
