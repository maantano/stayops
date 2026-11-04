/**
 * 시각은 서버의 Instant(ISO)를 **property timezone**으로 표시한다 (03 §4). 브라우저 timezone으로 무심코 바꾸지 않는다.
 */
export function formatInstant(iso: string | null | undefined, timeZone: string, withDate = true): string {
  if (!iso) return "-";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "-";
  const parts = new Intl.DateTimeFormat("ko-KR", {
    timeZone,
    month: withDate ? "2-digit" : undefined,
    day: withDate ? "2-digit" : undefined,
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(date);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  const time = `${get("hour")}:${get("minute")}`;
  return withDate ? `${get("month")}/${get("day")} ${time}` : time;
}

/** 위험 분: 양수면 "N분 늦음", 0 이하면 "N분 여유". */
export function formatRisk(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "-";
  if (minutes > 0) return `${minutes}분 늦음`;
  if (minutes === 0) return "마감 시각과 같음";
  return `${-minutes}분 여유`;
}

export function formatMinutes(seconds: number): string {
  const minutes = Math.ceil(seconds / 60);
  if (minutes < 60) return `${minutes}분`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h}시간` : `${h}시간 ${m}분`;
}
