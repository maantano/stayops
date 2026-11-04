# 03. Frontend Specification (v4.1)

> v4 대비 변경: readiness 5등급, `workflowStage` 표시, 재정비 생성 UI, 체크아웃 거부 안내, start 멱등 응답 처리, 오류 코드 매핑, 모든 command의 Idempotency-Key. 도메인 규칙은 `02_DOMAIN_AND_API.md`를 따른다.

## 1. FE 책임과 경계
- FE는 서버 도메인 상태를 임의로 계산/변경하지 않는다.
- readiness 계산과 command 허용 여부의 최종 판단은 BE.
- FE는 권한/상태에 따라 action을 숨기거나 비활성화할 수 있지만 보안 경계가 아니다.
- 모든 비동기 상태 변경은 pending/error/success를 명확히 표시한다.
- stale data, 409 conflict, 네트워크 오류를 정상적인 UX 경로로 처리한다.

## 2. 정보 구조
1. Dashboard
2. Rooms
3. Tasks
4. Reservations
5. Staff & Workload
6. Simulations
7. Activity / Event timeline
8. Settings (demo property/timezone/policy)

## 3. 화면 요구사항

### Dashboard
표시:
- 현재 객실 수와 `workflowStage` 분포 (NEEDS_CLEANING과 AWAITING_INSPECTION을 구분해 표시)
- AVAILABLE / OUT_OF_SERVICE 수
- 예정 check-in 및 checkout
- readiness: READY / ON_TRACK / WARNING / AT_RISK / UNKNOWN
- "담당자 없음 · 진행 중단" 경고 (started task가 PENDING인 객실, 서버 `alerts`의 `WORK_INTERRUPTED_NO_ASSIGNEE`)
- task: PENDING / ASSIGNED / IN_PROGRESS / BLOCKED
- 담당자별 예정 workload
- 기준 시각과 property timezone
- 합성 데이터 배지

행동:
- 위험 객실 선택 시 Room detail로 이동
- 위험 사유는 상위 1~3개 원인과 상세 근거 제공
- UNKNOWN은 위험/정상으로 합쳐 표시하지 않는다.

### Room detail
섹션:
- 객실 상태: operational + availability 두 축, 그리고 서버가 계산한 `workflowStage`
- 현재/다음 예약 및 시각
- 활성 task 및 담당자
- blocker와 예상 해제 정보
- readiness breakdown
- event timeline (task `origin`, 취소 코드 표시)
- 사용 가능한 명령과 비활성 사유
- 재정비 생성 (MANAGER에게만 노출):
  - "재청소 생성": `workflowStage=NEEDS_CLEANING`이고 active task가 없을 때 활성
  - "재검사 생성": `workflowStage=AWAITING_INSPECTION`이고 active task가 없을 때 활성
  - dialog: 사유(필수), 예상 소요(선택, 비우면 "설정 기본값" 표시), 예정 시각(선택), 담당자(선택)
  - 409 `ACTIVE_WORKFLOW_EXISTS`: 응답의 기존 task로 이동하는 링크 표시
- 체크아웃 거부(409 `STAYOVER_IN_PROGRESS`) dialog: 응답의 `blockingTask`(상태, 시작 시각, 담당자, 활성 blocker)를 보여주고 `resolutionOptions`에 있는 경로만 버튼으로 제공

### Task board
- filter: type, status, floor, staff, scheduled window
- sort: dueAt, priority, createdAt
- assignment history와 current assignment 구분
- blocker 추가/해제, start, complete, cancel
- cancel은 사유 필수. **한 번이라도 시작한 task(`actualStartedAt` 존재)는 status와 무관하게 관리자만 취소 가능**
- task가 BLOCKED일 때 활성 blocker 전체와 `statusBeforeBlocked` 표시
- start 응답 `alreadyStarted: true`는 성공으로 처리하고 별도 알림 없이 최신 상태로 갱신
- 재시작(이전에 시작했던 task)은 "재개"로 표시하고 최초 시작 시각을 유지해 보여줌

### Inspection
- 검사 task가 실제 start 되기 전까지 Room은 VACANT_DIRTY (`workflowStage=AWAITING_INSPECTION`).
- inspection start 성공 후 INSPECTING 표시.
- 검사 task에는 "완료" 버튼을 두지 않는다. 결과 제출(`/inspection-result`)만 제공한다.
- 결과 제출 전 checklist/결과 validation.
- FAILED는 failureReason 필수.
- 중복 submit 방지 및 성공 후 task/room 최신 상태 재조회.

### Simulation
- baseline과 scenario를 나란히 비교
- seed, snapshot, policy version, 파라미터 차이 표시
- 지표 분모/기간/취소 예약 처리 표시
- simulation 결과를 실제 운영 데이터와 시각적으로 혼동하지 않도록 "합성 데이터" 표시

## 4. 상태 표현 계약
- Room operational status와 availability status를 별도 badge/field로 표시.
- READY + OUT_OF_SERVICE를 단일 "준비 완료" badge로 축약하지 않는다.
- Room workflow status와 task execution status를 혼합하지 않는다.
- UNKNOWN, stale, loading, error는 서로 다른 상태다.
- 색상 단독 전달 금지: label/icon/text 병행.
- 날짜/시각은 property timezone 표시. API Instant를 브라우저 timezone으로 무심코 변환하지 않는다.

## 5. Command UX
- 명령 전 서버의 허용 상태를 확인하되, race condition을 전제로 처리한다.
- 성공 후 optimistic state를 영구 신뢰하지 말고 서버 응답으로 query cache 갱신.
- **모든 command에 Idempotency-Key 필수.** 사용자 행동(버튼 클릭/폼 제출) 1회마다 key를 생성하고, 같은 행동의 재시도에는 같은 key를 재사용한다.
- 네트워크 timeout: command 결과가 불명확할 수 있으므로 같은 Idempotency-Key로 재시도. 재시도 응답은 최초 결과이므로 성공 후에도 관련 query를 재조회한다.

