# StayOps — Room Readiness & Operations

> **과제 제출 문서: [`docs/SUBMISSION.md`](docs/SUBMISSION.md)** — 무엇을 만들었고, 어떻게 진행했고, AI를 어떻게 활용·검증했는지 한 문서로 정리했습니다. 평가자를 위한 읽는 순서가 들어 있습니다.

호텔·레지던스에서 **"다음 손님이 도착하기 전에 이 객실이 준비될까?"**에 답하는 운영 지원 도구의 MVP입니다.
객실 상태, 청소·검사 작업, 차단 사유(린넨 부족·방해 금지 등), 담당자 부하를 한 흐름으로 묶고, 준비 지연 위험을 **근거와 함께** 보여 줍니다.

> **데모 데이터.** 실제 호텔 운영 데이터나 운영자 인터뷰 없이 공개 자료와 직접 만든 데모 데이터로 설계·검증했습니다. 결과를 실제 운영 성과로 표현하지 않습니다.
>
> **인증은 데모용입니다.** 화면에서 직원을 고르면 `X-Demo-Staff-Id` 헤더로 그 직원의 권한을 씁니다. 보안 수단이 아닙니다 ([ADR-005](docs/decisions/ADR-005-demo-authentication.md), [ADR-007](docs/decisions/ADR-007-auth-before-deployment.md)).
>
> **AI 활용 개발.** 설계 리뷰부터 구현·검증·문서화까지 AI 코딩 에이전트(Claude Code)와 단계별로 진행했습니다. 사람과 AI의 역할 분담, 단계별 작업, 검증 방법, AI 오류를 잡아낸 사례는 [`docs/process/AI_DEVELOPMENT_PROCESS.md`](docs/process/AI_DEVELOPMENT_PROCESS.md)에 있습니다.

## 주요 기능

| 화면 | 역할 | 내용 |
|---|---|---|
| 대시보드 | 관리자·프런트 | 손님 도착 전에 준비될지(준비 예측)와 근거, 객실 업무 단계별 현황, 오늘 도착·출발, 상태별 작업 수, 담당자별 업무량 |
| 객실 / 객실 상세 | 전체 | 운영 상태와 판매 상태(판매 중단)를 분리 표시, 진행 중 작업, 준비 예측 근거, 이벤트 타임라인 |
| 작업 보드 | 전체 (하우스키핑·검사는 본인 작업만) | 배정·시작·차단 등록/해제·완료·검사 결과 입력·취소 — 역할별로 가능한 버튼만 표시 |
| 예약 | 관리자·프런트 | 체크인·체크아웃. 투숙 중 청소가 진행 중이면 체크아웃을 거부하고 해결 방법 안내 |
| 시뮬레이션 | 관리자(실행)·프런트(조회) | 데모 데이터셋을 올려 작업 배정 정책 3종의 정시 준비율·지연·가동률 비교 |

**준비 예측:** 도착 예정 손님이 있는 객실마다 남은 일(퇴실 대기 → 청소 → 검사)이 끝나는 시각을 계산해 준비 마감(체크인 30분 전)과 비교합니다. 표시는 늦을 예정 · 빠듯함 · 여유 있음 · 준비 완료 · 예측 불가 5가지이고, 정보가 부족하면 짐작하지 않고 **예측 불가**로 보여 줍니다.

## 빠른 시작 (데모)

요구 환경: JDK 21 · Node 22(`.nvmrc`) + pnpm 10 · Python 3.11+ + uv · Docker(Compose v2 호환: OrbStack, Docker Desktop 등)

각 블록을 **저장소 루트에서, 별도 터미널로** 실행합니다.

```bash
# 터미널 1 — DB (PostgreSQL 17)
docker compose up -d
```
```bash
# 터미널 2 — API http://localhost:8080 (demo profile: 데모 데이터를 만들고 매일 새벽 4시에 새로 만든다)
cd apps/api && ./gradlew bootRun --args='--spring.profiles.active=demo'
```
```bash
# 터미널 3 — Web http://localhost:3000
cd apps/web && nvm use && pnpm install && pnpm dev
```

브라우저에서 http://localhost:3000 을 열고 직원을 고릅니다. `MGR-01 관리자`로 시작하면 모든 화면을 볼 수 있습니다.

**처음이라면 사용 설명서부터:** 앱의 http://localhost:3000/guide (첫 화면과 상단 '사용법' 링크) 또는 [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md). 역할별로 할 수 있는 일, 객실·작업 상태별로 다음에 할 일, 메뉴별 사용법, 자주 만나는 거부·오류와 해결 방법을 정리했습니다.

