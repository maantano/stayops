# StayOps v4.1

- 상태: **현재 구현 기준 (APPROVED 2026-11-04)**
- 기준: v4 + `08_V4.1_CHANGE_LOG.md`의 승인 항목. v4 원본은 `docs/v4/`에 보존한다.

## 문서
1. `01_PRODUCT_AND_SCOPE.md` — 제품 정의, 역할, 시나리오(A~E), 범위, 지표
2. `02_DOMAIN_AND_API.md` — 도메인 모델, 상태 전이, 명령, API, 오류 코드, DB 제약 (**도메인 규칙의 단일 기준**)
3. `03_FRONTEND_SPEC.md` — 화면, 상태 표현, command UX, 오류 매핑
4. `04_BACKEND_SPEC.md` — 모듈, 트랜잭션, lock, RBAC, 관측
5. `05_QA_STRATEGY.md` — 테스트 매트릭스, 동시성, E2E, 릴리스 게이트
6. `06_CLAUDE_CODE_HARNESS.md` — 진행 현황, 구현 순서(P0~P12, P2.5), 실행 프롬프트
7. `07_CROSS_FUNCTIONAL_DEEP_DIVE.md` — 교차 리뷰, 발견사항, 잔여 OPEN
8. `08_V4.1_CHANGE_LOG.md` — v4 → v4.1 변경 전후 비교, 근거, 교차 흐름 추적, 환경 조사

## v4 대비 핵심 변경
- 관리자 재정비(재청소/재검사) 명령 `POST /rooms/{id}/rework-tasks`
- "시작 전/후"는 모든 규칙에서 `actualStartedAt`으로 판정
- Task start 판정 표(멱등·재시작·상태 모순)
- 체크아웃 시 unstarted stayover 자동 취소 / started면 거부
- 객실당 active workflow task 1개 (partial unique index), Room 행 잠금으로 객실 단위 직렬화
- 역할 FRONT_DESK, 오류 코드 단일 목록, readiness 5등급, `workflowStage`
- 구현 순서에 P2.5 command 인프라 추가

## 상태 라벨
`APPROVED`, `PROPOSED`, `OPEN`, `REJECTED` (변경 관리) / `FACT`, `EVIDENCE`, `INTERPRETATION`, `HYPOTHESIS`, `ASSUMPTION`, `DECISION`, `IMPLEMENTED`, `VERIFIED` (내용)

## 변경 이력
| 날짜 | 내용 |
|---|---|
| 2026-11-04 | v4 패키지를 저장소 `docs/`로 복사 (v1/v2 보존) |
| 2026-11-04 | v4.1 변경 제안 초안 작성 |
| 2026-11-04 | 사용자 승인 (권장안 일괄), 01~07 갱신본 작성 |