오류 코드 매핑 (전체 목록은 02 §7.1):
| code | FE 처리 |
|---|---|
| VALIDATION_ERROR | 필드 오류 표시 |
| NOT_FOUND | "찾을 수 없음" 화면, 목록으로 이동 |
| FORBIDDEN | 권한 안내. UI에서 숨겼더라도 처리 |
| VERSION_CONFLICT | 최신 데이터 fetch, 사용자가 새 상태를 보고 다시 행동 |
| INVALID_STATE | action 중단, 서버 설명과 `currentStatus` 표시, 재조회 |
| ACTIVE_BLOCKER_EXISTS | 활성 blocker 목록 표시 |
| ASSIGNMENT_INVALID / SHIFT_CONFLICT | 배정 화면으로 안내 |
| ACTIVE_WORKFLOW_EXISTS | 기존 task 링크 표시 |
| STAYOVER_IN_PROGRESS | 체크아웃 거부 dialog (Room detail 참조) |
| IDEMPOTENCY_CONFLICT | 새 key로 다시 시도하도록 안내 (FE 버그 가능성 → 로그) |
| STATE_INCONSISTENT | "데이터 상태 확인 필요" 표시, correlationId 노출, 관리자 문의 안내. 재시도 버튼 없음 |
| UNAUTHENTICATED | 데모 사용자 선택 화면으로 이동 `[P2.5]` |
| REQUEST_NOT_SUPPORTED / INTERNAL_ERROR | 일반 오류 화면, correlationId 노출 `[P2.5]` |
- destructive command(cancel, OOS)는 사유/확인 dialog.
- 더블클릭 방지 및 pending indicator.

## 6. API query/cache
- TanStack Query 사용.
- query key에 propertyId/filter/sort/cursor 포함.
- command success 후 관련 room/task/reservation/dashboard/timeline query invalidate.
- 자동 polling은 dashboard/readiness에 제한적으로 적용. polling interval과 staleTime을 설정화.
- 실시간 websocket은 MVP 제외.
- pagination은 cursor 기반.
- 검색/필터 상태는 URL query에 반영 가능하게 설계.

### 6.1 `[P8 구현]` 데이터 계층 (`apps/web/src/api/`)
- `schema.d.ts`: `docs/api/openapi.json`에서 생성(`pnpm api:types`). 수정 금지.
- `endpoints.ts`: openapi-fetch 기반 typed 클라이언트. 데모 요청자 헤더 주입, command는 호출자가 준 Idempotency-Key 사용, Problem → `ApiError`, 응답 없음 → `NETWORK_ERROR`, 데모 사용자 미선택 → 요청하지 않고 `NO_DEMO_ACTOR`.
- `errors.ts`: `errorAction(error)`가 위 오류 매핑 표의 UX 방침을 반환. 화면은 이 값으로 분기한다.
- `queryKeys.ts`: `["stayops", propertyId, 자원, filter]`. command별 무효화 대상 표(`invalidationTargets`). dashboard·readiness 30초 polling, staleTime 10초.
- `hooks.ts`: 조회 hooks(목록은 infinite query), `useCommand(name)` → `run(...)`은 새 key, `retry()`는 **같은 key·같은 인자**로 재전송. 성공·VERSION_CONFLICT·INVALID_STATE 후 관련 query 무효화. mutation 자동 재시도 없음.
- `session.ts`: 데모 세션(staffId, propertyId)을 localStorage에 보관, 실패 시 메모리.
- query 재시도: 4xx는 재시도하지 않음, 네트워크·5xx만 1회.

## 7. Form validation
- React Hook Form + Zod.
- 사용자 입력은 UX validation, 서버 validation은 최종 권위.
- 시간 입력은 property timezone 기준으로 해석.
- duration은 양의 정수 및 합리적 상한.
- notes/reason 길이 제한 및 trim.
- FAILED inspection이면 failureReason required.
- cancel이면 reason required.
- OOS 변경이면 reason required.
- 재정비 생성이면 reason required (1~500자), scheduledAt은 과거 불가.

## 8. 접근성/반응형
- 키보드로 주요 task command 수행 가능.
- dialog focus trap/restore, 명확한 accessible name.
- 상태를 색상만으로 구분하지 않음.
- 테이블은 좁은 화면에서 우선순위 열 유지 또는 카드 전환.
- 긴 작업 목록에서 loading skeleton/empty/error 분리.
- 화면 reader가 상태 변경 결과를 알 수 있도록 live region 사용.
- 시간/숫자 표기 일관성.

## 9. FE 테스트
- component tests: badge(readiness 5종, workflowStage 7종), forms, command dialog, blocker list, readiness explanation, 체크아웃 거부 dialog, 재정비 생성 dialog.
- 오류 코드 매핑 표의 모든 code에 대한 렌더링 테스트.
- query tests: cache invalidation, pagination, filters, stale response.
- interaction tests: command success/error/409/timeout retry.
- Playwright: 대표 end-to-end workflow.
- visual regression은 핵심 dashboard/room/task 화면에서 선택 적용.
- 접근성 자동 검사 + 수동 keyboard smoke test.

## 10. FE 완료 기준
- 각 화면 loading/empty/error/success 구현
- 권한 없는 명령 비노출 + 서버 403 처리
- stale/409/timeout 안전 처리
- Room/Task/Availability 상태 독립 표시
- property timezone 정확 표시
- 키보드 주요 흐름
- API contract와 FE DTO 일치
