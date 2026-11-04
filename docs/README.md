# StayOps 문서 인덱스

**현재 기준 버전: v4**  
이전 버전은 의사결정 이력 보존을 위해 유지한다. v3는 이 패키지의 `docs/v3/`에 읽기 전용 참고본으로 포함했다. 사용자의 실제 저장소에 존재하는 v1/v2는 Claude Code가 조사 단계에서 확인하고 절대 임의 삭제·이동하지 않는다.

## 현재 기준 문서
1. `docs/v4/01_PRODUCT_AND_SCOPE.md` — 제품 정의, 사용자/업무 흐름, 범위, 가설
2. `docs/v4/02_DOMAIN_AND_API.md` — 도메인 모델, 상태/명령 규칙, API, DB 불변식
3. `docs/v4/03_FRONTEND_SPEC.md` — FE 정보 구조, 화면/상태/상호작용/접근성
4. `docs/v4/04_BACKEND_SPEC.md` — BE 모듈, 트랜잭션, 동시성, 보안, 오류 계약
5. `docs/v4/05_QA_STRATEGY.md` — 테스트 전략, 시나리오, 속성/경계/장애 테스트
6. `docs/v4/06_CLAUDE_CODE_HARNESS.md` — 구현 절차, 리뷰 게이트, 작업 프롬프트
7. `docs/v4/07_CROSS_FUNCTIONAL_DEEP_DIVE.md` — 기획·FE·BE·QA 통합 리뷰, 발견사항 및 잔여 리스크

## 버전 정책
- `docs/v4/`만 현재 구현 기준이다.
- `docs/v3/`는 비교·이력용이다. 충돌 시 v4가 우선한다.
- v1/v2는 실제 저장소에서 발견하면 보존한다. 이 패키지에 원본이 없다고 해서 존재하지 않는다고 간주하지 않는다.
- 문서 수정 시 README 인덱스, API, 상태표, FE/BE/QA 계약을 한 번에 동기화한다.

## 상태 라벨
`FACT`, `EVIDENCE`, `INTERPRETATION`, `HYPOTHESIS`, `ASSUMPTION`, `PROPOSAL`, `DECISION`, `OPEN`, `IMPLEMENTED`, `VERIFIED`.

## 첫 실행
Claude Code는 `06_CLAUDE_CODE_HARNESS.md`의 Phase 0만 수행한다. 코드 변경·Git 초기화·의존성 설치·파일 이동은 조사 결과 보고 및 사용자 승인 전 금지.
