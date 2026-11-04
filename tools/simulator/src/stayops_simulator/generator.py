"""합성 데이터 생성기 (docs/v4.1/01 §8, 02 §2.2, 06 P10).

결정성 규칙
- 같은 seed + 같은 설정 → 바이트 단위로 같은 출력. 출력에 생성 시각을 넣지 않는다.
- 난수는 용도별 스트림으로 분리한다(`stream()`): 예컨대 투숙 중 청소 확률을 바꿔도 예약은 바뀌지 않는다.
- id는 seed와 업무 키로 만든 UUIDv5다. 개수가 바뀌어도 다른 레코드의 id가 밀리지 않는다.

시간 규칙
- 예약 시각은 property 현지 날짜 + 기본 체크인/체크아웃 시각을 IANA timezone으로 UTC instant로 바꾼다(DST 안전).
- 예약 구간은 `[checkIn, checkOut)` 이며 같은 객실에서 겹치지 않는다(체크아웃 11:00 → 같은 날 15:00 체크인 허용).

출력되는 모든 레코드는 합성 데이터이며 `synthetic: true`로 표시한다.
"""

from __future__ import annotations

import hashlib
import json
import random
import uuid
from datetime import UTC, date, datetime, time, timedelta

from .config import ROOM_TYPES, GeneratorConfig, describe

GENERATOR_VERSION = "gen-v1"


class _Streams:
    def __init__(self, seed: int) -> None:
        self.seed = seed
        digest = hashlib.sha256(f"stayops-simulator:{seed}".encode()).digest()
        self.namespace = uuid.UUID(bytes=digest[:16])
        self._cache: dict[str, random.Random] = {}

    def stream(self, name: str) -> random.Random:
        if name not in self._cache:
            self._cache[name] = random.Random(f"stayops:{self.seed}:{name}")
        return self._cache[name]

    def uid(self, key: str) -> str:
        return str(uuid.uuid5(self.namespace, key))


def _instant(day: date, at: time, config: GeneratorConfig) -> str:
    local = datetime.combine(day, at, tzinfo=config.zone)
    return local.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _uniform(rng: random.Random, low: int, high: int) -> int:
    return rng.randint(low, high)


def _stay_start_probability(config: GeneratorConfig) -> float:
    """빈 객실이 새 숙박을 시작할 확률 p. 점유율 = pE / (pE + 1 − p) 를 p에 대해 푼 값 (E = 평균 박 수)."""
    total = sum(w for _, w in config.stay_nights)
    mean = sum(n * w for n, w in config.stay_nights) / total
    occ = config.target_occupancy
    return occ / (mean * (1 - occ) + occ)


def _sample_nights(rng: random.Random, config: GeneratorConfig) -> int:
    nights, weights = zip(*config.stay_nights, strict=True)
    return rng.choices(nights, weights=weights, k=1)[0]


def generate(config: GeneratorConfig, seed: int) -> dict:
    s = _Streams(seed)
    property_id = s.uid("property")
    rooms = _rooms(config, s, property_id)
    staff = _staff(config, s, property_id)
    shifts = _shifts(config, s, staff)
    reservations = _reservations(config, s, property_id, rooms)
    _realize(config, s, rooms, reservations)

    return {
        "metadata": {
            "synthetic": True,
            "generatorVersion": GENERATOR_VERSION,
            "seed": seed,
            "configHash": config.config_hash(),
            "config": config.snapshot(),
            "assumptions": describe(config),
            "horizon": {
                "startDate": config.start_date.isoformat(),
                "days": config.days,
                "timezone": config.timezone,
            },
            "counts": {
                "rooms": len(rooms),
                "staff": len(staff),
                "shifts": len(shifts),
                "reservations": len(reservations),
            },
        },
        "property": {
            "propertyId": property_id,
            "name": config.property_name,
            "timezone": config.timezone,
            "defaultCheckInTime": config.check_in_time.isoformat("minutes"),
            "defaultCheckOutTime": config.check_out_time.isoformat("minutes"),
            "operationalBufferMinutes": config.operational_buffer_minutes,
            "synthetic": True,
        },
        "rooms": rooms,
        "staff": staff,
        "shifts": shifts,
        "reservations": reservations,
    }


