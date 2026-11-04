# StayOps — 설계 빈틈 및 결정 기록

> v3 기준일: 2026-11-04  
> 목적: v2에서 발견된 7개 이슈와 후속 검토에서 추가된 5개 빈틈을 통합한다. 구현 기준은 `02_TECHNICAL_DESIGN.md`.

## 1. v2의 7개 이슈 처리 현황

| # | 이슈 | v3 반영 |
|---|---|---|
| 1 | check-in/out API 누락 | 명시적 command + transaction |
| 2 | PENDING blocker 불가 | PENDING/ASSIGNED/IN_PROGRESS 허용 |
| 3 | BLOCKED 복귀 상태 미정 | statusBeforeBlocked |
| 4 | 검사 흐름 불명확 | inspection task + record, 공통 start |
| 5 | 시뮬레이션 실행 위치 충돌 | 온라인 Kotlin, 오프라인 Python |
| 6 | stayover 상태 누락 | OCCUPIED → STAYOVER_CLEANING → OCCUPIED |
| 7 | 문서/저장소 경로 충돌 | docs/README.md + v1/v2 보존, v3 현행 |

## 2. 추가 발견된 5개 빈틈

### G8. 청소 완료 후 검사 상태 전환 시점
**충돌:** 일부 문구는 청소 완료 즉시 INSPECTING, 다른 문구는 검사 시작 시 INSPECTING으로 정의했다.

**규칙:** 청소 완료 직후 VACANT_DIRTY. 검사 task 생성/배정만으로 상태를 바꾸지 않는다. 검사자가 공통 `/start`를 실행할 때 INSPECTING. PASSED면 READY, FAILED면 VACANT_DIRTY + RE_CLEANING.

### G9. READY와 OUT_OF_SERVICE 의미 충돌
**충돌:** READY 조건에 availability 제한이 섞여 두 상태 축의 독립성이 약해졌다.

**규칙:** READY는 청소·검사 workflow 완료, OUT_OF_SERVICE는 판매/투숙 불가다. `READY + OUT_OF_SERVICE`는 유효하나 check-in 불가. 시설 복구는 availability만 변경하고 operationalStatus는 그대로 둔다.

### G10. Task BLOCKED/CANCELLED와 Room 상태 동기화
**충돌:** 작업 차단/취소/담당자 무효 때 객실 상태가 무엇인지 불명확했다.

**규칙:** Room은 workflow 단계, Task는 실행 상태. 진행 중 workflow에서 task가 BLOCKED 또는 PENDING으로 돌아가도 Room 상태를 유지한다. 취소 시 task type별 복귀 상태를 적용한다. 시작 전 취소는 Room 상태 불변. 상세 행렬은 `02_TECHNICAL_DESIGN.md` §5.4.

### G11. Stayover task 생성 주체
**충돌:** task type만 존재하고 생성 주체/조건/API가 없었다.

**규칙:** MVP는 운영자가 수동 생성. `POST /api/v1/rooms/{roomId}/stayover-cleanings`. 생성 시 Room OCCUPIED 유지, task PENDING. task start 시 STAYOVER_CLEANING. 자동 스케줄러 제외.

### G12. 문서 경로와 버전 보존
**충돌:** 루트 직하 문서 참조와 실제 docs/v1, docs/v2 저장 위치가 불일치할 수 있다.

**규칙:** docs/README.md를 진입점으로 두고 v1/v2 보존, v3 현행. 실제 저장소는 Phase 0에서 조사하고 승인 전 파일 이동 금지.

## 3. 통합 상태 규칙 요약

### Room
- checkout → VACANT_DIRTY
- checkout cleaning start → CLEANING
- cleaning complete → VACANT_DIRTY
- inspection start → INSPECTING
- inspection PASSED → READY
- inspection FAILED/cancel → VACANT_DIRTY
- check-in → OCCUPIED
- stayover start → STAYOVER_CLEANING
- stayover complete/cancel → OCCUPIED

### Availability
- AVAILABLE / OUT_OF_SERVICE는 RoomOperationalStatus와 독립.
- check-in은 READY AND AVAILABLE 모두 요구.
- availability 복구만으로 READY 전환 금지.

### Task
- 정상: PENDING → ASSIGNED → IN_PROGRESS → COMPLETED
- 차단: PENDING/ASSIGNED/IN_PROGRESS → BLOCKED → saved prior state
- 취소: PENDING/ASSIGNED/BLOCKED 가능, IN_PROGRESS는 MANAGER + reason
- COMPLETED/CANCELLED terminal.
- blocker가 남아 있으면 시작/완료 불가.

