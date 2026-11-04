"""생성된 합성 데이터의 검증과 오프라인 요약 분석.

요약 지표는 **수요 측** 값이다(하루에 생길 청소·검사 작업 분 vs 근무 가능 분). 실제 처리 결과(정시 준비율 등)는
정책을 적용해 실행하는 시뮬레이션(P11)의 몫이다. 모든 수치는 합성 데이터에 대한 것이며 운영 성과가 아니다.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from datetime import date, datetime, timedelta

from .config import GeneratorConfig


def _parse(instant: str) -> datetime:
    return datetime.fromisoformat(instant.replace("Z", "+00:00"))


def validate(dataset: dict, config: GeneratorConfig) -> list[str]:
    """데이터 불변식 위반 목록 (비어 있으면 통과)."""
    problems: list[str] = []
    meta = dataset["metadata"]
    if meta.get("synthetic") is not True:
        problems.append("metadata.synthetic must be true")
    for section in ("rooms", "staff", "shifts", "reservations"):
        if any(item.get("synthetic") is not True for item in dataset[section]):
            problems.append(f"every {section} record must be marked synthetic")

    by_type = Counter(r["roomType"] for r in dataset["rooms"])
    if dict(by_type) != {t: n for t, n in config.rooms_by_type.items() if n}:
        problems.append(f"room counts {dict(by_type)} != config {config.rooms_by_type}")
    if len({r["roomNumber"] for r in dataset["rooms"]}) != len(dataset["rooms"]):
        problems.append("room numbers must be unique")
    roles = Counter(s["role"] for s in dataset["staff"])
    if roles["HOUSEKEEPER"] != config.housekeepers or roles["INSPECTOR"] != config.inspectors:
        problems.append(f"staff counts {dict(roles)} do not match config")

    # 예약: [in, out) 구간, 같은 객실 겹침 없음, 현지 기본 시각
    by_room: dict[str, list[tuple[datetime, datetime]]] = defaultdict(list)
    for r in dataset["reservations"]:
        check_in, check_out = _parse(r["scheduledCheckInAt"]), _parse(r["scheduledCheckOutAt"])
        if not check_in < check_out:
            problems.append(f"reservation {r['reservationId']} has check-in >= check-out")
        local_in, local_out = check_in.astimezone(config.zone), check_out.astimezone(config.zone)
        if (local_in.hour, local_in.minute) != (
            config.check_in_time.hour,
            config.check_in_time.minute,
        ):
            problems.append(f"reservation {r['reservationId']} check-in is not at the default local time")
        if (local_out.hour, local_out.minute) != (
            config.check_out_time.hour,
            config.check_out_time.minute,
        ):
            problems.append(f"reservation {r['reservationId']} check-out is not at the default local time")
        by_room[r["roomId"]].append((check_in, check_out))
        problems.extend(_realization_problems(r, config))
    for room_id, intervals in by_room.items():
        intervals.sort()
        for (_, prev_out), (next_in, _) in zip(intervals, intervals[1:], strict=False):
            if next_in < prev_out:
                problems.append(f"room {room_id} has overlapping reservations")

    # 근무: 같은 직원 겹침 없음
    by_staff: dict[str, list[tuple[datetime, datetime]]] = defaultdict(list)
    for sh in dataset["shifts"]:
        by_staff[sh["staffId"]].append((_parse(sh["startsAt"]), _parse(sh["endsAt"])))
    for staff_id, intervals in by_staff.items():
        intervals.sort()
        for (_, prev_end), (next_start, _) in zip(intervals, intervals[1:], strict=False):
            if next_start < prev_end:
                problems.append(f"staff {staff_id} has overlapping shifts")
    return problems


def _realization_problems(r: dict, config: GeneratorConfig) -> list[str]:
    d, z = config.durations, r["realization"]
    m = config.room_type_multiplier[r["roomType"]]
    out = []

    def within(value: int | None, low: float, high: float, label: str) -> None:
        if value is None or not round(low) <= value <= round(high):
            out.append(f"reservation {r['reservationId']} {label}={value} outside [{low}, {high}]")

    within(
        z["checkoutCleaningMinutes"],
        d["checkout_cleaning"].low * m,
        d["checkout_cleaning"].high * m,
        "checkoutCleaningMinutes",
    )
    within(z["inspectionMinutes"], d["inspection"].low, d["inspection"].high, "inspectionMinutes")
    if z["inspectionFails"]:
        within(
            z["reCleaningMinutes"],
            d["re_cleaning"].low * m,
            d["re_cleaning"].high * m,
            "reCleaningMinutes",
        )
    elif z["reCleaningMinutes"] is not None:
        out.append(f"reservation {r['reservationId']} has re-cleaning without a failed inspection")
    if z["linenWaitMinutes"]:
        within(z["linenWaitMinutes"], d["linen_wait"].low, d["linen_wait"].high, "linenWaitMinutes")
    if z["maintenanceBlockMinutes"]:
        within(
            z["maintenanceBlockMinutes"],
            d["maintenance_block"].low,
            d["maintenance_block"].high,
            "maintenanceBlockMinutes",
        )
    if len(z["stayoverRequests"]) > max(0, r["nights"] - 1):
        out.append(f"reservation {r['reservationId']} has more stayover requests than stay days")
    return out


def summarize(dataset: dict, config: GeneratorConfig) -> dict:
    """하루 단위 수요 요약: 점유 객실-박, 도착·출발, 청소·검사 수요 분, 근무 가능 분, 수요/가능 비율."""
    days = [config.start_date + timedelta(days=i) for i in range(config.days)]
    occupied = Counter()
    arrivals = Counter()
    departures = Counter()
    demand = Counter()
    for r in dataset["reservations"]:
        check_in_day = date.fromisoformat(r["checkInDate"])
        for k in range(r["nights"]):
            occupied[check_in_day + timedelta(days=k)] += 1
        arrivals[check_in_day] += 1
        out_day = check_in_day + timedelta(days=r["nights"])
        departures[out_day] += 1
        z = r["realization"]
        demand[out_day] += (
            z["checkoutCleaningMinutes"]
            + z["inspectionMinutes"]
            + (z["reCleaningMinutes"] or 0)
            + (z["reInspectionMinutes"] or 0)
        )
        for req in z["stayoverRequests"]:
            demand[date.fromisoformat(req["date"])] += req["minutes"]

    capacity = Counter()
    for sh in dataset["shifts"]:
        start, end = _parse(sh["startsAt"]), _parse(sh["endsAt"])
        capacity[start.astimezone(config.zone).date()] += int((end - start).total_seconds() // 60) - sh["breakMinutes"]

    total_rooms = len(dataset["rooms"])
    per_day = []
    for day in days:
        per_day.append(
            {
                "date": day.isoformat(),
                "occupiedRooms": occupied[day],
                "occupancy": round(occupied[day] / total_rooms, 4),
                "arrivals": arrivals[day],
                "departures": departures[day],
                "workDemandMinutes": demand[day],
                "staffCapacityMinutes": capacity[day],
                "demandToCapacity": round(demand[day] / capacity[day], 4) if capacity[day] else None,
            }
        )
    room_nights = sum(d["occupiedRooms"] for d in per_day)
    return {
        "synthetic": True,
        "seed": dataset["metadata"]["seed"],
        "generatorVersion": dataset["metadata"]["generatorVersion"],
        "configHash": dataset["metadata"]["configHash"],
        "note": (
            "Demand-side summary of synthetic data. Not operational results; see simulation (P11) for policy outcomes."
        ),
        "overall": {
            "reservations": len(dataset["reservations"]),
            "occupancy": round(room_nights / (total_rooms * len(days)), 4),
            "targetOccupancy": config.target_occupancy,
            "inspectionFailures": sum(1 for r in dataset["reservations"] if r["realization"]["inspectionFails"]),
            "stayoverRequests": sum(len(r["realization"]["stayoverRequests"]) for r in dataset["reservations"]),
            "peakDemandToCapacity": max((d["demandToCapacity"] or 0) for d in per_day),
        },
        "days": per_day,
    }
