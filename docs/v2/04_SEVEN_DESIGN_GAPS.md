# StayOps — 7개 설계 충돌 상세 검토 및 해결 제안

> 목적: 초기 문서 검토에서 발견한 7개 불일치의 원인, 사용자 영향, 도메인 결정 필요사항, 권장 설계, API·DB·테스트 변경을 한 곳에서 추적한다.  
> 상태: **해결안은 권장안(`PROPOSAL`)이며 사용자 승인 전에는 확정 설계가 아니다.**  
> 기준일: 2026-11-04

## 0. 요약

초기 문서에는 E2E 시나리오가 전제하는 행위가 API에 없거나, 상태 전이 표기가 실제 저장 모델로 표현되지 않는 문제가 있었다. 이 문제는 단순 문서 누락이 아니라 구현 단계에서 서로 다른 해석을 낳고 데이터 무결성을 깨뜨릴 수 있다.

| # | 문제 | 핵심 충돌 | 권장 해결 |
|---|---|---|---|
| 1 | 체크인/체크아웃 API 누락 | E2E는 checkout을 요구하지만 API가 없음 | 예약 Command API + 단일 transaction |
| 2 | PENDING blocker 등록 불가 | 현장 차단 사유는 배정 전에도 발생 가능하지만 전이 불가 | PENDING 포함 blocker 등록 허용 |
| 3 | BLOCKED 복귀 상태 미정 | 복귀 전 상태를 저장할 필드 없음 | `statusBeforeBlocked` 저장 |
| 4 | 검사 흐름 불명확 | 검사 task와 record는 있으나 시작·객실 상태 전이 없음 | 공통 start command + 결과 제출 command |
| 5 | 시뮬레이션 실행 위치 충돌 | Simulation API/Run은 서버 실행처럼 보이나 Python 단계와 불일치 | 온라인 Kotlin, 오프라인 Python 권장 |
| 6 | 투숙 중 청소 상태 누락 | STAYOVER_CLEANING 작업은 있으나 객실 상태 전이 없음 | OCCUPIED → STAYOVER_CLEANING → OCCUPIED |
| 7 | 문서와 저장소 구조 불일치 | 문서는 `stayops/`, 실제 작업 폴더는 `handys/` | `handys/`를 루트 후보로, Phase 0에서 실사 |

---

## 1. 체크인·체크아웃 API 누락

### 발견한 문제
초기 E2E는 “체크아웃 처리 → 객실 VACANT_DIRTY → 청소 작업 생성”을 요구하지만 API 후보에는 예약 체크아웃/체크인 명령이 없었다. 예약 상태와 객실 상태를 누가 어떤 경계에서 바꾸는지 정의되지 않았다.

### 왜 문제인가
- 프론트엔드가 예약 상태를 직접 PATCH하는 방식으로 임의 구현할 수 있다.
- 예약은 CHECKED_OUT인데 객실은 OCCUPIED인 부분 상태가 발생할 수 있다.
- checkout cleaning task가 생성되지 않거나 중복 생성될 수 있다.
- 상태 변경과 이벤트 기록이 분리되면 타임라인과 실제 상태가 달라진다.

### 권장 설계 (`PROPOSAL`)
명시적 Command API:
```http
POST /api/v1/reservations/{reservationId}/check-in
POST /api/v1/reservations/{reservationId}/check-out
```

Checkout은 하나의 DB transaction에서 수행한다.
1. 예약이 CHECKED_IN인지 검증
2. 예약 CHECKED_OUT 및 actualCheckOutAt 기록
3. 객실 VACANT_DIRTY 전환
4. CHECKOUT_CLEANING task 생성
5. OperationalEvent 기록
6. commit

Check-in은 예약 CONFIRMED, 객실 READY, availability AVAILABLE, 객실 예약 충돌 없음 조건을 확인한 뒤 예약 CHECKED_IN 및 객실 OCCUPIED를 한 transaction으로 처리한다.

### 멱등성·무결성
- 같은 checkout 요청 재전송으로 청소 task가 중복 생성되지 않아야 한다.
- Idempotency-Key 또는 reservation+task type 기반 unique constraint를 검토한다.
- 동시 요청은 aggregate lock/version으로 처리한다.
- transaction 중 하나라도 실패하면 예약·객실·작업·이벤트 변경을 전부 rollback한다.

