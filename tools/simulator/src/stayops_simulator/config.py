"""생성 설정 로딩·검증. 모든 값은 ASSUMPTION이며 검증만 하고 해석하지 않는다."""

from __future__ import annotations

import hashlib
import json
import tomllib
from dataclasses import asdict, dataclass, field
from datetime import date, time
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOM_TYPES = ("STANDARD", "DELUXE", "SUITE", "FAMILY")


class ConfigError(ValueError):
    """설정 값이 잘못됨."""


@dataclass(frozen=True)
class Range:
    low: int
    high: int

    def __post_init__(self) -> None:
        if self.low < 0 or self.high < self.low:
            raise ConfigError(f"invalid range [{self.low}, {self.high}]")


@dataclass(frozen=True)
class GeneratorConfig:
    property_name: str
    timezone: str
    check_in_time: time
    check_out_time: time
    operational_buffer_minutes: int
    start_date: date
    days: int
    rooms_by_type: dict[str, int]
    floors: int
    max_guests: dict[str, int]
    housekeepers: int
    inspectors: int
    shift_hours: int
    break_minutes: int
    housekeeper_shift_starts: tuple[time, ...]
    inspector_shift_starts: tuple[time, ...]
    target_occupancy: float
    stay_nights: tuple[tuple[int, int], ...]
    durations: dict[str, Range]
    room_type_multiplier: dict[str, float]
    probabilities: dict[str, float]
    source: dict = field(compare=False, repr=False, default_factory=dict)

    @property
    def total_rooms(self) -> int:
        return sum(self.rooms_by_type.values())

    @property
    def zone(self) -> ZoneInfo:
        return ZoneInfo(self.timezone)

    def config_hash(self) -> str:
        """정규화한 설정 원본의 SHA-256. 같은 값이면 같은 해시."""
        canonical = json.dumps(self.source, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        return hashlib.sha256(canonical.encode()).hexdigest()

    def snapshot(self) -> dict:
        return json.loads(json.dumps(self.source, sort_keys=True, ensure_ascii=False))


REQUIRED_DURATIONS = (
    "checkout_cleaning",
    "stayover_cleaning",
    "inspection",
    "re_cleaning",
    "linen_wait",
    "maintenance_block",
)
REQUIRED_PROBABILITIES = (
    "inspection_failure",
    "linen_shortage",
    "maintenance_block",
    "stayover_request",
)


def load_config(path: Path | str) -> GeneratorConfig:
    with open(path, "rb") as f:
        return parse_config(tomllib.load(f))


def parse_config(raw: dict) -> GeneratorConfig:
    try:
        prop, horizon, rooms, staff = raw["property"], raw["horizon"], raw["rooms"], raw["staff"]
        demand, durations, probabilities = raw["demand"], raw["durations"], raw["probabilities"]
    except KeyError as e:
        raise ConfigError(f"missing section {e}") from e

    try:
        ZoneInfo(prop["timezone"])
    except (ZoneInfoNotFoundError, ValueError) as e:
        raise ConfigError(f"unknown timezone {prop['timezone']!r}") from e

    rooms_by_type = {t: int(rooms.get(t, 0)) for t in ROOM_TYPES}
    if sum(rooms_by_type.values()) <= 0 or any(v < 0 for v in rooms_by_type.values()):
        raise ConfigError("room counts must be non-negative with at least one room")

    occupancy = float(demand["target_occupancy"])
    if not 0 < occupancy < 1:
        raise ConfigError("target_occupancy must be in (0, 1)")
    stay_nights = tuple((int(n), int(w)) for n, w in demand["stay_nights"])
    if not stay_nights or any(n < 1 or w <= 0 for n, w in stay_nights):
        raise ConfigError("stay_nights entries must be [nights >= 1, weight > 0]")

    missing = [k for k in REQUIRED_DURATIONS if k not in durations]
    if missing:
        raise ConfigError(f"missing durations {missing}")
    ranges = {k: Range(*map(int, durations[k])) for k in REQUIRED_DURATIONS}

    multiplier = {t: float(durations.get("room_type_multiplier", {}).get(t, 1.0)) for t in ROOM_TYPES}
    if any(m <= 0 for m in multiplier.values()):
        raise ConfigError("room_type_multiplier must be > 0")

    probs = {k: float(probabilities[k]) for k in REQUIRED_PROBABILITIES if k in probabilities}
    if set(probs) != set(REQUIRED_PROBABILITIES) or any(not 0 <= p <= 1 for p in probs.values()):
        raise ConfigError(f"probabilities {REQUIRED_PROBABILITIES} must be in [0, 1]")

    days = int(horizon["days"])
    if days < 1:
        raise ConfigError("horizon.days must be >= 1")

    return GeneratorConfig(
        property_name=str(prop["name"]),
        timezone=str(prop["timezone"]),
        check_in_time=time.fromisoformat(prop["check_in_time"]),
        check_out_time=time.fromisoformat(prop["check_out_time"]),
        operational_buffer_minutes=int(prop["operational_buffer_minutes"]),
        start_date=date.fromisoformat(str(horizon["start_date"])),
        days=days,
        rooms_by_type=rooms_by_type,
        floors=max(1, int(rooms.get("floors", 1))),
        max_guests={t: max(1, int(rooms.get("max_guests", {}).get(t, 2))) for t in ROOM_TYPES},
        housekeepers=int(staff["housekeepers"]),
        inspectors=int(staff["inspectors"]),
        shift_hours=int(staff["shift_hours"]),
        break_minutes=int(staff["break_minutes"]),
        housekeeper_shift_starts=tuple(time.fromisoformat(t) for t in staff["housekeeper_shift_starts"]),
        inspector_shift_starts=tuple(time.fromisoformat(t) for t in staff["inspector_shift_starts"]),
        target_occupancy=occupancy,
        stay_nights=stay_nights,
        durations=ranges,
        room_type_multiplier=multiplier,
        probabilities=probs,
        source=raw,
    )


def describe(config: GeneratorConfig) -> dict:
    """사람이 읽을 수 있는 가정 요약(출력 metadata에 포함)."""
    return {
        "rooms": config.rooms_by_type,
        "staff": {"housekeepers": config.housekeepers, "inspectors": config.inspectors},
        "targetOccupancy": config.target_occupancy,
        "durationsMinutes": {k: [v.low, v.high] for k, v in config.durations.items()},
        "roomTypeMultiplier": config.room_type_multiplier,
        "probabilities": config.probabilities,
        "note": "All values are arbitrary simulation assumptions, not industry averages or measurements.",
    }


__all__ = [
    "ConfigError",
    "GeneratorConfig",
    "Range",
    "ROOM_TYPES",
    "load_config",
    "parse_config",
    "describe",
    "asdict",
]
