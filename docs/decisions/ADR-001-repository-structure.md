# ADR-001: 저장소 구조

- 상태: Accepted
- 날짜: 2026-11-04
- 관련: v4.1 06 §5, v2 04 #7

## 맥락
초기 문서는 `stayops/` 루트를 전제했으나 실제 작업 디렉터리는 `handys/`이고, Phase 0 시점에 `docs/`만 있었으며 Git 저장소가 아니었다. 프론트엔드·백엔드·Python 도구가 한 저장소에서 같은 도메인 문서를 기준으로 개발된다.

## 결정
- `handys/`를 Git 저장소 루트로 사용한다 (`main` 브랜치).
- 단일 저장소(monorepo) 구조, 다만 언어별 빌드 도구를 하나로 통합하지 않는다.

```text
handys/
├── apps/
│   ├── web/              # Next.js (pnpm)
│   └── api/              # Spring Boot Kotlin (Gradle wrapper)
├── tools/
│   └── simulator/        # Python (uv)
├── infra/                # docker, terraform (필요 시점에 추가)
├── docs/                 # 설계 문서 v1~v4.1, decisions/
├── docker-compose.yml    # 로컬 PostgreSQL
├── .env.example
└── README.md
```

- JS 앱이 하나뿐이므로 pnpm workspace, Turborepo, Nx 등 monorepo 도구는 도입하지 않는다. 두 번째 JS 패키지(예: 공유 API 타입)가 생기면 재검토한다.
- 각 앱은 자기 디렉터리에서 독립적으로 build/test 한다. 루트 README에 전체 실행 순서를 기록한다.

## 대안
| 대안 | 기각 이유 |
|---|---|
| `handys/stayops/` 하위에 프로젝트 생성 | 문서와 코드 루트가 갈라지고 중첩 구조가 생김 |
| 앱별 별도 저장소 | 도메인 문서·API 계약 동기화 비용 증가, 과제 제출 단위가 분산됨 |
| pnpm workspace / Turborepo | 현재 JS 패키지 1개로 이점 없음 |

## 영향
- 경로 규칙이 문서(v4.1 06)와 일치한다.
- 루트 `.gitignore` 하나로 세 언어의 산출물을 관리한다.
