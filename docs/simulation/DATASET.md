# 합성 데이터셋 형식 (`gen-v1`)

- 생성기: `tools/simulator` (Python 3.11 표준 라이브러리만 사용)
- 설정: `tools/simulator/configs/default.toml` — **모든 값은 임의의 가정(ASSUMPTION)** 이며 업계 평균·실측치가 아니다
- 상태: `IMPLEMENTED` · `VERIFIED`(pytest 21개)
- 소비자: P11 시뮬레이션 — `POST /api/v1/properties/{id}/simulation-datasets`로 업로드(02 §9.1). 백엔드가 Python을 호출하지 않는다.
- 언어 간 계약: `configs/contract.toml` + seed 7 → `apps/api/src/test/resources/simulation/dataset-contract.json`. pytest가 생성기 출력과 바이트 일치를 확인하고, Kotlin API 테스트가 이 파일을 업로드해 실행한다.

```bash
cd tools/simulator
uv run stayops-simulator generate --seed 42 --out output/dataset-42.json   # output/은 git 제외
uv run stayops-simulator validate output/dataset-42.json
uv run stayops-simulator summarize output/dataset-42.json
```

## 재현성 계약
| 항목 | 규칙 |
|---|---|
| 같은 결과의 조건 | 같은 `seed` + 같은 설정(`configHash`) + 같은 `generatorVersion` → **바이트 단위로 같은 파일** (테스트로 검증) |
| 생성 시각 | 출력에 넣지 않는다(결정성) |
| 난수 스트림 | 용도별 독립(`reservations`, `guests`, `cleaning`, `inspection`, `inspection-failure`, `re-cleaning`, `linen`, `maintenance`, `stayover`, `rooms`). 한 확률을 바꿔도 다른 범주의 값은 그대로다(테스트로 검증) |
| id | `uuid5(seed 기반 namespace, 업무 키)` — 예: `reservation:305:2026-11-12`. 개수가 바뀌어도 다른 레코드 id가 밀리지 않는다 |
| 시각 | property 현지 날짜 + 기본 시각을 IANA timezone으로 UTC 변환(`...Z`). DST 전환에도 현지 15:00/11:00 유지(테스트로 검증) |

## 구조
```text
metadata   { synthetic: true, generatorVersion, seed, configHash, config(설정 원본), assumptions, horizon, counts }
property   { propertyId, name, timezone, defaultCheckInTime, defaultCheckOutTime, operationalBufferMinutes, synthetic }
rooms[]    { roomId, propertyId, roomNumber, roomType, floor, synthetic }
staff[]    { staffId, propertyId, displayName, role: HOUSEKEEPER|INSPECTOR, synthetic }
shifts[]   { shiftId, staffId, startsAt, endsAt, breakMinutes, synthetic }
reservations[] {
  reservationId, propertyId, roomId, roomNumber, roomType, checkInDate, nights,
  scheduledCheckInAt, scheduledCheckOutAt, guestCount, startsBeforeHorizon, synthetic,
  realization {                       # 시뮬레이션이 "실제로 일어났다"고 볼 표본
    checkoutCleaningMinutes,          # 균등[30,55] × 객실 유형 배수
    inspectionMinutes,                # 균등[5,10]
    inspectionFails,                  # Bernoulli(0.08)
    reCleaningMinutes?, reInspectionMinutes?,   # 실패 시에만. 재검사는 통과로 가정
    linenWaitMinutes,                 # 0 또는 균등[0,20] (확률 0.15)
    maintenanceBlockMinutes,          # 0 또는 균등[10,60] (확률 0.04)
    stayoverRequests[] { date, minutes }        # 연박 중 하루마다 확률 0.35, 균등[15,30]
  }
}
```
게스트 개인정보 필드는 없다.

## 생성 모델 (가정)
- **예약:** 객실마다 날짜를 따라가며, 빈 객실이 새 숙박을 시작할 확률 `p = occ / (E·(1−occ) + occ)` (E = 평균 박 수). 첫날에는 목표 점유율 확률로 이미 투숙 중인 숙박을 둔다(`startsBeforeHorizon`). 체크인은 기본 체크인 시각, 체크아웃은 기본 체크아웃 시각.
- **근무:** 매일 근무, 하우스키핑 시작 시각은 08/09/10시를 순환(4.3 연구의 "시작 시각 분산" 아이디어를 반영한 가정), 8시간, 휴게 30분. 휴무·결근은 모델링하지 않는다.
- **객실 유형 배수:** STANDARD 1.0 / DELUXE 1.1 / SUITE 1.3 / FAMILY 1.2 (4.1·4.2 연구의 "유형별 차이"를 반영한 가정).

## 기본 설정 관측값 (seed 0–9, 참고)
| 지표 | 값 |
|---|---|
| 점유율 | 평균 0.768 (0.731–0.786), 목표 0.78 — 첫날 아침에 끝나는 기존 투숙 때문에 약간 낮다 |
| 예약 수 | 평균 483 |
| 첫 검사 실패 | 평균 36.5건 |
| 하루 수요/근무 가능 분 최대 | 0.71 (이동·준비 시간 미반영) |

이 값은 합성 데이터의 성질이며 운영 성과가 아니다.

## 시뮬레이션 결과 예 (기본 설정, seed 42, 참고)
`sim-engine-v1` / `sim-policy-v1`, 평가 대상 도착 401건. 정시 준비율은 건수로 계산한 값을 한 번만 반올림한다(기준 327/401 = 81.546% → 81.5%). `[운영 테스트 수정]` 이전 기록 81.6%는 소수 넷째 자리 반올림값을 다시 반올림한 오류였다. 합성 데이터에 대한 상대 비교이며 운영 성과가 아니다.

| 정책 | 정시 준비율 | 지연 | 평균 지연 | p90 | 초과근무 |
|---|---|---|---|---|---|
| 기준: 먼저 생긴 작업부터 | 81.5% | 74건 | 54.2분 | 102분 | 18분 |
| 대안 A: 마감 급한 객실부터 | 96.3% | 15건 | 21.5분 | 37분 | 40분 |
| 대안 B: 교대 시작 09:00 통일 | 80.3% | 79건 | 52.1분 | 109분 | 0분 |
