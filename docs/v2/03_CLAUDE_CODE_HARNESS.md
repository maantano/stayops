# StayOps — Claude Code 하네스 및 구현 지시서

> Claude Code는 구현·테스트를 담당한다. 제품 사실이나 운영 데이터를 만들어내지 않는다.  
> 이 문서는 작업 규율과 구현 순서를 정의하며, 도메인 규칙은 `02_TECHNICAL_DESIGN.md`를 따른다.

## 1. 시작 전 읽을 문서

1. `docs/01_PRODUCT_DISCOVERY.md`
2. `docs/02_TECHNICAL_DESIGN.md`
3. `docs/03_CLAUDE_CODE_HARNESS.md`
4. `docs/04_SEVEN_DESIGN_GAPS.md`

문서 간 충돌이 있으면 임의로 결정하지 않는다. 이슈를 정리하고 사용자에게 질문한다. 특히 `PROPOSAL`은 사용자 승인 전까지 확정 규칙이 아니다.

## 2. 기본 행동 규칙

- 저장소 구조, 기존 코드, package manager, build tool을 먼저 확인한다.
- 기존 구현·설정·명령을 확인하고 중복 scaffold를 만들지 않는다.
- Phase 단위로 작업하며, 각 단계 시작 전 변경 계획과 파일 범위를 제시한다.
- 승인된 범위만 구현한다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.
- 테스트 실패는 원인·영향·수정·재실행 결과를 보고한다.
- 실제 운영 데이터가 없는 기능은 합성 데이터임을 UI와 문서에 표시한다.
- 외부 API, 유료 서비스, 고객 개인정보를 임의로 연결하지 않는다.
- 마이크로서비스, 메시지 브로커, 캐시 등은 요구와 근거 없이 추가하지 않는다.
- TODO에는 이유·영향·후속 조치를 기록한다.

## 3. 문서 상태 표기

- `EVIDENCE`: 공개 근거
- `INTERPRETATION`: 근거 해석
- `HYPOTHESIS`: 검증 전 제품 가설
- `ASSUMPTION`: 구현상 가정
- `PROPOSAL`: 사용자 승인 전 권장안
- `DECISION`: 승인된 결정
- `IMPLEMENTED`: 코드 반영
- `VERIFIED`: 테스트/재현 검증
- `OPEN`: 미결정

새 결정은 `docs/decisions/ADR-NNN-short-title.md`로 기록한다. 기존 ADR을 덮어쓰지 않는다. 번복 시 새 ADR을 추가하고 이전 ADR을 superseded 처리한다.

## 4. 프로젝트 루트 및 폴더 구조

작업 루트 후보는 사용자의 `handys/` 디렉터리다. Claude Code는 실제 경로와 Git 상태를 확인한다.

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

위는 목표 구조다. 현재 구조라고 가정하지 않는다. 기존 코드가 있으면 보존하고 이동/삭제 전에 확인을 받는다.

## 5. 구현 원칙

### Backend
- Kotlin + Spring Boot
- Controller는 HTTP 변환·검증에 집중
- 도메인 상태 변경은 application service/command handler에서 처리
- JPA Entity를 API 응답으로 직접 노출하지 않음
- transaction 경계를 명시
- 상태 변경과 OperationalEvent 기록은 원자적으로 처리
- Flyway migration은 추가만 하고 기존 migration을 수정하지 않음
- 도메인 오류를 안정적인 error code로 반환
- 동시성 충돌은 409 Conflict 등 명확한 응답

### Frontend
- Next.js App Router + TypeScript strict
- TanStack Query로 서버 상태 관리
- React Hook Form + Zod
- 로딩·오류·빈 상태 제공
- 서버에서 최종 권한·상태 검증
- 키보드 탐색, focus, label, 의미 있는 heading 제공
- 색상만으로 상태를 구분하지 않음

### Python
- uv로 의존성 관리
- 합성 데이터·오프라인 실험·KPI 분석 중심
- 고정 seed의 재현성 보장
- 임의 데이터 분포는 config로 노출하고 ASSUMPTION 표시
- 초기에는 상시 API 서버로 만들지 않음

## 6. 필수 도메인 불변식

