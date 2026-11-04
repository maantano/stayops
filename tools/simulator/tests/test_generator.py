"""합성 데이터 생성기 테스트 (docs/v4.1/05 §2 Simulation regression, 06 P10).

검증: 같은 seed·설정 재현성, 스트림 독립성, 불변식(겹침·현지 시각·범위·합성 표시), 설정 오류, DST, CLI.
"""

from __future__ import annotations

import copy
import json
import tomllib
from datetime import datetime
from pathlib import Path

import pytest

import stayops_simulator
from stayops_simulator.analysis import summarize, validate
from stayops_simulator.config import ConfigError, load_config, parse_config
from stayops_simulator.generator import generate, to_json

DEFAULT = Path(__file__).resolve().parents[1] / "configs" / "default.toml"


@pytest.fixture(scope="module")
def raw() -> dict:
    with open(DEFAULT, "rb") as f:
        return tomllib.load(f)


@pytest.fixture(scope="module")
def config():
    return load_config(DEFAULT)


def variant(raw: dict, **changes) -> dict:
    """raw 설정을 깊은 복사한 뒤 "section.key" 형태로 바꾼다."""
    out = copy.deepcopy(raw)
    for dotted, value in changes.items():
        *path, key = dotted.split("__")
        node = out
        for part in path:
            node = node[part]
        node[key] = value
    return out


# ------------------------------------------------------------------ 재현성


def test_same_seed_and_config_produce_identical_bytes(config):
    assert to_json(generate(config, 42)) == to_json(generate(config, 42))


def test_different_seeds_produce_different_data(config):
    a, b = generate(config, 1), generate(config, 2)
    assert [r["scheduledCheckInAt"] for r in a["reservations"]] != [r["scheduledCheckInAt"] for r in b["reservations"]]
    assert a["property"]["propertyId"] != b["property"]["propertyId"]


def test_metadata_records_seed_version_config_and_assumptions(config):
    meta = generate(config, 7)["metadata"]
    assert meta["synthetic"] is True
    assert meta["seed"] == 7
    assert meta["generatorVersion"] == "gen-v1"
    assert meta["configHash"] == config.config_hash()
    assert meta["config"]["durations"]["checkout_cleaning"] == [30, 55]
    assert "not industry averages" in meta["assumptions"]["note"]
    assert "generatedAt" not in meta  # 결정성: 생성 시각을 넣지 않는다


def test_changing_one_probability_does_not_reshuffle_other_streams(raw):
    """스트림 독립성: 투숙 중 청소 확률만 바꾸면 예약·청소 시간은 그대로다."""
    base = generate(parse_config(raw), 11)
    changed = generate(parse_config(variant(raw, probabilities__stayover_request=0.9)), 11)

    def strip(d: dict) -> list:
        return [
            (r["reservationId"], r["scheduledCheckInAt"], r["realization"]["checkoutCleaningMinutes"])
            for r in d["reservations"]
        ]

    assert strip(base) == strip(changed)
    assert sum(len(r["realization"]["stayoverRequests"]) for r in changed["reservations"]) > sum(
        len(r["realization"]["stayoverRequests"]) for r in base["reservations"]
    )


# ---------------------------------------------------------------- 불변식


@pytest.mark.parametrize("seed", [1, 2, 3, 42, 2026])
def test_generated_data_satisfies_invariants(config, seed):
    assert validate(generate(config, seed), config) == []


def test_room_and_staff_counts_follow_the_config(config):
    data = generate(config, 5)
    assert len(data["rooms"]) == 80
    assert {
        t: sum(1 for r in data["rooms"] if r["roomType"] == t) for t in ("STANDARD", "DELUXE", "SUITE", "FAMILY")
    } == {
        "STANDARD": 40,
        "DELUXE": 24,
        "SUITE": 12,
        "FAMILY": 4,
    }
    assert sum(1 for s in data["staff"] if s["role"] == "HOUSEKEEPER") == 6
    assert sum(1 for s in data["staff"] if s["role"] == "INSPECTOR") == 2
    assert len(data["shifts"]) == 8 * 14


