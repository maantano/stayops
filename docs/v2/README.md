# StayOps 문서 패키지

StayOps는 호텔·레지던스의 객실 준비 및 하우스키핑 운영을 다루는 포트폴리오용 제품 프로젝트다.

## 문서 구성과 읽는 순서

1. `01_PRODUCT_DISCOVERY.md` — 문제 정의, 공개 근거, 제품 가설, 검증 한계
2. `02_TECHNICAL_DESIGN.md` — 도메인 모델, 상태 전이, API, 트랜잭션, 계산 규칙
3. `03_CLAUDE_CODE_HARNESS.md` — Claude Code 작업 원칙, 구현 단계, 테스트 및 첫 프롬프트
4. `04_SEVEN_DESIGN_GAPS.md` — 발견한 7개 설계 충돌의 원인·영향·권장안·수용 기준

## Claude Code 전달 순서

저장소 루트에 이 문서들을 `docs/`로 둔다. 첫 세션에서는 `03_CLAUDE_CODE_HARNESS.md`의 첫 프롬프트를 사용해 **Phase 0 조사만** 수행한다. 조사 결과를 확인하기 전에는 scaffold나 기존 파일 변경을 시작하지 않는다.

## 프로젝트 루트

현재 사용자의 작업 디렉터리 이름이 `handys/`인 점을 반영해, `handys/`를 저장소 루트로 취급한다. 다만 실제 Git 저장소 여부와 내부 구조는 Claude Code가 Phase 0에서 확인한다.

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

위 구조는 목표 구조이지 현재 실제 구조라고 단정하지 않는다. 기존 파일이 있으면 보존하고 차이를 먼저 보고한다.

## 문서 상태 표기

- `EVIDENCE`: 공개 자료로 확인한 사실
- `INTERPRETATION`: 근거를 제품 관점에서 해석한 내용
- `HYPOTHESIS`: 검증 전 제품 가설
- `ASSUMPTION`: MVP 구현을 위해 둔 임시 가정
- `PROPOSAL`: 권장하지만 사용자 승인 전인 설계안
- `DECISION`: 사용자 승인 또는 프로젝트에서 명시적으로 확정한 선택
- `IMPLEMENTED`: 코드 구현 완료
- `VERIFIED`: 테스트/재현 실행으로 검증 완료
- `OPEN`: 미결정 또는 미검증

## 중요한 주의

- 이 문서의 권장 설계는 사용자의 최종 승인 전까지 `PROPOSAL`이다.
- 공개 자료로 실제 어반스테이 내부 운영 방식이나 내부 시스템 기능을 추정하지 않는다.
- 합성 데이터 결과를 실제 호텔 운영 성과로 표현하지 않는다.
- 문서 변경 시 기존 이력을 지우지 말고 날짜가 있는 변경 이력을 추가한다.