def _rooms(config: GeneratorConfig, s: _Streams, property_id: str) -> list[dict]:
    rng = s.stream("rooms")
    types = [t for t in ROOM_TYPES for _ in range(config.rooms_by_type[t])]
    rng.shuffle(types)  # 유형을 층에 섞어 배치
    per_floor = -(-len(types) // config.floors)
    rooms = []
    for index, room_type in enumerate(types):
        floor = index // per_floor + 1
        number = f"{floor}{index % per_floor + 1:02d}"
        rooms.append(
            {
                "roomId": s.uid(f"room:{number}"),
                "propertyId": property_id,
                "roomNumber": number,
                "roomType": room_type,
                "floor": floor,
                "synthetic": True,
            }
        )
    return rooms


def _staff(config: GeneratorConfig, s: _Streams, property_id: str) -> list[dict]:
    staff = []
    for i in range(config.housekeepers):
        name = f"HK-{i + 1:02d}"
        staff.append(
            {
                "staffId": s.uid(f"staff:{name}"),
                "propertyId": property_id,
                "displayName": name,
                "role": "HOUSEKEEPER",
                "synthetic": True,
            }
        )
    for i in range(config.inspectors):
        name = f"IN-{i + 1:02d}"
        staff.append(
            {
                "staffId": s.uid(f"staff:{name}"),
                "propertyId": property_id,
                "displayName": name,
                "role": "INSPECTOR",
                "synthetic": True,
            }
        )
    return staff


def _shifts(config: GeneratorConfig, s: _Streams, staff: list[dict]) -> list[dict]:
    """매일 근무. 하우스키핑 시작 시각은 설정 목록을 순환해 분산한다 (휴무 모델은 MVP 범위 밖)."""
    shifts = []
    counters = {"HOUSEKEEPER": 0, "INSPECTOR": 0}
    for member in staff:
        starts = config.housekeeper_shift_starts if member["role"] == "HOUSEKEEPER" else config.inspector_shift_starts
        start = starts[counters[member["role"]] % len(starts)]
        counters[member["role"]] += 1
        for d in range(config.days):
            day = config.start_date + timedelta(days=d)
            begin = datetime.combine(day, start, tzinfo=config.zone)
            end = begin + timedelta(hours=config.shift_hours)
            shifts.append(
                {
                    "shiftId": s.uid(f"shift:{member['displayName']}:{day.isoformat()}"),
                    "staffId": member["staffId"],
                    "startsAt": begin.astimezone(UTC).isoformat().replace("+00:00", "Z"),
                    "endsAt": end.astimezone(UTC).isoformat().replace("+00:00", "Z"),
                    "breakMinutes": config.break_minutes,
                    "synthetic": True,
                }
            )
    return shifts


def _reservations(config: GeneratorConfig, s: _Streams, property_id: str, rooms: list[dict]) -> list[dict]:
    """객실마다 날짜를 따라가며 숙박을 만든다. 첫날에는 이미 투숙 중인 숙박(시작 이전 체크인)도 만든다."""
    rng = s.stream("reservations")
    guests_rng = s.stream("guests")
    p_start = _stay_start_probability(config)
    reservations = []
    for room in rooms:
        night = 0
        if rng.random() < config.target_occupancy:
            nights = _sample_nights(rng, config)
            already = rng.randint(1, nights)  # 시작 전 이미 지난 박 수
            night = _add_stay(config, s, guests_rng, property_id, room, -already, nights, reservations)
        while night < config.days:
            if rng.random() < p_start:
                night = _add_stay(
                    config,
                    s,
                    guests_rng,
                    property_id,
                    room,
                    night,
                    _sample_nights(rng, config),
                    reservations,
                )
            else:
                night += 1
    reservations.sort(key=lambda r: (r["scheduledCheckInAt"], r["roomNumber"]))
    return reservations


def _add_stay(config, s, guests_rng, property_id, room, start_night: int, nights: int, out: list) -> int:
    check_in_day = config.start_date + timedelta(days=start_night)
    check_out_day = check_in_day + timedelta(days=nights)
    out.append(
        {
            "reservationId": s.uid(f"reservation:{room['roomNumber']}:{check_in_day.isoformat()}"),
            "propertyId": property_id,
            "roomId": room["roomId"],
            "roomNumber": room["roomNumber"],
            "roomType": room["roomType"],
            "checkInDate": check_in_day.isoformat(),
            "nights": nights,
            "scheduledCheckInAt": _instant(check_in_day, config.check_in_time, config),
            "scheduledCheckOutAt": _instant(check_out_day, config.check_out_time, config),
            "guestCount": guests_rng.randint(1, config.max_guests[room["roomType"]]),
            "startsBeforeHorizon": start_night < 0,
            "synthetic": True,
        }
    )
    return start_night + nights


def _realize(config: GeneratorConfig, s: _Streams, rooms: list[dict], reservations: list[dict]) -> None:
    """예약마다 시뮬레이션(P11)이 쓸 '실제로 일어날 일' 표본. 범주마다 독립 스트림을 쓴다."""
    d, p = config.durations, config.probabilities
    cleaning, inspection, failure = (
        s.stream("cleaning"),
        s.stream("inspection"),
        s.stream("inspection-failure"),
    )
    rework, linen, maintenance, stayover = (
        s.stream("re-cleaning"),
        s.stream("linen"),
        s.stream("maintenance"),
        s.stream("stayover"),
    )
    for r in reservations:
        multiplier = config.room_type_multiplier[r["roomType"]]
        fails = failure.random() < p["inspection_failure"]
        realization = {
            "checkoutCleaningMinutes": round(
                _uniform(cleaning, d["checkout_cleaning"].low, d["checkout_cleaning"].high) * multiplier
            ),
            "inspectionMinutes": _uniform(inspection, d["inspection"].low, d["inspection"].high),
            "inspectionFails": fails,
            # 실패 시 재청소 후 재검사는 통과한다고 가정(ASSUMPTION). 값은 항상 뽑아 스트림 정렬을 유지한다.
            "reCleaningMinutes": None,
            "reInspectionMinutes": None,
            "linenWaitMinutes": 0,
            "maintenanceBlockMinutes": 0,
            "stayoverRequests": [],
        }
        re_minutes = round(_uniform(rework, d["re_cleaning"].low, d["re_cleaning"].high) * multiplier)
        re_inspection = _uniform(inspection, d["inspection"].low, d["inspection"].high)
        if fails:
            realization["reCleaningMinutes"] = re_minutes
            realization["reInspectionMinutes"] = re_inspection
        linen_hit, linen_minutes = (
            linen.random() < p["linen_shortage"],
            _uniform(linen, d["linen_wait"].low, d["linen_wait"].high),
        )
        if linen_hit:
            realization["linenWaitMinutes"] = linen_minutes
        maint_hit, maint_minutes = (
            maintenance.random() < p["maintenance_block"],
            _uniform(maintenance, d["maintenance_block"].low, d["maintenance_block"].high),
        )
        if maint_hit:
            realization["maintenanceBlockMinutes"] = maint_minutes
        check_in_day = date.fromisoformat(r["checkInDate"])
        for k in range(1, r["nights"]):
            asked, minutes = (
                stayover.random() < p["stayover_request"],
                _uniform(stayover, d["stayover_cleaning"].low, d["stayover_cleaning"].high),
            )
            if asked:
                realization["stayoverRequests"].append(
                    {"date": (check_in_day + timedelta(days=k)).isoformat(), "minutes": minutes}
                )
        r["realization"] = realization


def to_json(dataset: dict) -> str:
    """정규화된 JSON (키 정렬, UTF-8, 마지막 줄바꿈). 같은 dataset → 같은 바이트."""
    return json.dumps(dataset, sort_keys=True, ensure_ascii=False, indent=2) + "\n"
