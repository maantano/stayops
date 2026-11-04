# 보안·접근성 점검 (P12)

- 날짜: 2026-11-04 · 범위: `apps/api`, `apps/web`, `tools/simulator`, `infra/terraform`, git 이력
- 전제: 합성 데이터 데모. 인증은 ADR-005 데모 헤더(보안 수단 아님, ADR-007)

## 1. 결과 요약
| 영역 | 도구·방법 | 결과 | 조치 |
|---|---|---|---|
| 비밀 유출 (git 이력) | gitleaks 8.30.1 `git` (15 commits) | 0건 | — |
| 비밀 유출 (작업 디렉터리) | gitleaks `dir` | 13건 모두 `apps/web/.next/` 빌드 산출물의 Next preview key (git 제외 대상) | 조치 불필요 |
| FE 의존성 | `pnpm audit` | moderate 3 (`minimatch` ReDoS) — eslint·openapi-typescript 등 **개발 도구 전용**, 런타임 번들 미포함. 수정판은 major 변경(11.x)이라 상위 도구가 아직 미지원 | 수용, 상위 도구 갱신 시 해소 |
| 요청 본문 크기 | 코드 검토 | **결함**: JSON 본문이 권한 확인 전에 전부 파싱됨 → 큰 본문으로 메모리 소모 가능 | `RequestSizeLimitFilter` 추가: Content-Length 초과는 읽지 않고, chunked는 상한에서 읽기 중단 → 413 `PAYLOAD_TOO_LARGE`. 테스트 3개. FE 업로드도 같은 상한을 먼저 확인 |
| 웹 보안 헤더 | 설정 검토 + E2E | 헤더 없음 | `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`, production CSP(`frame-ancestors 'none'`, `connect-src` API만), `X-Powered-By` 제거. E2E로 확인 |
| IaC | checkov | 121 통과 · 0 실패 · 19 skip | 고친 것: SG 규칙 설명, 기본 SG 차단, RDS 로그 내보내기, migration task 읽기 전용 FS. skip은 데모 비용 사유를 코드에 기록 (DEPLOYMENT §6) |
| 컨테이너 | 로컬 실행 | non-root(`stayops`), JRE만 포함, healthcheck | — |

## 2. 설정 검토 체크리스트
| 항목 | 상태 | 근거 |
|---|---|---|
| SQL injection | ✓ | JPA·파라미터 바인딩만 사용, 문자열 SQL 조합 없음. `lock_timeout`은 설정값(사용자 입력 아님) |
| 오류 응답 정보 노출 | ✓ | Problem Details에 stack trace 없음, 매핑되지 않은 오류는 `INTERNAL_ERROR`(내부 정보 미노출), correlationId만 노출 |
| 다른 property 접근 | ✓ | 404로 존재 노출 방지 (ADR-005), 통합 테스트로 검증 |
| 역할 권한 | ✓ | 서버에서 최종 판단(`requireRole`), FE 숨김은 편의. 403 테스트 |
| 재전송·중복 요청 | ✓ | 모든 command Idempotency-Key 필수, 다른 payload는 409 |
| CORS | ✓ | 허용 origin 명시(`STAYOPS_WEB_ALLOWED_ORIGINS`), 배포 profile은 기본값 없음 |
| actuator | ✓ | `health`만 노출, `show-details: never` |
| API 문서 | ✓ | 배포 profile에서 기본 비활성 |
| DB 비밀번호 | ✓ | 로컬은 `.env.example`의 개발용 값만, 배포는 RDS 관리형 Secrets Manager(코드·state 미보관) |
| 전송 구간 암호화 | △ | RDS `rds.force_ssl=1` + `sslmode=require`. ALB HTTPS는 인증서 변수 지정 시 (데모는 선택) |
| 개인정보 | ✓ | 합성 데이터에 게스트 개인정보 필드 없음, 메모 입력란에 개인정보 금지 안내 |
| **인증** | ✗ (의도) | 데모 헤더 — 누구나 다른 직원으로 행세 가능. 공개 시 접근 제한 필수, 운영 전 OIDC (ADR-007) |
| rate limiting | ✗ | 미구현. 공개 시 WAF rate rule 또는 ALB 앞단에서 (DEPLOYMENT §6) |

## 3. 접근성 (05 §8)
| 확인 | 방법 | 결과 |
|---|---|---|
| 주요 화면 7개(사용자 선택, 대시보드, 객실, 객실 상세, 작업, 예약, 시뮬레이션) + 열린 dialog | Playwright + axe-core 4.11 (WCAG 2.0/2.1 A·AA) | serious·critical **0** |
| 키보드로 dialog 열기·Esc 닫기·포커스 복귀 | Playwright | **결함 발견·수정**: ×/취소 버튼·제출 성공처럼 부모가 dialog를 unmount하면 포커스가 trigger로 돌아오지 않음(Esc만 정상). `Dialog` unmount 시 포커스 복원 + 단위 회귀 테스트(수정 전 실패 확인) |
| 상태를 색만으로 표시하지 않음 | 코드 검토 | Badge가 항상 텍스트 라벨 + 화면 낭독용 접두어 |

남은 한계: 실제 화면 낭독기(VoiceOver 등) 수동 점검은 하지 않았다. axe는 자동 검출 가능한 규칙만 확인한다.
