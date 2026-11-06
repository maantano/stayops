# StayOps — Claude Code 하네스 및 구현 지시서

> 이 문서는 Claude Code가 프로젝트를 구현할 때 따라야 하는 작업 방식과 품질 기준이다.
> Claude Code는 코드를 작성하고 테스트를 실행한다. 제품 사실·도메인 근거를 임의로 만들어내지 않는다.

## 1. 프로젝트 목표

StayOps는 호텔 운영 담당자가 객실 준비 상태, 하우스키핑 작업, 차단 사유, 담당자 업무량을 파악하고 운영 대안을 시뮬레이션하는 MVP다.

핵심 가치 가설:
1. 객실 준비 위험을 조기에 식별한다.
2. 위험의 계산 근거와 원인을 설명한다.
3. 운영 대안별 결과를 시뮬레이션해 비교한다.

제품 가설과 공개 리서치의 한계는 `01_PRODUCT_DISCOVERY.md`를 기준으로 한다. 도메인과 기술 구현 규칙은 `02_TECHNICAL_DESIGN.md`를 기준으로 한다.

## 2. 작업 시작 전 반드시 읽을 문서

1. `01_PRODUCT_DISCOVERY.md`
2. `02_TECHNICAL_DESIGN.md`
3. `03_CLAUDE_CODE_HARNESS.md`

문서가 서로 충돌하면 임의로 선택하지 말고 충돌 내용을 사용자에게 보고하고, 결정 전에는 데이터 손실·대규모 구조 변경을 피한다.

## 3. Claude Code 기본 행동 규칙

- 먼저 저장소 구조와 기존 코드를 읽는다.
- 이미 존재하는 구현·설정·명령어를 확인하고 중복 scaffold를 만들지 않는다.
- 구현 전 작업 계획과 영향을 받는 파일을 짧게 제시한다.
- 사용자가 승인한 범위 안에서 작은 단위로 구현한다.
- 한 번에 전체 애플리케이션을 생성하지 않는다.
- 각 단계에서 빌드·테스트·린트를 실행한다.
- 테스트가 실패하면 실패 원인을 분석하고 수정한 뒤 재실행한다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.
- 실제 운영 데이터가 없는 기능은 합성 데이터 기반임을 UI와 문서에 표시한다.
- 외부 API나 유료 서비스를 임의로 연결하지 않는다.
- 실제 고객 개인정보를 사용하지 않는다.
- 불필요한 마이크로서비스, 메시지 브로커, 캐시, 복잡한 이벤트 아키텍처를 도입하지 않는다.
- 요구되지 않은 기능을 추가하지 않는다.
- TODO를 남길 때는 이유와 후속 작업을 명시한다.

## 4. 문서 우선 원칙

문서와 코드가 달라지면 실제 구현을 확인한 뒤 문서를 갱신한다.

문서에서 사용하는 상태 표기:
- `EVIDENCE`: 외부 공개 자료로 확인한 사실
- `ASSUMPTION`: MVP를 위해 임시로 둔 가정
- `HYPOTHESIS`: 검증 전 제품 가설
- `DECISION`: 프로젝트에서 선택한 설계
- `IMPLEMENTED`: 코드로 구현된 사항
- `VERIFIED`: 자동화 테스트 또는 재현 가능한 실행으로 검증한 사항
- `OPEN`: 아직 결정/검증되지 않은 사항

새로운 결정은 `docs/decisions/ADR-NNN-*.md`에 기록한다. ADR은 기존 결정 문서를 덮어쓰지 않고, 대안·선택 이유·영향·날짜를 포함한다.

## 5. 권장 저장소 구조

```text
stayops/
  apps/
    web/
    api/
  tools/
    simulator/
  infra/
    docker/
    terraform/
  docs/
    01_PRODUCT_DISCOVERY.md
    02_TECHNICAL_DESIGN.md
    03_CLAUDE_CODE_HARNESS.md
    decisions/
  docker-compose.yml
  README.md
```

