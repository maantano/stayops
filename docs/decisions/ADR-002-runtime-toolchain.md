# ADR-002: 런타임과 도구 버전

- 상태: Accepted
- 날짜: 2026-11-04
- 관련: v4.1 02 §1, 04, 08 §10

## 결정

| 영역 | 선택 | 고정 방법 | 근거 |
|---|---|---|---|
| JDK | 21 (LTS) | Gradle toolchain `languageVersion = 21` | 로컬에 OpenJDK 21.0.13 설치. Spring Boot 4.x 지원 범위 |
| Kotlin / Spring Boot | Spring Initializr 2026-11-04 기본 조합 (Spring Boot **4.1.2**, 생성된 Kotlin 버전) | `apps/api/build.gradle.kts` | 당일 최신 GA. SNAPSHOT/RC 제외 |
| 빌드 | Gradle wrapper (Kotlin DSL) | `gradle/wrapper/gradle-wrapper.properties` | 로컬 Gradle 설치 불필요 |
| Node | 22 LTS | `.nvmrc` | 로컬 nvm에 22.x 설치됨. 21(기본)은 비LTS |
| JS 패키지 매니저 | pnpm | `packageManager` 필드 | 로컬 설치(10.x), 빠른 설치·엄격한 의존성 |
| Frontend | Next.js App Router, TypeScript strict, Tailwind CSS, ESLint | `apps/web/package.json` lockfile | v4.1 03 |
| FE 테스트 | Vitest + React Testing Library | lockfile | v4.1 03 §9 |
| Python | 3.11+ | `tools/simulator/pyproject.toml` `requires-python`, `.python-version` | 로컬 3.11.6 |
| Python 도구 | uv, pytest, ruff | `uv.lock` | v4.1 06 §5 |
| DB | PostgreSQL 17 | `docker-compose.yml` 이미지 태그 | JSONB, partial unique index, exclusion constraint 지원 |
| 컨테이너 런타임 | OrbStack (Docker API 호환) | 로컬 설치 | Testcontainers 기본 소켓 경로 지원 |

### 이번 단계에서 도입하지 않는 것
TanStack Query, React Hook Form, Zod, shadcn/ui, Playwright, OpenAPI 생성기는 사용하는 Phase(P8 이후)에서 추가한다. 쓰지 않는 의존성을 미리 넣지 않는다.

## 대안
| 대안 | 기각 이유 |
|---|---|
| Spring Boot 3.5.x | 신규 프로젝트에서 이전 메이저를 쓸 이유 없음 |
| JDK 25 | 로컬 미설치, 21로 요구사항 충족 |
| npm / yarn | pnpm이 이미 설치되어 있고 lockfile 엄격성 우수 |
| H2 등 내장 DB로 테스트 | v4.1 05 §2: PostgreSQL과 동작이 다른 DB로 제약 테스트를 대체하지 않음 |

## 영향
- 백엔드 통합 테스트는 Docker(Testcontainers)가 실행 중이어야 한다.
- 버전 업그레이드는 새 ADR 또는 이 ADR의 개정 이력으로 기록한다.

## 개정 (2026-11-04, P8·P9)
- FE 데이터·폼: TanStack Query 5.104.5, openapi-fetch 0.17.0, openapi-typescript 7.13.0, React Hook Form 7.90.0, Zod 4.6.7, @hookform/resolvers 6.0.0 (확인 시점 기준 2주 이상 지난 안정 버전).
- **shadcn/ui는 도입하지 않는다.** MVP 화면은 Tailwind 유틸리티 + 네이티브 `<dialog>`(showModal의 포커스 가두기·Esc 닫기)로 충분하고, 컴포넌트 생성 CLI·Radix 의존성을 늘릴 이유가 없다. 디자인 시스템이 필요해지면 재검토한다(v4.1 01 §1 기술 스택 표와의 차이).
