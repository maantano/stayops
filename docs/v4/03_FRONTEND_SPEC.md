# 03. Frontend Specification

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
- 현재 객실 수와 operational status 분포
- AVAILABLE / OUT_OF_SERVICE 수
- 예정 check-in 및 checkout
- readiness: AT_RISK / ON_TRACK / UNKNOWN / READY
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
- 객실 상태: operational + availability 두 축
- 현재/다음 예약 및 시각
- 활성 task 및 담당자
- blocker와 예상 해제 정보
- readiness breakdown
- event timeline
- 사용 가능한 명령과 비활성 사유

### Task board
- filter: type, status, floor, staff, scheduled window
- sort: dueAt, priority, createdAt
- assignment history와 current assignment 구분
- blocker 추가/해제, start, complete, cancel
- cancel은 사유 필수, IN_PROGRESS 취소는 관리자 확인
- task가 BLOCKED일 때 활성 blocker 전체 표시

### Inspection
- 검사 task가 실제 start 되기 전까지 Room은 VACANT_DIRTY.
- inspection start 성공 후 INSPECTING 표시.
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
- 409 `VERSION_CONFLICT`: 최신 데이터 fetch, 사용자가 새 상태를 보고 다시 행동.
- 409 `INVALID_STATE`: action을 중단하고 서버 설명 표시.
- 403: 권한 안내, UI에서 숨겼더라도 서버 응답을 처리.
- 네트워크 timeout: command 결과가 불명확할 수 있으므로 같은 Idempotency-Key로 재시도.
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

## 7. Form validation
- React Hook Form + Zod.
- 사용자 입력은 UX validation, 서버 validation은 최종 권위.
- 시간 입력은 property timezone 기준으로 해석.
- duration은 양의 정수 및 합리적 상한.
- notes/reason 길이 제한 및 trim.
- FAILED inspection이면 failureReason required.
- cancel이면 reason required.
- OOS 변경이면 reason required.

## 8. 접근성/반응형
- 키보드로 주요 task command 수행 가능.
- dialog focus trap/restore, 명확한 accessible name.
- 상태를 색상만으로 구분하지 않음.
- 테이블은 좁은 화면에서 우선순위 열 유지 또는 카드 전환.
- 긴 작업 목록에서 loading skeleton/empty/error 분리.
- 화면 reader가 상태 변경 결과를 알 수 있도록 live region 사용.
- 시간/숫자 표기 일관성.

## 9. FE 테스트
- component tests: badge, forms, command dialog, blocker list, readiness explanation.
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