기존 저장소 구조가 이미 있다면 이를 우선 존중한다. 위 구조를 무조건 강제하지 않는다.

## 6. 개발 원칙

### Backend
- Kotlin + Spring Boot.
- Controller는 HTTP 입출력과 DTO 변환에 집중한다.
- 도메인 상태 변경은 Application Service를 통해 수행한다.
- JPA Entity를 API 응답으로 직접 노출하지 않는다.
- 트랜잭션 경계를 명확히 한다.
- 상태 변경과 OperationalEvent 기록은 같은 트랜잭션에서 처리한다.
- DB 제약조건으로 중복·무결성을 보강한다.
- 시간은 UTC instant로 저장하고 property timezone으로 현지 시간을 계산한다.
- Flyway migration으로 스키마를 변경한다. 기존 migration 파일을 수정하지 않는다.

### Frontend
- Next.js App Router + TypeScript strict.
- 서버 데이터는 TanStack Query로 관리한다.
- 폼은 React Hook Form + Zod를 사용한다.
- API 계약은 OpenAPI에서 생성하거나 명시적으로 동기화한다.
- 로딩·오류·빈 상태를 빠뜨리지 않는다.
- 상태별 불가능한 액션은 UI에서 숨기거나 비활성화하되, 최종 검증은 서버에서 수행한다.
- 접근성: 의미 있는 heading, label, keyboard navigation, focus 표시, 색상만으로 상태를 전달하지 않기.

### Python
- `uv`로 의존성을 관리한다.
- 합성 데이터 생성과 시뮬레이션/분석 스크립트에 한정한다.
- seed를 고정하면 같은 결과를 재현할 수 있어야 한다.
- 실제 측정 데이터처럼 보이는 이름·문구를 사용하지 않는다.

## 7. 코드 품질 규칙

- TypeScript strict 및 Kotlin null-safety를 활용한다.
- `any`, 무분별한 `!!`, 광범위한 예외 삼키기를 피한다.
- 도메인 enum과 상태 전이를 명시적으로 정의한다.
- magic number를 여러 곳에 중복하지 않는다.
- 설정값은 설정 객체/환경변수로 관리하고 기본값을 문서화한다.
- 입력 검증 실패와 도메인 상태 충돌을 구분한다.
- API 오류는 안정적인 error code를 제공한다.
- 로그에는 correlationId를 포함할 수 있도록 한다.
- 복잡한 계산은 순수 함수로 분리해 단위 테스트한다.

## 8. 필수 도메인 불변식

다음 규칙은 서버에서 강제하고 테스트한다.

1. PENDING 작업에는 활성 담당자가 없다.
2. ASSIGNED/IN_PROGRESS 작업에는 유효한 담당자가 있다.
3. BLOCKED 작업에는 활성 blocker가 하나 이상 있다.
4. 활성 blocker가 있으면 재개/완료할 수 없다.
5. COMPLETED 작업은 재시작할 수 없다.
6. 검사 작업은 청소 완료 이후에만 시작할 수 있다.
7. 검사 통과 전 객실은 READY가 될 수 없다.
8. 시설 수리 완료가 객실 준비 완료를 의미하지 않는다.
9. 근무 시간 외 작업 시작/배정은 정책에 따라 거부한다.
10. 상태 변경과 이벤트 기록은 원자적으로 처리한다.
11. 중복 요청으로 동일 목적의 작업이 중복 생성되지 않는다.
12. 객실 운영 상태와 availability 상태를 하나의 enum으로 합치지 않는다.

## 9. Readiness Calculator 요구사항

입력:
- checkInAt
- estimatedTaskStartAt
- remainingCleaningMinutes
- expectedRemainingBlockerMinutes
- inspectionMinutes
- operationalBufferMinutes
- dataCompleteness

계산:
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

