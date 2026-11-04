# 01. Product & Scope — StayOps (v4.1)

> v4 대비 변경: 역할 명칭(FRONT_DESK), 위험 등급 표현, 시나리오 C 보강, 시나리오 E(재정비) 추가, MVP 범위에 재정비 명령 추가. 상세는 `08_V4.1_CHANGE_LOG.md`.

## 1. 제품 정의

**StayOps — Room Readiness & Operations**는 호텔/레지던스 운영자가 객실 준비 상태, 청소·검사 작업, blocker, 담당자 부하를 한 흐름으로 파악하고 객실 준비 지연 위험의 근거를 확인하도록 돕는 운영 지원 제품이다.

핵심은 자동으로 현장을 통제하는 AI가 아니다. 운영자가 현재 상황과 대안을 이해하고 직접 결정하도록 돕는 **설명 가능한 workflow + decision support + deterministic simulation**이다.

### 제품 가설
객실·예약·작업·작업자·시설 상태를 일관된 도메인 모델로 연결하고, 준비 마감 시각 대비 예상 완료 시각 및 위험 원인을 설명하면 운영자가 위험 객실을 더 일찍 식별하고 작업 우선순위를 조정할 수 있을 것이다.

상기 내용은 `HYPOTHESIS`. 실제 어반스테이 현장에 동일 문제가 존재하거나 효과가 발생한다고 주장하지 않는다.

## 2. 대상 사용자와 Job-to-be-done

| 사용자 역할 | 달성하려는 일 | 필요한 정보/행동 | MVP 권한 가정 |
|---|---|---|---|
| 프런트/운영 담당자 (`FRONT_DESK`) | 체크인 전에 객실 준비 여부를 판단 | 도착 예정, 객실 상태, 준비 예상, blocker 확인 | 조회, 예약 check-in/out, stayover 요청·시작 전 취소 |
| 하우스키핑 담당자 (`HOUSEKEEPER`) | 맡은 작업을 정확히 수행하고 예외 보고 | 작업 목록, 객실/유형, 예정시간, blocker, 완료 | 본인 작업 조회·시작·차단·완료 |
| 검사 담당자 (`INSPECTOR`) | 청소 품질 확인 및 실패 사유 기록 | 검사 대기, 체크리스트, 결과 | 본인 검사 시작·결과 입력 |
| 운영 관리자 (`MANAGER`) | 인력/작업량/지연을 조정하고 예외를 복구 | 전체 현황, 재배정, 취소, OOS, 재정비 작업, 시뮬레이션 | 관리 명령. 작업 담당자로 배정되지는 않음 |

역할은 프로젝트용 단순 권한 모델이다. 실제 호텔 직무 체계와 동일하다고 보지 않는다.

## 3. 대표 사용자 시나리오

### 시나리오 A: 체크아웃 객실 준비
1. 예약 checkout 명령
2. 예약 CHECKED_OUT, 객실 VACANT_DIRTY, checkout cleaning task 생성
3. 담당자 배정
4. 작업 시작 시 객실 CLEANING
5. blocker 발생 시 task BLOCKED, 객실 CLEANING 유지
6. blocker 해제 후 task 실행 재개
7. 청소 완료 시 객실 VACANT_DIRTY
8. 검사 task 시작 시 INSPECTING
9. 검사 통과 시 READY
10. availability AVAILABLE이고 예약 검증을 통과하면 check-in 가능

### 시나리오 B: 위험 객실 판단
1. 운영자가 오늘 도착 예정 객실 목록을 본다.
2. 위험(AT_RISK)/주의(WARNING)/정상(ON_TRACK)/판정 불가(UNKNOWN)와 이미 준비 완료(READY)를 구분한다.
3. 각 위험 객실에서 예상 완료 시각과 계산 근거, blocker, 누락 데이터를 확인한다.
4. 운영자가 작업 순서/담당자/예상 소요를 변경한다.
5. 변경 전후 결과를 비교하고 실제 명령은 명시적으로 확정한다.

### 시나리오 C: 투숙 중 청소 요청
1. 프런트/운영자가 투숙 객실에서 stayover cleaning 요청을 생성한다.
2. 생성 시 예약은 CHECKED_IN, 객실은 OCCUPIED 유지, task는 PENDING.
3. 담당자 배정 및 시작 시 객실 STAYOVER_CLEANING.
4. 완료/취소 후 객실 OCCUPIED, 예약 CHECKED_IN 유지.
5. 체크아웃 시 아직 시작하지 않은 투숙 중 청소는 자동 취소된다. 이미 시작한 투숙 중 청소가 있으면 체크아웃이 거부되며, 화면은 "차단 해제 후 완료" 또는 "관리자 취소" 경로를 안내한다.

### 시나리오 E: 예외 후 재정비 (v4.1)
1. 체크아웃 청소가 시작된 뒤 취소되었거나, 검사가 시작된 뒤 취소되어 객실이 VACANT_DIRTY로 남는다.
2. 관리자가 객실 상세에서 사유를 입력하고 재청소 또는 재검사 작업을 만든다.
3. 재검사는 마지막 청소가 완료된 객실에서만 만들 수 있다. 화면은 "청소 필요"와 "검사 대기"를 구분해 보여준다.
4. 작업 생성만으로 객실 상태는 바뀌지 않는다. 작업이 시작될 때 바뀐다.

### 시나리오 D: 시설 문제
1. 관리자가 객실을 OUT_OF_SERVICE로 지정하고 사유/시각을 남긴다.
2. 이미 투숙 중인 예약을 자동 취소하거나 퇴실 처리하지 않는다.
3. 향후 check-in은 거부한다.
4. 복구 시 availability를 AVAILABLE로 바꾸되, 객실 operational status가 READY가 되는 것은 아니다.
5. 실제 점검/청소 필요 여부를 별도 확인한다.