**데모 데이터:** 데모 데이터는 실제 command(체크아웃·배정·시작·차단·완료·검사)로 만들었으므로 이벤트 이력이 남아 있습니다. 준비 예측 5가지 표시가 모두 나옵니다. **매일 새벽 4시(한국 시간)에 지금 시각 기준으로 새로 만들어**, 오래 둬도 시각이 지난 낡은 데이터가 보이지 않습니다(서버를 시작할 때도 확인). 바로 처음 상태로 되돌리려면 API를 `--stayops.demo.force-refresh-on-startup=true`로 다시 띄웁니다.

| 객실 | 상태 | 확인해 볼 것 |
|---|---|---|
| 202 | 체크아웃 후 청소 미배정 | 관리자 배정 → 하우스키핑 시작·완료 → 검사 → 준비 완료 |
| 203 / 204 | 청소 중 / 린넨 부족으로 차단 | 차단 해제 예상 시각이 위험 계산에 반영됨 |
| 301 / 302 / 303 | 검사 대기 / 검사 중 / 검사 실패 → 재청소 대기 | 검사 실패 시 재청소 작업 자동 생성 |
| 304 | 해제 예상 없는 방해 금지(DND) 차단 | 준비 예측 "예측 불가" |
| 102 | 준비 완료지만 판매 중단 | 체크인 거부 → 관리자 판매 재개 |
| 104 | 투숙 중 청소 요청 | 청소 시작 후 체크아웃 시도 → 거부와 해결 방법 안내 |

기타
- `.env`는 docker compose만 읽습니다(선택, 기본값으로 동작). API는 환경 변수(`STAYOPS_DB_URL` 등)를 직접 읽습니다 — [`.env.example`](.env.example).
- 웹을 다른 포트로 띄우면 API에 `STAYOPS_WEB_ALLOWED_ORIGINS=http://localhost:<port>`를 지정합니다. 웹이 부르는 API 주소는 `NEXT_PUBLIC_STAYOPS_API_BASE_URL`(기본 `http://localhost:8080`)입니다.
- `--spring.profiles.active=demo` 없이 실행하면 데이터가 없는 빈 DB로 시작합니다.
- 상태 확인: `curl localhost:8080/actuator/health/readiness`

## 설계 핵심

| 주제 | 결정 | 근거 |
|---|---|---|
| 상태 모델 | 객실 **운영 상태**(청소 전·청소 중·검사 중·준비 완료·투숙 중…)와 **판매 상태**(판매 가능/중단)를 분리. 객실당 진행 중 작업 1개를 DB 부분 unique index로 보장 | [02 도메인·API](docs/v4.1/02_DOMAIN_AND_API.md) |
| 명령 처리 | 모든 command에 `Idempotency-Key` 필수(재전송 시 최초 응답 재생). 객실 → 예약 → 작업 순서의 비관적 잠금, `lock_timeout` 초과는 409 | [ADR-003](docs/decisions/ADR-003-command-locking.md) |
| 예약 겹침 | PostgreSQL exclusion constraint로 저장 시점에 차단 | [ADR-004](docs/decisions/ADR-004-reservation-overlap-constraint.md) |
| 상태 모순 | 저장된 상태끼리 맞지 않으면 조용히 보정하지 않고 `STATE_INCONSISTENT`로 거부·진단 로그 | [02 §7.1](docs/v4.1/02_DOMAIN_AND_API.md) |
| 오류 | RFC 9457 Problem Details + `code` + `correlationId`. FE는 코드별 UX 처리 방침을 하나씩 정함 | [03 FE 명세](docs/v4.1/03_FRONTEND_SPEC.md) |
| API 계약 | 서버 코드에서 OpenAPI 스냅샷 생성 → FE 타입 생성. 계약 테스트가 불일치 감지 | [ADR-006](docs/decisions/ADR-006-api-contract.md) |
| 시뮬레이션 | Python 생성기(seed 고정, 바이트 단위 재현)와 Kotlin 결정적 엔진. 두 언어는 계약 fixture로 연결 | [DATASET.md](docs/simulation/DATASET.md) |

## 검증 현황