필수 규칙:
- 이미 경과한 blocker 시간은 다시 더하지 않는다.
- 중첩 blocker 시간을 무조건 합산하지 않는다.
- 해제 예상 시각이 없는 blocker에 임의 시간을 넣지 않는다.
- 필수 데이터가 부족하면 UNKNOWN을 반환한다.
- 위험 결과에는 원인과 입력 근거를 포함한다.
- 위험 등급 경계값은 한 곳에서 설정하고 테스트한다.
- 계산 로직을 UI에 구현하지 않는다. 서버가 기준 결과를 반환한다.

## 10. 합성 데이터 규칙

초기 가상 호텔:
- 80 rooms: Standard 40, Deluxe 24, Suite 12, Family 4
- Housekeepers 6, Inspectors 2
- Checkout 11:00, Check-in 15:00
- 14-day simulation

초기 가정:
- Checkout cleaning 30–55 min
- Stay-over cleaning 15–30 min
- Inspection 5–10 min
- Linen wait 0–20 min
- Maintenance block 10–60 min

모두 임의의 시뮬레이션 가정이다. generator의 config로 분리하고 README/화면에 명시한다. 생성된 데이터에 `synthetic` 표시를 추가할 수 있으면 추가한다.

동일 seed와 동일 파라미터로 재실행하면 결과가 재현되어야 한다.

## 11. 구현 단계 및 완료 조건

### Phase 0 — Repository reconnaissance
작업:
- 저장소 구조 확인
- 런타임/패키지 매니저/빌드 도구 확인
- 기존 lint/test 명령 확인
- 문서와 실제 코드 간 차이 기록

완료:
- 변경 파일 없이 현재 구조 및 실행 방법 보고
- 발견한 위험·충돌 사항 보고

### Phase 1 — Scaffold and local environment
작업:
- Next.js 앱
- Kotlin Spring Boot 앱
- Python uv 프로젝트
- Docker Compose PostgreSQL
- 환경변수 예시
- 기본 health endpoint

완료:
- 로컬에서 앱과 DB 실행
- frontend/backend build 성공
- health endpoint 응답
- 실행 방법 README 작성

### Phase 2 — Database and core domain
작업:
- Flyway
- Property, Room, Reservation
- Staff, StaffShift
- HousekeepingTask, TaskAssignment, TaskBlocker
- InspectionRecord
- OperationalEvent
- UUID, TIMESTAMPTZ, JSONB, version
- 필요한 unique/check/index 제약조건

완료:
- migration clean database에서 성공
- repository/integration tests 통과
- ERD 또는 schema 문서 갱신

### Phase 3 — State transitions and application services
작업:
- 작업 배정/해제
- 시작/차단/해제/완료
- 검사 및 재청소 생성
- 객실 상태 전이
- OperationalEvent 원자적 기록

완료:
- 허용/불허 상태 전이 테스트
- 중복 요청 방지 테스트
- 동시성 충돌 처리

### Phase 4 — Readiness
작업:
- 순수 계산 모듈
- 위험 등급 및 UNKNOWN
- 원인 설명
- 대시보드용 조회 모델

완료:
- 경계값 테스트
- 누락 데이터 테스트
- blocker 중첩/예상 해제 시각 테스트
- 계산 근거를 API 응답에서 확인 가능

### Phase 5 — API contract
작업:
- REST endpoint
- validation
- error response
- OpenAPI

완료:
- API 문서 생성
- API integration tests
- 프론트에서 사용할 타입 계약 확정

### Phase 6 — Synthetic data and simulation
작업:
- Python generator
- fixed seed
- baseline/alternative policy
- KPI 집계 및 결과 저장/출력

완료:
- 동일 seed 재현성 테스트
- KPI 계산 테스트
- 시뮬레이션 가정 문서화

### Phase 7 — Frontend operations UI
작업:
- 대시보드
- 객실 목록/상세/타임라인
- 작업 관리
- readiness 근거 표시
- 시뮬레이션 비교

완료:
- 로딩/오류/빈 상태
- 접근성 기본 점검
- 핵심 컴포넌트 테스트

### Phase 8 — E2E, deployment and handoff
작업:
- Playwright 주요 흐름
- Vercel 배포
- AWS ECS 배포 구성
- Terraform
- 운영/개발 README

