"""언어 간 계약: Kotlin API 테스트가 쓰는 데이터셋 fixture가 현재 생성기 출력과 같아야 한다 (P11).

생성기나 형식을 바꿔 이 테스트가 실패하면 fixture를 다시 만들고 API 테스트를 함께 확인한다:
    uv run stayops-simulator generate --seed 7 --config configs/contract.toml \
        --out ../../apps/api/src/test/resources/simulation/dataset-contract.json
"""

from pathlib import Path

from stayops_simulator.config import load_config
from stayops_simulator.generator import generate, to_json

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT.parents[1] / "apps" / "api" / "src" / "test" / "resources" / "simulation" / "dataset-contract.json"


def test_kotlin_fixture_matches_generator_output():
    expected = to_json(generate(load_config(ROOT / "configs" / "contract.toml"), 7))
    assert FIXTURE.read_text(encoding="utf-8") == expected
