"""StayOps 합성 데이터 생성 및 오프라인 시뮬레이션 도구.

이 패키지가 만드는 모든 데이터와 결과는 합성(synthetic)이며 실제 호텔 운영 데이터가 아니다.
생성 로직과 가정값은 P10에서 추가한다 (docs/v4.1/06 §5).
"""

__version__ = "0.1.0"


def describe() -> str:
    return f"stayops-simulator {__version__} (synthetic data only)"


def main() -> None:
    print(describe())
