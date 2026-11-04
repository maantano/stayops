/**
 * 합성 데이터 표시 배지 (v4.1 01 §8, 03 §3).
 * 색상만으로 의미를 전달하지 않도록 텍스트 라벨을 항상 함께 표시한다.
 */
export function SyntheticDataBadge() {
  return (
    <span
      className="inline-flex items-center rounded-full border border-amber-400 bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-900"
      title="이 화면의 데이터는 실제 호텔 운영 데이터가 아닌 합성 데이터입니다."
    >
      합성 데이터
    </span>
  );
}