## 4. Room × Task matrix

| Type | Start | Blocked/PENDING 복귀 중 | Complete | Cancel after start |
|---|---|---|---|---|
| CHECKOUT_CLEANING | CLEANING | CLEANING 유지 | VACANT_DIRTY | VACANT_DIRTY |
| RE_CLEANING | CLEANING | CLEANING 유지 | VACANT_DIRTY | VACANT_DIRTY |
| INSPECTION | INSPECTING | INSPECTING 유지 | PASSED=READY / FAILED=VACANT_DIRTY | VACANT_DIRTY |
| STAYOVER_CLEANING | STAYOVER_CLEANING | STAYOVER_CLEANING 유지 | OCCUPIED | OCCUPIED |

시작 전 취소는 Room 상태를 변경하지 않는다. 취소된 작업은 재개하지 않고 필요하면 새 task를 생성한다.

## 5. API 및 DB 영향
- `POST /reservations/{id}/check-in`, `/check-out`
- `POST /tasks/{id}/cancel` with mandatory reason
- `POST /rooms/{id}/stayover-cleanings`
- 공통 `POST /tasks/{id}/start`
- `POST /tasks/{inspectionTaskId}/inspections`
- task당 활성 assignment partial unique index
- inspection task당 InspectionRecord unique
- checkout source reservation + type 중복 방지
- 활성 stayover task 객실별 unique
- Room/Reservation/Task version 또는 lock
- event와 도메인 상태 같은 transaction

## 6. E2E 누락 방지 체크
- [ ] cleaning complete 직후 VACANT_DIRTY
- [ ] inspection task 생성/배정만으로 INSPECTING 아님
- [ ] inspection start 때 INSPECTING
- [ ] PASSED READY와 OUT_OF_SERVICE가 독립
- [ ] READY + OUT_OF_SERVICE check-in 거부
- [ ] OUT_OF_SERVICE 해제만으로 READY 금지
- [ ] 청소 task BLOCKED 중 CLEANING 유지
- [ ] 담당자 무효 복귀 때 Room workflow 유지
- [ ] 시작 전 취소 Room 불변
- [ ] 진행 중 task 취소 시 type별 Room 복귀
- [ ] stayover 수동 생성·시작·차단·완료·취소
- [ ] 예약 CHECKED_IN 유지
- [ ] 모든 상태 변경 event transaction 원자성

## 7. 미결정 사항
1. 예약 중복 방지: PostgreSQL exclusion constraint vs transaction lock.
2. IN_PROGRESS 취소의 MANAGER 권한 범위 및 RBAC 상세.
3. MAINTENANCE blocker가 청소 전/중에 미치는 영향.
4. maintenance workflow/API를 MVP에 포함할지.
5. readiness 계산에서 blocker 병렬/직렬 관계 모델.
6. stayover cleaning 후 별도 inspection을 요구할지. MVP 기본은 검사 생략으로 제안.
7. task 생성 시 Room workflow 상태를 바꾸지 않는다는 원칙을 모든 유형에 일관 적용할지. v3는 start 시 전환을 기준으로 한다.

## 8. 설계 결정 이력

| 날짜 | ID | 결정/제안 | 상태 |
|---|---|---|---|
| 2026-11-04 | D-001 | 청소 완료 후 VACANT_DIRTY, 검사 start 시 INSPECTING | DECISION |
| 2026-11-04 | D-002 | READY와 availability 축 독립 | DECISION |
| 2026-11-04 | D-003 | Room workflow / Task execution 분리 및 lifecycle matrix | DECISION |
| 2026-11-04 | D-004 | stayover task 운영자 수동 생성 | DECISION |
| 2026-11-04 | D-005 | docs/v1,v2 보존, v3 현행 경로 | DECISION |
| 2026-11-04 | D-006 | 온라인 시뮬레이션 Kotlin, Python 오프라인 | DECISION |

> 이 문서의 결정은 StayOps 프로젝트의 구현 규칙이다. 실제 호텔의 운영 정책에 관한 사실 진술이 아니다.

## 9. v3 변경 이력
- v2의 7개 설계 이슈를 유지하고 해결 상태를 교차 참조로 정리.
- 추가 5개 빈틈(G8–G12)을 통합.
- Room, Task, Reservation 및 Availability 상태 규칙을 기술 설계와 일치시킴.
- API, DB 제약, E2E, 문서 경로를 함께 동기화.
