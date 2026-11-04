# StayOps — Room Readiness & Operations

호텔·레지던스의 객실 준비 상태, 청소·검사 작업, 차단 사유, 담당자 부하를 한 흐름으로 보여주고, 객실 준비 지연 위험의 근거를 설명하는 운영 지원 도구의 MVP 프로젝트입니다.

> **합성 데이터 데모.** 실제 호텔 운영 데이터나 운영자 인터뷰 없이 공개 자료와 합성 데이터로 설계·검증합니다. 결과를 실제 운영 성과로 표현하지 않습니다.

- 설계 문서: [`docs/README.md`](docs/README.md) (현재 기준 v4.1)
- 설계 결정(ADR): [`docs/decisions/`](docs/decisions/)
- 배포 구성(실제 배포 안 함): [`docs/deploy/DEPLOYMENT.md`](docs/deploy/DEPLOYMENT.md) · 보안·접근성 점검: [`docs/deploy/SECURITY_REVIEW.md`](docs/deploy/SECURITY_REVIEW.md)

> **인증은 데모용입니다.** `X-Demo-Staff-Id` 헤더로 직원을 고르는 방식이며 보안 수단이 아닙니다 (ADR-005, ADR-007).

## 구조

```text
apps/web          Next.js (App Router, TypeScript strict, Tailwind) — pnpm
apps/api          Spring Boot 4 + Kotlin, JPA, Flyway, PostgreSQL — Gradle wrapper
tools/simulator   합성 데이터·오프라인 시뮬레이션 — Python + uv
infra/terraform   AWS 배포 구성 (ECS Fargate·RDS·ALB) — 코드만, apply 안 함
docs/             설계 문서와 ADR
```

## 요구 환경

| 도구 | 버전 |
|---|---|
| JDK | 21 |
| Node | 22 (`.nvmrc`) + pnpm 10 |
| Python | 3.11+ + uv |
| Docker | Compose v2 호환 런타임 (OrbStack, Docker Desktop 등) |

## 로컬 실행

```bash
# 1. DB
cp .env.example .env          # 선택: 기본값으로도 동작
docker compose up -d

# 2. API (http://localhost:8080)
cd apps/api
./gradlew bootRun
curl localhost:8080/actuator/health            # {"status":"UP"}
curl localhost:8080/actuator/health/readiness

# 3. Web (http://localhost:3000)
cd apps/web
nvm use && pnpm install
pnpm dev

# 4. Simulator
cd tools/simulator
uv sync && uv run stayops-simulator
```

## 합성 데이터 생성

```bash
cd tools/simulator
uv run stayops-simulator generate --seed 42 --out output/dataset-42.json
uv run stayops-simulator validate output/dataset-42.json
uv run stayops-simulator summarize output/dataset-42.json
```
같은 seed·설정이면 같은 파일이 나온다. 모든 값은 가정이다 — [`docs/simulation/DATASET.md`](docs/simulation/DATASET.md).

## 데모로 화면 보기

```bash
docker compose up -d
cd apps/api && ./gradlew bootRun --args='--spring.profiles.active=demo'   # 데모 호텔이 없으면 합성 데이터를 만든다
cd apps/web && pnpm dev                                                   # http://localhost:3000 → 데모 사용자 선택
```
- 데모 데이터는 실제 command(체크아웃·배정·시작·차단·완료·검사)로 상태를 만들어 이벤트 이력이 남는다. 준비 위험 5등급이 모두 나오도록 구성했다.
- 웹을 다른 포트로 띄우면 API에 `STAYOPS_WEB_ALLOWED_ORIGINS=http://localhost:<port>`를 지정한다. API 주소는 `NEXT_PUBLIC_STAYOPS_API_BASE_URL`(기본 `http://localhost:8080`).
- 실행 중인 API에 대한 FE 클라이언트 smoke: `cd apps/web && STAYOPS_LIVE_API=http://localhost:8080 pnpm exec vitest run live`

## 검증 명령

| 대상 | 명령 |
|---|---|
| API | `cd apps/api && ./gradlew build` (단위·통합 테스트 포함, Testcontainers로 PostgreSQL을 띄우므로 Docker 실행 필요) |
| Web | `cd apps/web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` |
| Simulator | `cd tools/simulator && uv run ruff check . && uv run ruff format --check . && uv run pytest` |
| E2E (Playwright) | `cd apps/web && pnpm exec playwright install chromium && pnpm test:e2e` — 아래 참고 |
| 이미지 | `docker build -t stayops-api apps/api` |
| Terraform | `cd infra/terraform && terraform init -backend=false && terraform validate` |

E2E는 API를 `e2e` profile(포트 8081, **전용 DB `stayops_e2e`를 매번 비우고** migration + 데모 seed)로, 웹을 production build(포트 3200)로 직접 띄운 뒤 화면으로 핵심 흐름·예외 흐름·시뮬레이션·접근성(axe serious/critical 0)·보안 헤더를 확인한다. 처음 한 번 DB를 만든다:
```bash
docker compose exec -T postgres createdb -U stayops stayops_e2e
```
e2e profile은 DB 이름이 `_e2e`로 끝나지 않으면 시작을 거부한다.

## API 계약

- OpenAPI: [`docs/api/openapi.json`](docs/api/openapi.json) — 서버 코드에서 생성한 스냅샷 (ADR-006). 실행 중이면 `GET /v3/api-docs`.
- API를 바꿨다면:
  ```bash
  cd apps/api && STAYOPS_WRITE_OPENAPI=true ./gradlew test --tests '*OpenApiContractTests*'
  cd ../web && pnpm api:types
  ```

## 진행 상태

[`docs/v4.1/06_CLAUDE_CODE_HARNESS.md`](docs/v4.1/06_CLAUDE_CODE_HARNESS.md) §1.1 참고.