### 수용 기준
- checkout 성공 시 예약/객실/task/event가 모두 변경된다.
- task 생성 실패 시 모든 변경이 rollback된다.
- 동일 요청을 반복해도 task는 1건이다.
- READY가 아닌 객실에 check-in할 수 없다.
- OUT_OF_SERVICE 객실에 check-in할 수 없다.

---

## 2. PENDING 작업의 blocker 등록 불가

### 발견한 문제
초기 전이는 ASSIGNED/IN_PROGRESS → BLOCKED만 정의되어 있었다. 하지만 DND, 린넨 부족, 시설 문제 등은 작업자가 배정되기 전에도 발생할 수 있다.

### 왜 문제인가
- 배정 전 객실의 방해 요인을 기록할 수 없다.
- PENDING으로 남아 있는 작업이 실제로는 진행 불가한데 대시보드에서는 단순 대기처럼 보인다.
- readiness 계산에서 blocker 원인을 누락할 수 있다.

### 권장 설계 (`PROPOSAL`)
다음 상태에서 blocker 등록을 허용한다.
```text
PENDING     -> BLOCKED
ASSIGNED    -> BLOCKED
IN_PROGRESS -> BLOCKED
```

BLOCKED 상태는 활성 blocker가 한 개 이상일 때만 유효하다. blocker는 task에 연결하고 개별 해제 시각을 보존한다.

### 고려할 점
- DND와 같이 시간 기반인 blocker는 estimatedResolutionAt이 없을 수 있다.
- 예상 해제 시각이 없는 blocker는 readiness를 임의 계산하지 않고 UNKNOWN으로 만들 수 있다.
- 한 작업에 여러 blocker가 동시에 존재할 수 있다.
- 일부 blocker만 해결되면 계속 BLOCKED여야 한다.

### 수용 기준
- PENDING 작업에 blocker 등록 시 BLOCKED 전환
- 활성 blocker가 하나라도 남으면 계속 BLOCKED
- 모든 blocker가 해제되면 이전 상태 복원
- blocker 이력은 삭제되지 않고 resolvedAt으로 종료

---

## 3. 차단 해제 후 복귀 상태 기준 누락

### 발견한 문제
초기 설계는 BLOCKED → ASSIGNED와 BLOCKED → IN_PROGRESS를 허용하지만, 작업이 차단되기 전 어떤 상태였는지 저장하는 필드가 없다. PENDING에서 차단된 작업은 어디로 돌아가야 하는지 알 수 없다.

### 권장 설계 (`PROPOSAL`)
`HousekeepingTask.statusBeforeBlocked: TaskStatus?` 추가.

규칙:
- BLOCKED 진입 시 이전 상태를 저장한다.
- 허용값은 PENDING, ASSIGNED, IN_PROGRESS뿐이다.
- BLOCKED 상태에서만 값이 존재한다.
- 모든 활성 blocker 해제 시 저장된 상태로 복귀한다.
- 복귀 완료 후 필드는 null로 초기화한다.

### 담당자 유효성 문제
ASSIGNED/IN_PROGRESS 복귀 시 담당자가 여전히 활성이고 해당 근무시간인지 검증해야 한다. 유효하지 않다면 기존 담당자에게 자동 복귀시키는 것은 위험하다.

**권장 정책 (`PROPOSAL`):** 담당자가 유효하지 않으면 assignment를 종료하고 PENDING으로 복귀시킨 뒤 재배정을 요구한다. 자동으로 다른 담당자를 배정하지 않는다.

### 수용 기준
- 세 가지 이전 상태가 정확히 복원된다.
- 복수 blocker에서는 마지막 blocker 해제 시 복귀한다.
- 잘못된 상태의 statusBeforeBlocked 값은 저장되지 않는다.
- 담당자 무효 상황은 명시된 정책대로 처리된다.

---

## 4. 검사 흐름 불명확

### 발견한 문제
초기 모델에는 `HousekeepingTask(type=INSPECTION)`과 `InspectionRecord`가 함께 존재하지만 객실이 언제 INSPECTING이 되는지, 검사 시작 API가 무엇인지 정의되지 않았다.

### 권장 역할 분리 (`PROPOSAL`)
- `HousekeepingTask(type=INSPECTION)`: 검사라는 업무의 배정·시작·완료 상태
- `InspectionRecord`: 검사 결과(PASSED/FAILED), 실패 사유, 검사자, 검사 시각

즉 task는 workflow, record는 outcome을 표현한다.

