# 배포 가이드 (P12 — 구성만, 실제 배포하지 않음)

> **상태:** Dockerfile·Terraform·배포 profile은 작성·로컬 검증까지 했고, AWS/Vercel에 실제로 배포하지 않았다.
> 인증은 ADR-005 데모 헤더이며 보안 수단이 아니다. 배포는 **합성 데이터 공개 데모** 용도로만 한다 (ADR-007).

## 1. 구성
```text
브라우저 ──▶ Vercel (Next.js, apps/web)
   │  fetch (CORS, X-Demo-Staff-Id, Idempotency-Key)
   ▼
ALB (public subnet, 80→443) ──▶ ECS Fargate "api" 서비스 ×2 (apps/api 이미지, 8080)
                                   │  Secrets Manager: DB 사용자·비밀번호
                                   │  CloudWatch Logs: ECS JSON 로그(correlationId 포함)
                                   ▼
                               RDS PostgreSQL 17 (private subnet, 인터넷 경로 없음, SSL 강제)
ECS 일회성 task "migrate" (같은 이미지, profile aws,migrate) ──▶ Flyway
```

| 파일 | 내용 |
|---|---|
| `apps/api/Dockerfile` | multi-stage(JDK 21 빌드 → JRE 21 alpine), Spring Boot layer 분리, non-root, `HEALTHCHECK` liveness |
| `apps/api/src/main/resources/application-aws.yml` | 환경 변수 필수(기본값 없음), Flyway 끔, graceful shutdown, ECS 구조화 로그, api-docs 기본 끔 |
| `apps/api/src/main/resources/application-migrate.yml` | 웹 서버 없이 Flyway만 실행 후 종료 |
| `infra/terraform/` | VPC·SG·ALB·ECR·ECS(서비스+migration task)·RDS·IAM·CloudWatch |
| `apps/web/next.config.ts` | 보안 헤더, production CSP(`connect-src`는 API origin만) |

## 2. 환경 변수
### API (ECS task)
| 변수 | 필수 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ✓ | 서버 `aws`, migration `aws,migrate` (데모 데이터까지 `aws,migrate,demo`) |
| `STAYOPS_DB_URL` | ✓ | `jdbc:postgresql://<rds-endpoint>/stayops?sslmode=require` |
| `STAYOPS_DB_USER` / `STAYOPS_DB_PASSWORD` | ✓ | Secrets Manager(RDS 관리형 비밀)에서 주입. 코드·state에 남지 않음 |
| `STAYOPS_WEB_ALLOWED_ORIGINS` | ✓ | CORS 허용 origin(쉼표 구분). Vercel 주소 |
| `STAYOPS_DB_POOL_SIZE` |  | 기본 10. `task 수 × pool ≤ RDS max_connections` 유지 |
| `STAYOPS_MAX_REQUEST_SIZE` |  | 본문 상한, 기본 `5MB` (413 `PAYLOAD_TOO_LARGE`) |
| `STAYOPS_API_DOCS_ENABLED` |  | `/v3/api-docs` 공개 여부, 기본 false |
| `STAYOPS_DEMO_MODE` |  | 기본 true. 실제 인증 전에는 바꾸지 않는다(ADR-007) |

### Web (Vercel)
| 변수 | 설명 |
|---|---|
| `NEXT_PUBLIC_STAYOPS_API_BASE_URL` | Terraform output `api_url`. **build 시점에 들어간다** — 바꾸면 재배포. CSP `connect-src`도 이 값으로 만든다 |

## 3. 처음 배포 순서 (예시 — 실행하지 않음)
```bash
# 0) 사전: AWS 계정·자격 증명, (공개 시) ACM 인증서, Vercel 프로젝트
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # image_tag, web_origin, certificate_arn 채움
terraform init && terraform plan -out tfplan    # 처음엔 ECR만 먼저: terraform apply -target=aws_ecr_repository.api

# 1) 이미지 빌드·push (Fargate 아키텍처와 맞춘다: 기본 ARM64)
REPO=$(terraform output -raw ecr_repository_url); TAG=$(git rev-parse --short HEAD)
aws ecr get-login-password | docker login --username AWS --password-stdin "${REPO%/*}"
docker buildx build --platform linux/arm64 -t "$REPO:$TAG" ../../apps/api --push

# 2) 인프라 생성 (image_tag=$TAG)
terraform apply -var image_tag=$TAG

# 3) migration (서비스 갱신 전에 매번). 종료 코드 0을 확인한다
$(terraform output -raw migrate_command)        # 데모 데이터가 필요하면 task override로 profile aws,migrate,demo

# 4) Web: Vercel 프로젝트 Root Directory = apps/web, Install = pnpm install, Node 22
#    환경 변수 NEXT_PUBLIC_STAYOPS_API_BASE_URL = $(terraform output -raw api_url) 후 배포
```