| 대상 | 테스트 | 내용 |
|---|---|---|
| API | 234 | 스키마 제약, 상태 전이 판정 표, 동시 요청, 장애 주입 rollback, seed 고정 무작위 command 시퀀스 + DB 불변식 6종, 준비 예측 계산, OpenAPI 계약, 데모 데이터 새로 만들기 |
| Web | 43 (+ 실행 중인 API 대상 smoke 1) | API 클라이언트, 오류 코드별 처리, 역할별 버튼, 화면 컴포넌트 |
| Simulator | 22 | 재현성, 난수 스트림 독립성, 시간대(DST), 언어 간 계약 fixture |
| E2E | 8 | 핵심 흐름(배정 → 차단·해제 → 검사 실패 → 재청소 → 통과 → 체크인), 체크아웃 거부, 판매 중단 체크인 차단, 시뮬레이션, 키보드 dialog, axe(라이트·다크 모두 serious/critical 0), 보안 헤더 |
| 운영 테스트 | 15 | 실행 중인 데모 서비스에서 테스트 케이스 15개를 브라우저·API로 수행 — [케이스](docs/qa/TEST_CASES.md) · [결과 보고서](docs/qa/OPERATIONAL_TEST_REPORT.md) |

**개발 중 테스트로 찾아 고친 결함**
- 동시 재배정에서 시각 역전(배정 해제 시각 < 배정 시각): 명령 시각을 잠금 이후에 정함 (ADR-003 개정)
- 준비 예측 계산의 초 단위 반올림 불일치: 실행 중인 API 대상 smoke에서 발견
- dialog를 ×·취소로 닫을 때 포커스 미복귀: E2E에서 발견
- 요청 본문이 크기 제한 없이 파싱됨: 보안 점검에서 발견, 413 `PAYLOAD_TOO_LARGE`로 거절
- OS 다크 모드에서 카드 글자가 안 보임: 사람이 화면을 직접 보고 발견 → 다크 모드 axe 검사 추가
- 개발 모드에서 dialog가 열리지 않음(위 확인 중 발견, React StrictMode)
- 정시 준비율이 문서(81.6%)와 화면(81.5%)에서 달랐음: 운영 테스트에서 발견, 이중 반올림 → 건수로 한 번만 반올림
- 준비 안 된 객실도 체크인 버튼이 눌리고 서버의 영어 설명이 그대로 보임: 사람이 직접 써 보고 발견 → 예약에 객실 상태를 함께 내려 미리 안내, 오류 문구 한국어화

## 시뮬레이션 결과 (참고)

기본 설정(80실·14일) 데모 데이터, seed 42. **데모 데이터에 대한 정책 간 상대 비교이며 운영 성과가 아닙니다.**

| 정책 | 정시 준비율 | 평균 지연 | 초과근무 |
|---|---|---|---|
| 기준: 먼저 생긴 작업부터 | 81.5% | 54.2분 | 18분 |
| 대안 A: 마감 급한 객실부터 | 96.3% | 21.5분 | 40분 |
| 대안 B: 교대 시작 09:00 통일 | 80.3% | 52.1분 | 0분 |

```bash
cd tools/simulator
uv run stayops-simulator generate --seed 42 --out output/dataset-42.json   # 같은 seed·설정이면 같은 파일
uv run stayops-simulator validate output/dataset-42.json
uv run stayops-simulator summarize output/dataset-42.json
```
만든 파일은 웹의 **시뮬레이션** 화면에서 올립니다. 모든 설정값은 가정입니다 — [`docs/simulation/DATASET.md`](docs/simulation/DATASET.md).

## 검증 명령

CI([`.github/workflows/ci.yml`](.github/workflows/ci.yml))가 push(main)·PR마다 아래 전부를 병렬 job으로 실행합니다. 배포는 하지 않습니다. action은 커밋 SHA로 고정했고, Dependabot([`.github/dependabot.yml`](.github/dependabot.yml))이 주 1회 갱신 PR을 올립니다.