### 권장 흐름
1. 청소 task 완료
2. inspection task 생성
3. 검사자 배정
4. 검사자가 공통 `POST /api/v1/tasks/{taskId}/start` 실행
5. task IN_PROGRESS, 객실 INSPECTING
6. `POST /api/v1/tasks/{inspectionTaskId}/inspections`로 결과 제출
7. PASSED: 검사 task 완료, 객실 READY (다른 조건 충족 시)
8. FAILED: 검사 task 완료, 실패 record 생성, RE_CLEANING task 생성, 객실 VACANT_DIRTY
9. 재청소 완료 후 새 inspection task 생성

### API 역할
검사 시작 전용 endpoint를 별도 만들지 않고 일반 task start를 재사용한다. 서버는 task type이 INSPECTION인지 확인해 객실 상태도 전이시킨다. 결과 제출 API는 결과 기록과 후속 작업 생성의 transaction을 담당한다.

### 수용 기준
- 청소 완료 전 검사 task 시작 불가
- 검사 task 시작 시 객실 INSPECTING
- PASSED 전 READY 불가
- FAILED 시 failureReason 필수 및 RE_CLEANING 생성
- 재검사는 새 task로 이력 보존

---

## 5. 시뮬레이션 실행 위치 충돌

### 발견한 문제
초기 설계는 SimulationRun과 POST /simulations API를 두어 백엔드 실행을 암시하지만, 구현 단계는 Python generator/시뮬레이터 중심이었다. API가 실제로 어느 프로세스를 호출하고 결과를 어떻게 저장하는지 정의되지 않았다.

### 선택지 비교

| 선택지 | 장점 | 단점 |
|---|---|---|
| A. Kotlin에서 온라인 실행 | 도메인 모델·트랜잭션·API와 가까움, 운영 배포 단순 | 시뮬레이션 계산 로직도 Kotlin으로 작성 |
| B. Python 상시 API | 분석 생태계 활용, pandas 등 편리 | 별도 서비스·배포·통신·관측 부담 |
| C. Kotlin이 Python subprocess 호출 | 코드 재사용 가능 | 프로세스 관리·타임아웃·오류·배포 복잡성 |
| D. Python 오프라인 파일 실행 | 실험/분석에 적합, 단순 | 사용자 요청 즉시 결과 반환과는 별도 흐름 |

### 권장안 (`PROPOSAL`)
- 온라인 `POST /simulations`: Kotlin 백엔드가 실행하고 SimulationRun에 저장
- Python: 합성 데이터 생성, 오프라인 반복 실험, KPI 분석
- 초기에는 Python API 서버 및 subprocess 호출 없음
- 필요가 입증되면 별도 ADR로 worker/service 경계를 결정

### 수용 기준
- POST API가 실행 엔진과 결과 저장 책임을 명확히 가진다.
- SimulationRun의 status lifecycle이 정의된다.
- 동일 seed와 파라미터 재실행 결과가 재현된다.
- Python은 MVP 온라인 API의 숨은 런타임 의존성이 아니다.

---

## 6. 투숙 중 청소의 객실 상태 흐름 누락

### 발견한 문제
`STAYOVER_CLEANING` 작업 유형은 있지만 객실 상태는 체크아웃 이후 VACANT_DIRTY → CLEANING → INSPECTING → READY 흐름만 정의되어 있었다.

### 왜 문제인가
투숙 중 청소는 객실이 빈 객실이 되는 것이 아니다. 청소 완료 후 READY로 바꾸면 객실이 판매/투숙 가능 상태로 잘못 해석되거나 예약 상태와 충돌할 수 있다.

### 권장 설계 (`PROPOSAL`)
객실 상태 enum에 `STAYOVER_CLEANING` 추가:
```text
OCCUPIED
  -> STAYOVER_CLEANING
  -> OCCUPIED
```

- 시작 시: OCCUPIED → STAYOVER_CLEANING
- 완료 시: STAYOVER_CLEANING → OCCUPIED
- 투숙 예약은 계속 CHECKED_IN
- 청소 완료만으로 READY 전환 금지
- 실패/중단/차단 시 복귀 정책은 task lifecycle과 연결

### 수용 기준
- 투숙 중 청소 완료 후 OCCUPIED 복귀
- 투숙 예약 상태가 유지됨
- 객실 READY로 전환되지 않음
- 체크아웃 청소와 stayover cleaning이 다른 task type 및 객실 상태 규칙을 사용

