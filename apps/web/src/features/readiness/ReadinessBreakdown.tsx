import type { components } from "@/api/schema";
import { Badge } from "@/components/ui";
import { formatInstant, formatMinutes, formatRisk } from "@/lib/format";
import { contributionLabels, flagLabels, gradeLabels, labelOf, missingInputLabel } from "@/lib/labels";

type ReadinessResult = components["schemas"]["ReadinessResult"];

export function GradeBadge({ grade }: { grade: string }) {
  const g = labelOf(gradeLabels, grade);
  return <Badge tone={g.tone} label={g.label} prefix="준비 예측" />;
}

/**
 * 준비 예측의 계산 근거 (02 §8, H2 "원인 설명"). 예상 시각은 보장이 아니라 입력 기반 계산값임을 함께 밝힌다.
 */
export function ReadinessBreakdown({ result, timeZone }: { result: ReadinessResult; timeZone: string }) {
  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <GradeBadge grade={result.grade} />
        {result.riskMinutes !== undefined && result.riskMinutes !== null ? <span>{formatRisk(result.riskMinutes)}</span> : null}
        {result.availabilityStatus === "OUT_OF_SERVICE" ? <Badge tone="danger" label="판매 중단" /> : null}
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 sm:grid-cols-3">
        <div>
          <dt className="text-xs text-zinc-500">체크인 예정</dt>
          <dd>{formatInstant(result.scheduledCheckInAt, timeZone)}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">준비 마감 (체크인 − 운영 여유)</dt>
          <dd>{formatInstant(result.readyDeadline, timeZone)}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">예상 준비 시각</dt>
          <dd>{result.expectedReadyAt ? formatInstant(result.expectedReadyAt, timeZone) : result.grade === "READY" ? "준비 완료" : "계산 불가"}</dd>
        </div>
      </dl>

      {result.missingInputs.length ? (
        <div>
          <p className="font-medium">판정할 수 없는 이유</p>
          <ul className="list-disc pl-5">
            {result.missingInputs.map((m) => (
              <li key={m}>{missingInputLabel(m)}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {result.contributions.length ? (
        <div>
          <p className="font-medium">예상 시각까지 걸리는 시간</p>
          <ol className="space-y-1">
            {result.contributions.map((c, i) => (
              <li key={i} className="flex justify-between gap-4 border-b border-dashed border-zinc-200 py-0.5">
                <span>
                  {contributionLabels[c.type] ?? c.type}
                  {c.durationSource === "CONFIG_DEFAULT" ? <span className="text-xs text-zinc-500"> (기본값)</span> : null}
                  {c.note ? <span className="text-xs text-zinc-500"> — {c.note}</span> : null}
                </span>
                <span className="tabular-nums">{formatMinutes(c.seconds)}</span>
              </li>
            ))}
          </ol>
          {result.overlappedBlockers.length ? (
            <p className="text-xs text-zinc-500">겹친 차단 {result.overlappedBlockers.length}건은 가장 늦은 해제 시각만 반영했습니다.</p>
          ) : null}
        </div>
      ) : null}

      {result.flags.length ? (
        <p className="text-xs">
          표시: {result.flags.map((f) => flagLabels[f] ?? f).join(", ")}
        </p>
      ) : null}
      {result.assumptions.length ? (
        <details className="text-xs text-zinc-600">
          <summary>계산 가정 ({result.assumptions.length})</summary>
          <ul className="list-disc pl-5">
            {result.assumptions.map((a) => (
              <li key={a}>{a}</li>
            ))}
          </ul>
        </details>
      ) : null}
      <p className="text-xs text-zinc-500">
        {result.calculationVersion} · {formatInstant(result.calculatedAt, timeZone)} 계산 · 예측 보장이 아닌 입력 기반 추정입니다.
      </p>
    </div>
  );
}