## 4. MVP 기능 범위

### 포함
- property/room/reservation/staff/shift
- 예약 check-in/out
- 객실 operational status와 availability 분리
- checkout cleaning, re-cleaning, inspection, stayover cleaning
- task assignment/reassignment, blocker, cancel
- 관리자 재정비 작업(재청소/재검사) 생성
- room/task/reservation timeline 및 operational event
- readiness 산정과 설명
- 운영 대시보드 및 task board
- deterministic simulation 및 KPI 비교
- 합성 데이터 생성/seed 관리
- 역할 기반 demo access

### 제외
- 결제/정산, PMS 전체 대체, 실제 OTA/PMS 연동
- 고객 연락처/민감정보 저장, SMS/이메일 발송
- ML 예측 및 AI 자동 명령
- 자동 일일 stayover 청소 스케줄
- READY 객실을 다시 청소 대상으로 되돌리는 명령 (v4.2 후보)
- 실제 근무표/급여 시스템 연동
- 멀티테넌트 SaaS 운영 수준의 청구/계약 관리
- 복잡한 마이크로서비스/메시지 브로커

## 5. 제품 경계와 안전한 표현
- "예상 준비 시각"은 현재 입력과 규칙에 따른 추정이며 보장 시각이 아니다.
- UNKNOWN은 오류가 아니라 데이터 부족 상태다.
- 위험 등급은 configurable rule output이며 현장 표준/업계 기준이 아니다.
- 합성 데이터 성능은 실제 운영 성과가 아니다.
- 운영 명령은 사람이 실행하고 서버가 권한·상태를 검증한다.
- 자동 최적화, AI, 실시간 연동이 이미 구현된 것처럼 표현하지 않는다.

## 6. 성공 지표와 정의

| 지표 | 정의 | 해석 주의 |
|---|---|---|
| On-time readiness rate | 평가 대상 중 deadline 이전 READY가 된 객실 / 평가 대상 | 분모·취소 예약 처리 명시 |
| Mean lateness | 늦게 READY가 된 객실의 지연 분 평균 | 정시/조기 객실 제외 여부 명시 |
| Risk lead time | 위험 최초 탐지 시각과 readiness deadline 간 차이 | 음수/미탐지 처리 정의 |
| Unknown rate | 필수 입력 누락으로 판정 불가한 대상 / 평가 대상 | 낮다고 무조건 좋은 것은 아님 |
| Task completion rate | 완료 task / 완료 또는 취소 제외한 유효 task | task type별 분리 |
| Workload ratio | 근무 가능 분 대비 배정 예상 작업 분 | 이동/휴게 시간 미포함이면 표시 |
| Reassignment count | task당 assignment 변경 횟수 | 운영 품질의 단독 대리 지표 아님 |
| Rework rate | origin=MANAGER_REWORK 또는 INSPECTION_FAILED task / 전체 cleaning·inspection task | 합성 데이터에서는 생성 규칙의 결과일 뿐 품질 지표 아님 |

모든 KPI에 기간, property, task type, 분모, 취소/No-show 포함 여부를 표시한다.

## 7. 데스크 리서치 근거

1. 객실 청소 시간 영향 변수 연구: https://www.jiem.org/index.php/jiem/article/view/3441
2. 객실 정비 생산성·린넨 대기 연구: https://doi.org/10.1080/15256480.2021.1881941
3. 호텔 turnaround 인력 capacity planning: https://doi.org/10.1287/msom.2025.0395
4. Oracle OPERA Cloud: https://www.oracle.com/hospitality/hotel-property-management/
5. Mews Operations: https://help.mews.com/s/article/Mews-Operations
6. Mews Room Overview: https://help.mews.com/s/article/mobile-room-overview
7. 핸디즈 사업: https://handys.co.kr/business

연구 표본·산업·운영 조건이 다르므로 프로젝트 파라미터의 직접 근거로 쓰지 않는다.

## 8. 합성 데이터

초기 가정:
- 80 rooms: Standard 40, Deluxe 24, Suite 12, Family 4
- Housekeeper 6, Inspector 2
- 기본 checkout 11:00, check-in 15:00
- 청소 30–55분, stayover 15–30분, inspection 5–10분
- 린넨 blocker 0–20분, 시설 blocker 10–60분

전부 `ASSUMPTION`. 설정 파일로 분리하고 UI에 "합성 데이터" badge를 노출한다. seed, generator version, parameter snapshot을 저장한다.

## 9. 검증 전략(인터뷰 없이)
- 공개 연구/공식 제품 문서의 근거 한계 명시
- 업무 흐름/상태 전이 논리 검토
- 고정 seed 합성 시나리오
- 정상·예외·동시성·경계 자동 테스트
- 같은 입력/seed 결과 재현성
- readiness 계산의 설명 가능성 및 누락 입력 처리
- FE 사용성은 시나리오 기반 heuristic review로 한정하고 현장 검증으로 포장하지 않음

## 10. 미검증 가설과 질문
- 실제 객실 준비 지연 빈도/원인 분포
- 실제 작업자 배정 권한 및 변경 절차
- stayover 청소의 실제 정책
- inspection 적용 범위
- 시설 문제와 판매 중단의 연결 정책
- 객실 유형·점유율·체크인 시간대별 청소 시간 분포
- 현장 운영자가 설명 가능한 readiness 근거를 신뢰하는지

모두 `OPEN`. 추측으로 확정하지 않는다.