---

## 7. 문서 구조와 실제 폴더 구조 불일치

### 발견한 문제
초기 문서는 `stayops/` 루트 구조를 전제했지만 사용자의 작업 디렉터리는 `handys/`이며 당시 `docs/`만 있고 Git 저장소가 아니라고 보고되었다. 실제 구조를 조사하지 않고 `stayops/` scaffold를 만들면 중첩 폴더가 생기거나 문서 위치가 갈라질 수 있다.

### 권장 설계 (`PROPOSAL`)
- `handys/`를 프로젝트 루트 후보로 사용한다.
- `handys/docs/`에 문서를 둔다.
- 앱은 `apps/web`, `apps/api`, Python 도구는 `tools/simulator`로 구성한다.
- Git 저장소 여부는 Claude Code Phase 0에서 확인한다.
- Git 초기화·파일 이동·기존 구조 변경은 조사 결과를 사용자에게 보고한 뒤 승인받는다.

### 수용 기준
- 실제 작업 경로와 문서 경로가 일치한다.
- `handys/handys/` 같은 중첩 프로젝트 루트가 생기지 않는다.
- 기존 파일이 무단 이동/삭제되지 않는다.
- Git 초기화 여부와 이유가 기록된다.

---

## 8. 7개 이슈 간 의존관계

- #1 checkout 명령은 #2/#3의 task lifecycle과 #4 inspection 생성으로 이어진다.
- #2 blocker 등록 허용은 #3 이전 상태 저장 없이는 안전하게 복귀할 수 없다.
- #4 inspection은 #6의 stayover cleaning과 분리된 객실 상태 규칙을 가져야 한다.
- #5는 API 설계·배포 구성·Phase 순서에 영향을 준다.
- #7은 구현 전 저장소 조사 순서에 영향을 준다.

따라서 개별 항목만 고치지 말고 기술 문서·API·DB·E2E·하네스의 표현을 함께 맞춰야 한다.

## 9. 추가로 점검해야 할 인접 설계 위험

다음은 7개 핵심 이슈에서 파생되는 추가 검토 항목이다.

1. **예약 객실 중복:** 동일 객실의 시간 중복 활성 예약 방지
2. **중복 task:** checkout 이벤트 재전송 및 재시도 시 task 중복 방지
3. **재배정 이력:** 기존 assignment를 삭제하지 않고 종료 시각 보존
4. **복수 blocker:** 모든 blocker가 해결되기 전에는 BLOCKED 유지
5. **OUT_OF_SERVICE:** 객실 사용 불가와 청소 진행 상태를 분리
6. **READY 조건:** 검사 통과뿐 아니라 활성 blocker·시설 제한·필수 작업을 확인
7. **이벤트 원자성:** 상태 변경과 OperationalEvent를 같은 transaction에서 기록
8. **동시성:** 중복 checkout, 동시 assignment, 검사 결과 중복 제출 방지
9. **투숙 중 청소:** checkout cleaning과 다른 완료 후 객실 상태를 사용

## 10. 사용자 승인 체크포인트

아래 항목은 권장안이므로 사용자가 승인하거나 수정해야 한다.

| 항목 | 권장안 | 상태 |
|---|---|---|
| 체크인/체크아웃 | 명시적 Command API + 단일 transaction | PROPOSAL |
| PENDING blocker | 등록 허용 | PROPOSAL |
| 차단 복귀 | statusBeforeBlocked 저장 | PROPOSAL |
| 담당자 무효 복귀 | PENDING으로 복귀, assignment 종료 | PROPOSAL |
| 검사 모델 | INSPECTION task + InspectionRecord | PROPOSAL |
| 검사 시작 | 공통 task start command | PROPOSAL |
| 검사 실패 | RE_CLEANING 후 새 검사 task | PROPOSAL |
| 시뮬레이션 | 온라인 Kotlin, 오프라인 Python | PROPOSAL |
| 투숙 중 청소 | OCCUPIED → STAYOVER_CLEANING → OCCUPIED | PROPOSAL |
| 프로젝트 루트 | handys/ 후보, Phase 0 실사 | PROPOSAL |

## 11. 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-11-04 | 초기 문서의 E2E/API/상태 모델/저장소 구조 불일치 7개를 정리하고 교차 문서 수정 방향을 제안 |
| 2026-11-04 | 각 해결안의 API·DB·트랜잭션·테스트 수용 기준을 추가 |