`02_TECHNICAL_DESIGN.md`를 기준으로 구현한다. 핵심 규칙:
1. PENDING에는 활성 assignment가 없다.
2. ASSIGNED/IN_PROGRESS에는 유효한 활성 assignment가 하나 있다.
3. BLOCKED에는 활성 blocker가 하나 이상이며 이전 상태가 저장되어 있다.
4. blocker가 모두 해소되기 전 작업을 재개/완료할 수 없다.
5. blocker가 모두 해소되면 이전 상태로 복귀하되 담당자 유효성을 재검증한다.
6. COMPLETED 작업은 재시작할 수 없다.
7. checkout은 예약·객실·청소 작업·이벤트를 원자적으로 변경한다.
8. 동일 명령 재전송으로 중복 작업이 생기지 않는다.
9. 검사 시작 시 객실은 INSPECTING이 된다.
10. 검사 통과 전 READY가 될 수 없다.
11. 검사 실패는 RE_CLEANING 후 재검사로 이어진다.
12. stayover cleaning 완료 후 객실은 OCCUPIED로 복귀한다.
13. OUT_OF_SERVICE 해제만으로 READY가 되지 않는다.
14. 상태 변경과 이벤트는 같은 transaction에서 기록한다.

## 7. 단계별 구현 계획

### Phase 0 — Repository reconnaissance (코드 변경 금지)
확인:
- 현재 경로 및 디렉터리 구조
- Git 저장소 여부 및 working tree
- Node/Kotlin/JDK/Python 버전
- package manager/build tool
- 기존 scaffold
- lint/typecheck/test/build 명령
- Docker/DB 구성
- 문서 설계와 기존 코드의 충돌
- 7개 설계 이슈 중 기존 코드에 이미 반영된 내용 여부

완료 조건:
- 변경 파일 0개
- 발견 사항 및 다음 단계 파일 목록 보고
- `PROPOSAL` 승인 필요 항목을 사용자에게 확인
- 사용자 확인 전 Phase 1 진행 금지

### Phase 1 — Scaffold and local environment
- 승인된 구조에 맞춰 web/api/simulator scaffold
- Docker Compose PostgreSQL
- 환경변수 예시
- health endpoint
완료: 로컬 실행, build, health check 성공.

### Phase 2 — Schema and core entities
- Property, Room, Reservation, Staff, StaffShift
- HousekeepingTask, TaskAssignment, TaskBlocker
- InspectionRecord, OperationalEvent
- Flyway 및 DB constraint/index
완료: migration 및 Testcontainers 통합 테스트.

### Phase 3 — Reservation commands
- check-in/check-out
- 객실 상태 전이
- checkout cleaning task 생성
- 멱등성 및 transaction
완료: 성공·실패 rollback·중복 재전송 테스트.

### Phase 4 — Task lifecycle
- 배정/재배정
- PENDING/ASSIGNED/IN_PROGRESS blocker 등록
- blocker별 해제 및 이전 상태 복원
- 작업 완료/취소
완료: 모든 상태 조합 테스트 및 동시성 테스트.

### Phase 5 — Inspection and stayover
- 검사 작업 생성·배정·시작·결과
- 검사 실패 후 재청소·재검사
- stayover cleaning의 OCCUPIED 복귀
완료: 전체 상태 흐름 테스트.

### Phase 6 — Readiness and API contract
- 계산기 및 UNKNOWN
- 원인 설명 응답
- OpenAPI
완료: 경계값·누락·blocker 중첩 테스트, API 계약 확정.

### Phase 7 — Synthetic data and simulation
- Python generator, 고정 seed
- 오프라인 시나리오 및 KPI
- 승인된 경우 Kotlin 온라인 시뮬레이션
완료: 재현성·산식 테스트, 가정 표시.

### Phase 8 — Frontend operations UI
- 대시보드
- 객실 목록/상세/타임라인
- 예약 명령 UI
- 작업·검사 관리
- readiness 근거
- 시뮬레이션 비교
완료: 컴포넌트 테스트, 접근성 기본 점검.

### Phase 9 — E2E and deployment
- Playwright 핵심 플로우
- Vercel/AWS ECS 구성
- Terraform은 실제 필요 범위만
완료: 재현 가능한 배포 절차 및 테스트 결과 기록.

## 8. 필수 테스트 시나리오

