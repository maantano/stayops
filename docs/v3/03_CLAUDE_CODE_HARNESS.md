# StayOps — Claude Code 하네스 및 구현 지시서

> 구현 담당: Claude Code. 제품 사실·운영 데이터를 만들어내지 않는다.  
> 도메인 단일 기준: `docs/v3/02_TECHNICAL_DESIGN.md`.

## 1. 읽기 순서
1. `docs/README.md`
2. `docs/v3/01_PRODUCT_DISCOVERY.md`
3. `docs/v3/02_TECHNICAL_DESIGN.md`
4. `docs/v3/03_CLAUDE_CODE_HARNESS.md`
5. `docs/v3/04_DESIGN_GAPS_AND_DECISIONS.md`

문서 충돌 시 임의 구현하지 말고 충돌을 보고한다. 문서의 `OPEN`은 구현 전에 결정이 필요하다. `PROPOSAL`은 승인 전 확정 규칙이 아니다.

## 2. 기본 행동 규칙
- 저장소 구조·Git·기존 코드·toolchain을 먼저 조사한다.
- 기존 파일을 보존하고 중복 scaffold를 만들지 않는다.
- Phase 단위로 계획/파일 범위를 먼저 보고한다.
- 승인된 범위만 구현한다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.
- 합성 데이터는 화면/문서에 합성임을 표시한다.
- 실제 PMS, 유료 API, 개인정보를 임의 연결하지 않는다.
- 근거 없이 microservice, broker, cache를 추가하지 않는다.
- migration은 추가만 하고 이미 적용된 migration을 수정하지 않는다.
- 문서 변경 시 같은 버전 문서 간 참조와 상태 규칙을 모두 동기화한다.

## 3. 문서 버전 경로 규칙
- 현재 기준: `docs/v3/`.
- 보관: `docs/v1/`, `docs/v2/`.
- 문서 진입점: `docs/README.md`.
- v1/v2 삭제·덮어쓰기·이동 금지.
- 새 버전이 만들어지면 docs/README.md만 현재 버전을 가리키도록 갱신하고 이전 버전은 보존.
- 저장소에 문서가 루트 직하로 있거나 다른 경로라면 Phase 0에서 실사하고 보고한다. 승인 전 이동하지 않는다.

## 4. Phase 0 — Repository reconnaissance (코드 변경 금지)
확인:
- 실제 작업 경로/디렉터리
- Git 여부, branch, working tree
- Node/JDK/Kotlin/Python 버전
- package manager/build tool
- 기존 web/api/scaffold
- lint/typecheck/test/build 명령
- Docker/DB 구성
- docs/v3 문서와 기존 코드 충돌
- Room×Task lifecycle matrix의 기존 구현 여부
- migration 상태
- 생성/수정 예상 파일

완료:
- 파일 변경 0개
- 사실/추측 분리한 조사 보고
- 충돌·미결정·리스크 목록
- Phase 1 변경 파일 후보
- 사용자 확인 대기

**금지:** Git init, dependency install, scaffold, 파일 생성/삭제/이동, migration 실행, 코드 수정.

## 5. 구현 원칙

### Backend
- Kotlin + Spring Boot.
- Controller는 HTTP 계층만 담당.
- 도메인 규칙은 application/domain service.
- JPA Entity 직접 응답 금지.
- transaction 경계 명시.
- Room/Reservation/Task 변경과 event를 동일 transaction.
- property scope/RBAC 서버 검증.
- 동시성 충돌은 409 등 안정적 오류로 응답.

### Frontend
- Next.js App Router + TypeScript strict.
- TanStack Query server state.
- React Hook Form + Zod.
- loading/error/empty states.
- 서버가 최종 상태/권한 검증.
- 색상만으로 상태 구분하지 않음.

### Python
- uv, pytest.
- 합성 데이터/오프라인 분석.
- 고정 seed와 config 노출.
- MVP online API 서버 금지.

