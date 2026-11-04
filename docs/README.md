# StayOps 문서 인덱스

**현재 기준 버전: v4.1** (2026-11-04 승인)
이전 버전은 의사결정 이력 보존을 위해 유지하며 수정·삭제하지 않는다.

## 현재 기준 문서
1. `docs/v4.1/01_PRODUCT_AND_SCOPE.md` — 제품 정의, 사용자/업무 흐름, 범위, 가설
2. `docs/v4.1/02_DOMAIN_AND_API.md` — 도메인 모델, 상태/명령 규칙, API, 오류 코드, DB 불변식 (도메인 규칙 단일 기준)
3. `docs/v4.1/03_FRONTEND_SPEC.md` — FE 정보 구조, 화면/상태/상호작용/접근성
4. `docs/v4.1/04_BACKEND_SPEC.md` — BE 모듈, 트랜잭션, 동시성, 보안
5. `docs/v4.1/05_QA_STRATEGY.md` — 테스트 전략, 시나리오, 속성/경계/장애 테스트
6. `docs/v4.1/06_CLAUDE_CODE_HARNESS.md` — 진행 현황, 구현 절차, 작업 프롬프트
7. `docs/v4.1/07_CROSS_FUNCTIONAL_DEEP_DIVE.md` — 기획·FE·BE·QA 통합 리뷰, 잔여 리스크
8. `docs/v4.1/08_V4.1_CHANGE_LOG.md` — v4 → v4.1 변경 전후 비교와 근거

## 버전 정책
- `docs/v4.1/`만 현재 구현 기준이다.
- `docs/v4/`, `docs/v3/`, `docs/v2/`, `docs/v1/`은 이력이다. 충돌 시 v4.1이 우선한다.
- 문서 수정 시 README 인덱스, API, 상태표, FE/BE/QA 계약을 한 번에 동기화하고, 변경은 새 버전 디렉터리 또는 변경 이력으로 남긴다.
- 설계 결정은 `docs/decisions/ADR-NNN-*.md`에 기록한다.

## 상태 라벨
변경 관리: `APPROVED`, `PROPOSED`, `OPEN`, `REJECTED`
내용: `FACT`, `EVIDENCE`, `INTERPRETATION`, `HYPOTHESIS`, `ASSUMPTION`, `DECISION`, `IMPLEMENTED`, `VERIFIED`

## 구현 문서
- 진행 현황: `docs/v4.1/06_CLAUDE_CODE_HARNESS.md` §1.1 (P1~P12 완료)
- 스키마 `docs/schema/SCHEMA.md` · API 계약 `docs/api/openapi.json` · 합성 데이터 `docs/simulation/DATASET.md`
- 배포 구성 `docs/deploy/DEPLOYMENT.md` · 보안·접근성 점검 `docs/deploy/SECURITY_REVIEW.md` (실제 배포 안 함)

## 버전 이력
| 버전 | 내용 |
|---|---|
| v1 | 초기 문제 정의·기술 설계·하네스 |
| v2 | 7개 설계 충돌 정리 (PROPOSAL) |
| v3 | 설계 갭 결정 반영 |
| v4 | 기능 관점별(Product/FE/BE/QA) 문서 분리, Room×Task matrix |
| v4.1 | 재정비 명령, started 기준 통일, start 멱등·재시작, checkout×stayover, 오류 코드·역할·등급 통일, P2.5 |
