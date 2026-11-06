# 개발 가이드

코드를 고치거나 검증을 직접 돌려 볼 때 필요한 내용입니다. 실행만 해 보려면 [README의 빠른 시작](../README.md#빠른-시작)으로 충분합니다.

## 구조

```text
apps/web          Next.js 16 (App Router, TypeScript strict, Tailwind, TanStack Query) — pnpm
apps/api          Spring Boot 4 + Kotlin, JPA, Flyway, PostgreSQL 17 — Gradle wrapper
tools/simulator   데모 데이터셋 생성기 — Python 3.11 표준 라이브러리 + uv
infra/terraform   AWS 배포 구성 — 코드만, apply 안 함
docs/             설계 문서(v4.1이 현재 기준), ADR, 사용 설명서, QA, 배포·점검
```

## 설정

- `.env`는 docker compose만 읽습니다(선택, 기본값으로 동작). API는 환경 변수(`STAYOPS_DB_URL` 등)를 직접 읽습니다 — [`.env.example`](../.env.example).
- 웹을 다른 포트로 띄우면 API에 `STAYOPS_WEB_ALLOWED_ORIGINS=http://localhost:<port>`를 지정합니다. 웹이 부르는 API 주소는 `NEXT_PUBLIC_STAYOPS_API_BASE_URL`(기본 `http://localhost:8080`)입니다.
- `--spring.profiles.active=demo` 없이 실행하면 데이터가 없는 빈 DB로 시작합니다.
- 데모 데이터는 매일 새벽 4시(한국 시간)에 새로 만들어지고, 서버를 시작할 때도 확인합니다. 바로 처음 상태로 되돌리려면 `--stayops.demo.force-refresh-on-startup=true`를 붙여 API를 다시 띄웁니다. 설정은 `application.yml`의 `stayops.demo`.
- 상태 확인: `curl localhost:8080/actuator/health/readiness`

## 검증 명령

CI([`.github/workflows/ci.yml`](../.github/workflows/ci.yml))가 push(main)·PR마다 아래를 병렬로 실행합니다(배포 없음). action은 커밋 SHA로 고정했고 Dependabot이 주 1회 갱신 PR을 올립니다.

| 대상 | 명령 |
|---|---|
| API | `cd apps/api && ./gradlew build` (Testcontainers가 PostgreSQL을 띄우므로 Docker 필요) |
| Web | `cd apps/web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` |
| Simulator | `cd tools/simulator && uv run ruff check . && uv run ruff format --check . && uv run pytest` |
| E2E | `cd apps/web && pnpm exec playwright install chromium && pnpm test:e2e` (아래 준비 필요) |
| 운영 테스트 | 실행 중인 데모 서비스 대상 `cd apps/web && pnpm test:ops` — [실행 방법](qa/TEST_CASES.md#실행-방법) |
| API 이미지 | `docker build -t stayops-api apps/api` |
| Terraform | `cd infra/terraform && terraform init -backend=false && terraform validate` |
| 실행 중인 API smoke | `cd apps/web && STAYOPS_LIVE_API=http://localhost:8080 pnpm exec vitest run live` |

**E2E 준비:** E2E는 API를 `e2e` profile(포트 8081)로, 웹을 production build(포트 3200)로 직접 띄웁니다. API는 **전용 DB `stayops_e2e`를 매번 비우고** migration과 데모 데이터를 다시 넣으며, DB 이름이 `_e2e`로 끝나지 않으면 시작을 거부합니다. 전용 DB는 처음 한 번만 만듭니다(이미 있으면 오류만 나고 문제없음).

```bash
docker compose exec -T postgres createdb -U stayops stayops_e2e
```

## API 계약

- OpenAPI: [`docs/api/openapi.json`](api/openapi.json) — 서버 코드에서 생성한 스냅샷(ADR-006). 로컬 실행 중에는 `GET /v3/api-docs`.
- API를 바꿨다면 스냅샷과 FE 타입을 다시 만듭니다. 어긋나면 계약 테스트와 CI가 실패합니다.

```bash
cd apps/api && STAYOPS_WRITE_OPENAPI=true ./gradlew test --tests '*OpenApiContractTests*'
cd ../web && pnpm api:types
```

## 사용 설명서

앱의 `/guide`와 [`docs/USER_GUIDE.md`](USER_GUIDE.md)는 `apps/web/src/features/guide/content.ts` 하나에서 만들어집니다. 원본을 고친 뒤 Markdown을 다시 만듭니다.

```bash
cd apps/web && STAYOPS_WRITE_GUIDE=true pnpm test guide
```

## 데모 데이터셋과 시뮬레이션

```bash
cd tools/simulator
uv run stayops-simulator generate --seed 42 --out output/dataset-42.json   # 같은 seed·설정이면 같은 파일
uv run stayops-simulator validate output/dataset-42.json
uv run stayops-simulator summarize output/dataset-42.json
```

만든 파일은 웹의 **시뮬레이션** 화면에서 올립니다. 형식과 가정값은 [`docs/simulation/DATASET.md`](simulation/DATASET.md).

## 배포 구성

웹은 Vercel, API는 AWS ECS Fargate + RDS PostgreSQL + ALB. 스키마 migration은 별도 일회성 task로 실행합니다. 구성과 로컬 검증 결과는 [`docs/deploy/DEPLOYMENT.md`](deploy/DEPLOYMENT.md), 보안·접근성 점검은 [`docs/deploy/SECURITY_REVIEW.md`](deploy/SECURITY_REVIEW.md).