## 6. 반드시 지켜야 할 도메인 규칙
1. 청소 완료 직후 Room은 VACANT_DIRTY.
2. inspection task 생성/배정만으로 INSPECTING이 되지 않는다.
3. inspection task start 시 INSPECTING.
4. inspection PASSED → READY; 단 READY는 availability와 독립.
5. READY + OUT_OF_SERVICE는 check-in 불가.
6. OUT_OF_SERVICE 해제만으로 READY로 바꾸지 않는다.
7. Room은 workflow 단계, Task는 실행 상태를 표현한다.
8. Task BLOCKED/PENDING이어도 이미 시작된 workflow의 Room 상태를 유지한다.
9. 취소 결과는 Room×Task matrix에 따른다.
10. 시작 전 task 취소는 Room 상태 불변.
11. 진행 중 checkout/re-clean cancel → VACANT_DIRTY.
12. 진행 중 inspection cancel → VACANT_DIRTY, READY 금지.
13. 진행 중 stayover cancel/complete → OCCUPIED.
14. stayover task는 운영자가 수동 생성하며 생성 시 Room OCCUPIED 유지.
15. PENDING/ASSIGNED/IN_PROGRESS에 blocker 등록 가능.
16. blocker 전부 해제 후 이전 상태 복원, 담당자 무효면 PENDING 및 assignment 종료.
17. checkout은 reservation/room/task/event 원자 변경.
18. task/event 중복 생성 방지.
19. 예약 CHECKED_IN 상태는 stayover cleaning 동안 유지.
20. 상태 변경과 event는 동일 transaction.

## 7. 구현 단계
- Phase 0: 조사만.
- Phase 1: 승인된 구조로 scaffold/로컬 DB.
- Phase 2: schema/core entities.
- Phase 3: reservation commands.
- Phase 4: task lifecycle, blocker, cancel.
- Phase 5: inspection/re-clean/stayover manual creation.
- Phase 6: readiness/API contract.
- Phase 7: synthetic data/offline simulation.
- Phase 8: operations UI.
- Phase 9: E2E/deploy.

각 Phase의 종료 조건은 기술 설계 §14~15와 수용 테스트를 따른다. 상태 모델과 API를 구현 전에 OpenAPI/테스트로 고정한다.

## 8. 필수 E2E
1. checkout → CHECKED_OUT + VACANT_DIRTY + checkout task 1건
2. 동일 checkout 재전송 → 중복 없음
3. PENDING blocker → BLOCKED → 해제 → PENDING
4. ASSIGNED/IN_PROGRESS blocker 및 복수 blocker
5. 담당자 무효 복귀 → PENDING + assignment 종료
6. checkout cleaning start → CLEANING
7. cleaning complete → VACANT_DIRTY
8. inspection task create/assign → VACANT_DIRTY
9. inspection start → INSPECTING
10. PASSED → READY
11. FAILED → VACANT_DIRTY + RE_CLEANING
12. re-clean complete → 새 inspection task
13. READY + OUT_OF_SERVICE check-in 거부
14. availability AVAILABLE 복귀 후 유효 예약 check-in
15. stayover manual create → PENDING + OCCUPIED
16. stayover start → STAYOVER_CLEANING
17. stayover BLOCKED → Room state 유지
18. stayover complete/cancel → OCCUPIED
19. inspection cancel → VACANT_DIRTY, READY 금지
20. 시작 전 task cancel → Room unchanged
21. readiness 필수 입력 누락 → UNKNOWN
22. 동일 seed/parameters → 동일 결과

## 9. 결과 보고 형식
```text
## 작업 요약
## 변경 파일
## 검증 (실행 명령과 실제 결과)
## 미실행 테스트와 이유
## 결정/가정/OPEN
## 문서 갱신
## 다음 단계
```

## 10. 첫 Claude Code 프롬프트
```text
현재 StayOps 저장소의 Phase 0 Repository reconnaissance만 수행한다.

먼저 docs/README.md를 읽고 현재 문서 버전 경로를 확인한 다음 docs/v3/의 문서 4개를 읽어라.

코드를 수정하거나 파일을 생성·삭제·이동하지 마라. Git init, dependency install, scaffold, migration 실행도 금지한다.

조사:
1. 실제 경로와 디렉터리 구조, Git 상태
2. Node/JDK/Kotlin/Python 버전
3. package manager/build tool
4. web/api/simulator scaffold 및 기존 명령
5. Docker/DB 및 migration 상태
6. 문서와 기존 코드 충돌
7. Room×Task lifecycle matrix 적용 여부
8. Room status와 availability 독립성 적용 여부
9. 검사 시작/완료 상태 전이
10. stayover 수동 생성 흐름
11. task cancel 및 blocker 복귀 처리
12. 다음 단계 변경 예상 파일 목록

사실과 추측을 분리하고, 미결정 사항은 OPEN으로 보고하라. 기존 파일 이동·삭제·수정은 승인받기 전 금지한다. Phase 0 보고 후 중단하고 사용자 확인을 기다려라.
```

## 11. 완료 선언
핵심 E2E, 서버 도메인 불변식, readiness 근거, 합성 데이터 식별, 시뮬레이션 재현성, 실행 테스트 기록, 배포 절차, 제품 가설의 한계가 확인되기 전에는 완료라고 표현하지 않는다.