def test_validator_catches_overlap_and_unmarked_records(config):
    data = generate(config, 9)
    room_id = data["reservations"][0]["roomId"]
    clone = dict(data["reservations"][0], reservationId="dup")
    data["reservations"].append(clone)
    data["rooms"][0]["synthetic"] = False
    problems = validate(data, config)
    assert any("overlapping reservations" in p and room_id in p for p in problems)
    assert any("rooms record must be marked synthetic" in p for p in problems)


def test_occupancy_is_close_to_the_target_on_average(config):
    """점유율은 확률적이므로 여러 seed 평균으로 본다 (허용 오차 0.05, ASSUMPTION)."""
    values = [summarize(generate(config, s), config)["overall"]["occupancy"] for s in range(10)]
    assert abs(sum(values) / len(values) - config.target_occupancy) < 0.05


def test_failed_inspections_have_re_cleaning_and_others_do_not(config):
    for r in generate(config, 3)["reservations"]:
        z = r["realization"]
        assert (z["reCleaningMinutes"] is not None) == z["inspectionFails"]
        assert (z["reInspectionMinutes"] is not None) == z["inspectionFails"]


# --------------------------------------------------------------- 시간·DST


def test_check_in_and_out_are_default_local_times_across_dst(raw):
    """미국 동부 DST 종료일(2026-11-01)을 지나도 현지 15:00/11:00이 유지되고 UTC 오프셋만 바뀐다."""
    cfg = parse_config(
        variant(
            raw,
            property__timezone="America/New_York",
            horizon__start_date="2026-10-28",
            horizon__days=7,
        )
    )
    data = generate(cfg, 4)
    assert validate(data, cfg) == []
    offsets = {
        datetime.fromisoformat(r["scheduledCheckInAt"].replace("Z", "+00:00"))
        .astimezone(cfg.zone)
        .utcoffset()
        .total_seconds()
        / 3600
        for r in data["reservations"]
    }
    assert offsets == {-4.0, -5.0}


# ---------------------------------------------------------------- 설정 오류


@pytest.mark.parametrize(
    ("changes", "message"),
    [
        ({"demand__target_occupancy": 1.2}, "target_occupancy"),
        ({"durations__inspection": [10, 5]}, "invalid range"),
        ({"probabilities__inspection_failure": -0.1}, "probabilities"),
        ({"property__timezone": "Mars/Base"}, "unknown timezone"),
        ({"demand__stay_nights": [[0, 10]]}, "stay_nights"),
    ],
)
def test_invalid_config_is_rejected(raw, changes, message):
    with pytest.raises(ConfigError, match=message):
        parse_config(variant(raw, **changes))


# ------------------------------------------------------------------- CLI


def test_cli_generates_validates_and_summarizes(tmp_path, capsys):
    out = tmp_path / "dataset.json"
    assert stayops_simulator.main(["generate", "--seed", "42", "--out", str(out)]) == 0
    assert stayops_simulator.main(["validate", str(out)]) == 0
    assert "valid" in capsys.readouterr().out
    assert stayops_simulator.main(["summarize", str(out)]) == 0
    summary = json.loads(capsys.readouterr().out)
    assert summary["synthetic"] is True
    assert len(summary["days"]) == 14
    assert all(d["staffCapacityMinutes"] == 6 * 450 + 2 * 450 for d in summary["days"])


def test_cli_reports_config_errors(tmp_path, capsys):
    bad = tmp_path / "bad.toml"
    bad.write_text(
        DEFAULT.read_text(encoding="utf-8").replace("target_occupancy = 0.78", "target_occupancy = 2"),
        encoding="utf-8",
    )
    assert (
        stayops_simulator.main(["generate", "--seed", "1", "--config", str(bad), "--out", str(tmp_path / "x.json")])
        == 2
    )
    assert "target_occupancy" in capsys.readouterr().err