| 대상 | 명령 |
|---|---|
| API | `cd apps/api && ./gradlew build` (Testcontainers가 PostgreSQL을 띄우므로 Docker 필요) |
| Web | `cd apps/web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` |
| Simulator | `cd tools/simulator && uv run ruff check . && uv run ruff format --check . && uv run pytest` |
| E2E | `cd apps/web && pnpm exec playwright install chromium && pnpm test:e2e` (아래 준비 필요) |
| 운영 테스트 | 실행 중인 데모 서비스 대상 `cd apps/web && pnpm test:ops` — [실행 방법](docs/qa/TEST_CASES.md#실행-방법) |
| API 이미지 | `docker build -t stayops-api apps/api` |
| Terraform | `cd infra/terraform && terraform init -backend=false && terraform validate` |
| 실행 중인 API smoke | `cd apps/web && STAYOPS_LIVE_API=http://localhost:8080 pnpm exec vitest run live` |

**E2E 준비:** E2E는 API를 `e2e` profile로(포트 8081), 웹을 production build로(포트 3200) 직접 띄웁니다. 이때 API는 **전용 DB `stayops_e2e`를 매번 비우고** migration과 데모 seed를 다시 넣습니다. DB 이름이 `_e2e`로 끝나지 않으면 시작을 거부합니다. 전용 DB는 처음 한 번만 만듭니다(이미 있으면 오류만 나고 문제없음).
```bash
docker compose exec -T postgres createdb -U stayops stayops_e2e
```

## API 계약

- OpenAPI: [`docs/api/openapi.json`](docs/api/openapi.json) — 서버 코드에서 생성한 스냅샷. 로컬 실행 중에는 `GET /v3/api-docs`로도 볼 수 있습니다.
- API를 바꿨다면 스냅샷과 FE 타입을 다시 만듭니다. 이 둘이 어긋나면 CI가 실패합니다.
  ```bash
  cd apps/api && STAYOPS_WRITE_OPENAPI=true ./gradlew test --tests '*OpenApiContractTests*'
  cd ../web && pnpm api:types
  ```

## 배포 구성 (실제 배포 안 함)

웹은 Vercel, API는 AWS ECS Fargate + RDS PostgreSQL + ALB 구성입니다. 스키마 migration은 서버 시작 시가 아니라 별도 일회성 task로 실행합니다. Dockerfile과 Terraform을 작성해 로컬에서 검증까지 했고, 실제 배포는 하지 않았습니다.
→ [`docs/deploy/DEPLOYMENT.md`](docs/deploy/DEPLOYMENT.md) · 보안·접근성 점검 [`docs/deploy/SECURITY_REVIEW.md`](docs/deploy/SECURITY_REVIEW.md)

## 알려진 한계

- **인증:** 데모 헤더 방식입니다. 실제 인증(OIDC) 도입 단계는 [ADR-007](docs/decisions/ADR-007-auth-before-deployment.md)에 기록했습니다.
- **데이터:** 모든 데이터와 가정값(작업 소요시간, 준비 여유 30분 등)은 데모용으로 정한 값이며, 업계 표준이나 실측치가 아닙니다.
- **미결정 정책:** room move, no-show, 작업자 이동 시간 등은 [02 §11](docs/v4.1/02_DOMAIN_AND_API.md) OPEN 목록에 있습니다.
- **실행하지 못한 검증:** 실제 배포, GitHub에서의 CI 실행(원격 저장소 없음), 화면 낭독기 수동 점검.

## 구조와 문서

```text
apps/web          Next.js 16 (App Router, TypeScript strict, Tailwind, TanStack Query) — pnpm
apps/api          Spring Boot 4 + Kotlin, JPA, Flyway, PostgreSQL 17 — Gradle wrapper
tools/simulator   데모 데이터셋 생성기 — Python 3.11 표준 라이브러리 + uv
infra/terraform   AWS 배포 구성 — 코드만, apply 안 함
docs/             설계 문서(v4.1이 현재 기준), ADR, 스키마, 배포·점검
```

| 문서 | 내용 |
|---|---|
| [`docs/README.md`](docs/README.md) | 문서 인덱스, 버전 정책 |
| [`docs/v4.1/`](docs/v4.1/) | 01 제품·범위 · 02 도메인·API(규칙의 단일 기준) · 03 FE · 04 BE · 05 QA · 06 진행 현황 · 07 교차 검토 · 08 변경 이력 |
| [`docs/decisions/`](docs/decisions/) | ADR-001~007 |
| [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) | **사용 설명서** (앱의 `/guide`와 같은 원본에서 생성) |
| [`docs/qa/`](docs/qa/) | **테스트 케이스**와 **운영 테스트 결과 보고서**(증거 캡처 포함) |
| [`docs/schema/SCHEMA.md`](docs/schema/SCHEMA.md) | DB 스키마와 제약 |
| [`docs/process/AI_DEVELOPMENT_PROCESS.md`](docs/process/AI_DEVELOPMENT_PROCESS.md) | **AI 활용 개발 과정** — 단계별 작업, 사람·AI 역할 분담, 검증 방법, AI 오류 교정 사례 |