1. CHECKED_IN 예약 checkout → CHECKED_OUT + VACANT_DIRTY + 청소 task 1건
2. checkout 동일 요청 반복 → 중복 task 없음
3. PENDING task에 blocker → BLOCKED → 전부 해제 → PENDING
4. ASSIGNED task에 blocker → 해제 후 ASSIGNED
5. IN_PROGRESS task에 blocker → 해제 후 IN_PROGRESS
6. 복수 blocker 중 일부만 해제 → 계속 BLOCKED
7. 담당자 무효 상태로 blocker 해제 → 승인된 정책대로 복귀/재배정 필요
8. 청소 완료 → 검사 task 생성
9. 검사 시작 → 객실 INSPECTING
10. 검사 PASSED → 조건 충족 시 READY
11. 검사 FAILED → RE_CLEANING 생성, READY 금지
12. 재청소 완료 → 새 검사 task
13. stayover cleaning 시작/완료 → OCCUPIED 복귀
14. OUT_OF_SERVICE 객실 check-in 거부
15. readiness 입력 누락 → UNKNOWN
16. 동일 seed/parameter → 동일 simulation 결과

## 9. 데이터·보안 원칙

- 합성 데이터는 `isSynthetic` 또는 dataset metadata로 식별
- 실제 고객 이름·연락처·결제정보 미사용
- seed와 생성 파라미터 기록
- 로그에 개인정보·토큰 기록 금지
- property scope 검증
- 환경별 CORS 제한
- secret은 환경변수 또는 secret manager

## 10. 결과 보고 형식

```text
## 작업 요약
- 구현한 내용

## 변경 파일
- 경로: 목적

## 검증
- 실제 실행한 명령
- 결과
- 미실행 테스트와 이유

## 결정/가정
- 승인된 결정
- 임시 가정
- 미결정 사항

## 문서 갱신
- 수정한 문서 및 이유

## 다음 단계
- 다음으로 진행할 작은 단위
```

## 11. 첫 번째 Claude Code 프롬프트

아래 프롬프트는 Phase 0만 수행하도록 제한한다.

---

이 저장소에서 StayOps 프로젝트를 진행한다.

먼저 다음 문서를 읽어라.
- `docs/01_PRODUCT_DISCOVERY.md`
- `docs/02_TECHNICAL_DESIGN.md`
- `docs/03_CLAUDE_CODE_HARNESS.md`
- `docs/04_SEVEN_DESIGN_GAPS.md`

이번 작업은 **Phase 0 — Repository reconnaissance만 수행**한다. 코드를 수정하거나 파일을 생성·삭제·이동하지 마라.

조사 항목:
1. 현재 작업 디렉터리의 실제 경로와 구조
2. Git 저장소 여부, branch, working tree
3. Node/JDK/Kotlin/Python 버전
4. package manager와 build tool
5. frontend/backend/python scaffold 존재 여부
6. 기존 lint/typecheck/test/build 명령
7. Docker 및 DB 구성
8. 기존 코드/문서에 이미 구현된 도메인 규칙
9. 문서와 실제 저장소 사이의 충돌
10. 일곱 가지 설계 이슈 각각의 적용 상태
11. Phase 1에서 생성·수정이 예상되는 파일 목록

반드시 다음을 지켜라.
- 기존 파일을 수정·삭제·이동하지 않는다.
- Git 초기화, dependency 설치, scaffold 생성도 하지 않는다.
- 확인한 사실과 추측을 분리한다.
- 설계 문서의 `PROPOSAL`은 사용자 승인 전까지 확정하지 않는다.
- 조사 결과와 미결정 사항을 보고한 뒤 중단하고 사용자 확인을 기다린다.

보고 형식은 하네스 문서의 `결과 보고 형식`을 따른다.

---

## 12. 완료 선언 기준

아래가 확인되기 전에는 프로젝트 완료라고 표현하지 않는다.
- 핵심 업무 흐름 E2E 성공
- 서버가 도메인 불변식 강제
- readiness 근거 표시
- 합성 데이터와 실제 데이터 구분
- 시뮬레이션 재현성
- 실행한 테스트 결과 기록
- 배포 절차 문서화
- 제품 가설 및 검증 한계의 과장 없는 설명