### 이후 배포
1. 새 이미지 push(태그 = git SHA, ECR은 태그 변경 불가).
2. **migration task 먼저** — 스키마 변경은 항상 이전 버전 코드와 호환되게(컬럼 추가 → 코드 배포 → 정리 migration) 만든다. 서버는 `ddl-auto: validate`라 스키마가 앞서지 않으면 시작하지 않는다.
3. `terraform apply -var image_tag=<new>` → ECS rolling 배포(최소 100%, 최대 200%). 새 task가 ALB readiness를 통과하지 못하면 **deployment circuit breaker가 자동 rollback**.

## 4. 상태 확인·운영
| 항목 | 설정 |
|---|---|
| ALB health check | `/actuator/health/readiness` (DB 포함). 실패 시 트래픽 제외 |
| 컨테이너 health check | `/actuator/health/liveness`. 실패 시 ECS가 task 교체 |
| 종료 | `server.shutdown=graceful`, ECS `stopTimeout` 30초, ALB deregistration 30초 |
| 로그 | CloudWatch `/ecs/stayops-demo/api`, ECS JSON. 오류 응답의 `correlationId`로 검색 |
| actuator 노출 | `health`만 (`show-details: never`) |
| DB 잠금 | `lock_timeout 5s` (ADR-003). 초과 시 409 `VERSION_CONFLICT` |

## 5. 로컬에서 검증한 것 (2026-11-04)
| 확인 | 결과 |
|---|---|
| `docker build apps/api` | 성공, 이미지 416MB (arm64) |
| migration task (`aws,migrate,demo`, 빈 DB) | Flyway 5개 적용 + 데모 seed 후 스스로 종료(약 8초) |
| 서버 (`aws`) | readiness `UP`, `/v3/api-docs` 404(꺼짐), 실행 사용자 `stayops`(uid 100), 로그 ECS JSON |
| `terraform fmt -check`, `terraform validate` | 통과 (Terraform 1.17.1, AWS provider ~> 6.0) |
| checkov 정적 검사 | 121 통과 · 0 실패 · 19 skip(사유는 각 리소스 `checkov:skip` 주석) |
| Web production build + Playwright E2E | 7개 통과, CSP·보안 헤더 적용 상태에서 실행 |

검증하지 않은 것: `terraform plan/apply`(AWS 자격 증명 없음), Vercel 배포, 실제 HTTPS·도메인, 부하.

## 6. 데모 배포의 의도된 단순화 (운영 전환 시 바꿀 것)
| 단순화 | 운영 전환 |
|---|---|
| 데모 헤더 인증 | ADR-007의 OIDC 도입 단계 |
| HTTPS 선택(`certificate_arn` 비우면 HTTP) | 인증서 필수, HTTP는 리다이렉트만 |
| API task가 public subnet + 공인 IP(NAT 비용 회피, 인바운드는 ALB SG만) | private subnet + NAT 또는 VPC endpoint |
| RDS 단일 AZ, 삭제 보호 끔, 백업 7일 | Multi-AZ, 삭제 보호, 보존 기간 확대 |
| WAF·ALB access log·VPC flow log 없음 | WAF managed rules, 로그 버킷 |
| 로그 30일, AWS 관리형 암호화 | 보존 정책·KMS CMK 검토 |
| Terraform local state | S3 backend + lockfile |
| CI는 검증만(`.github/workflows/ci.yml`: 테스트·E2E·이미지 빌드·Terraform 검사·비밀 스캔), 배포 단계 없음 | CD 추가: OIDC로 AWS 역할 위임(장기 키 없음) → ECR push → migration task → `terraform apply`(environment 승인) |
