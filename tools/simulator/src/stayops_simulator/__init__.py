"""StayOps 합성 데이터 생성 및 오프라인 분석 도구 (docs/v4.1/02 §2.2, 06 P10).

이 패키지가 만드는 모든 데이터와 결과는 합성(synthetic)이며 실제 호텔 운영 데이터가 아니다.
API 서버나 백엔드 subprocess로 쓰지 않는다. 결과는 파일(JSON)로만 넘긴다.

    stayops-simulator generate --seed 42 --out output/dataset-42.json
    stayops-simulator validate output/dataset-42.json
    stayops-simulator summarize output/dataset-42.json
"""

import argparse
import json
import sys
from pathlib import Path

from .analysis import summarize, validate
from .config import ConfigError, load_config
from .generator import GENERATOR_VERSION, generate, to_json

__version__ = "0.2.0"

DEFAULT_CONFIG = Path(__file__).resolve().parents[2] / "configs" / "default.toml"


def describe() -> str:
    return f"stayops-simulator {__version__} ({GENERATOR_VERSION}, synthetic data only)"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="stayops-simulator", description=describe())
    sub = parser.add_subparsers(dest="command")

    gen = sub.add_parser("generate", help="데모 데이터셋 생성 (같은 seed·설정 → 같은 파일)")
    gen.add_argument("--seed", type=int, required=True)
    gen.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    gen.add_argument("--out", type=Path, required=True)

    for name, help_text in (
        ("validate", "데이터 불변식 검사"),
        ("summarize", "하루 단위 수요 요약(JSON)"),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("dataset", type=Path)
        p.add_argument("--config", type=Path, default=DEFAULT_CONFIG)

    args = parser.parse_args(argv)
    if args.command is None:
        print(describe())
        return 0
    try:
        config = load_config(args.config)
    except ConfigError as e:
        print(f"config error: {e}", file=sys.stderr)
        return 2

    if args.command == "generate":
        dataset = generate(config, args.seed)
        problems = validate(dataset, config)
        if problems:
            print("generated dataset is invalid:\n" + "\n".join(problems), file=sys.stderr)
            return 1
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(to_json(dataset), encoding="utf-8")
        counts = dataset["metadata"]["counts"]
        print(f"wrote {args.out} (seed={args.seed}, configHash={dataset['metadata']['configHash'][:12]}, {counts})")
        return 0

    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    if dataset["metadata"]["configHash"] != config.config_hash():
        print("warning: dataset was generated with a different config", file=sys.stderr)
    if args.command == "validate":
        problems = validate(dataset, config)
        print("valid" if not problems else "\n".join(problems))
        return 0 if not problems else 1
    print(json.dumps(summarize(dataset, config), ensure_ascii=False, indent=2))
    return 0