완료:
- E2E 통과
- 배포 재현 절차 문서화
- 환경변수/secret 설정 문서화
- 알려진 한계와 미완료 항목 정리

## 12. 테스트 시나리오

1. 체크아웃 처리 → 객실 VACANT_DIRTY → checkout cleaning 생성
2. 작업 배정 → 작업 시작
3. 린넨 부족 blocker 등록 → 작업 BLOCKED
4. blocker 해제 → 작업 재개
5. 청소 완료 → 검사 작업 진행
6. 검사 FAILED → RE_CLEANING 생성
7. 재청소 완료 → 재검사 PASSED
8. 객실 READY
9. 체크인 시각·buffer·작업 소요 시간으로 readiness 계산
10. 필수 데이터 누락 시 UNKNOWN
11. 같은 요청 중복 전송 시 작업 중복 생성 방지
12. 같은 seed로 시뮬레이션 반복 시 동일 결과

## 13. Git 및 변경 관리

- 작은 단위로 커밋 가능한 변경을 만든다.
- 커밋 메시지는 변경 목적을 명확히 표현한다.
- migration은 추가만 하고 이미 공유된 migration을 수정하지 않는다.
- 대규모 리팩터링과 기능 추가를 한 변경에 섞지 않는다.
- API 계약 변경 시 frontend와 backend를 함께 확인한다.
- dependency 추가 전 표준 라이브러리/기존 의존성으로 해결 가능한지 검토한다.

## 14. 작업 결과 보고 형식

각 구현 단계가 끝나면 다음을 보고한다.

```text
## 작업 요약
- 구현한 내용

## 변경 파일
- 경로: 변경 목적

## 검증
- 실행한 명령
- 결과
- 실행하지 못한 테스트와 이유

## 결정/가정
- 새로 추가한 결정
- 임시 가정
- 미결정 사항

## 다음 단계
- 다음으로 진행할 작은 단위 작업
```

테스트 명령과 결과를 실제 실행한 경우에만 기록한다.

## 15. 첫 번째 Claude Code 프롬프트

아래 내용을 Claude Code 세션 첫 입력으로 사용한다.

---

이 저장소에서 StayOps 프로젝트를 시작하려고 한다.

먼저 `docs/01_PRODUCT_DISCOVERY.md`, `docs/02_TECHNICAL_DESIGN.md`, `docs/03_CLAUDE_CODE_HARNESS.md`를 모두 읽어라.

이번 작업은 **Phase 0 — Repository reconnaissance만 수행**한다. 아직 코드를 수정하거나 파일을 생성하지 마라.

다음 항목을 조사해서 보고해라.

1. 현재 저장소 디렉터리 구조
2. 설치된 런타임과 버전
3. package manager 및 build tool
4. frontend/backend/python 프로젝트의 기존 scaffold 여부
5. 기존 lint, typecheck, test, build 명령
6. Docker 및 DB 실행 구성 여부
7. 문서의 기술 설계와 현재 저장소 간 충돌
8. 다음 Phase 1에서 생성하거나 수정해야 할 파일 목록

보고 형식은 하네스 문서의 `작업 결과 보고 형식`을 따른다.

기존 코드나 설정을 삭제·덮어쓰지 말고, 불확실한 사항은 추정하지 말고 `OPEN`으로 표시해라. 조사 후 구현을 시작하지 말고 사용자 확인을 기다려라.

---

## 16. 최종 완료 기준

프로젝트를 완료했다고 말하기 전에 아래를 확인한다.

- 핵심 운영 흐름이 실제로 동작한다.
- 서버가 도메인 불변식을 강제한다.
- readiness 계산 근거를 사용자가 확인할 수 있다.
- 합성 데이터와 실제 데이터가 명확히 구분된다.
- 시뮬레이션이 재현 가능하다.
- 주요 도메인·API·E2E 테스트가 실행되고 결과가 기록되어 있다.
- 배포 절차가 문서화되어 있다.
- 제품 가설과 검증 한계가 과장 없이 README에 표현되어 있다.
