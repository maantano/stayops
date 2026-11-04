# stayops-simulator

StayOps 합성 데이터 생성 및 오프라인 분석 도구. 생성되는 모든 데이터는 **합성 데이터**이며 실제 운영 데이터가 아니다.
런타임 의존성 없이 Python 3.11 표준 라이브러리만 쓴다. 형식과 재현성 계약: `docs/simulation/DATASET.md`.

```bash
uv sync
uv run stayops-simulator generate --seed 42 --out output/dataset-42.json   # 가정값: configs/default.toml
uv run stayops-simulator validate output/dataset-42.json
uv run stayops-simulator summarize output/dataset-42.json
uv run pytest
uv run ruff check . && uv run ruff format --check .
```
