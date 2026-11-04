"use client";

import { useState } from "react";
import type { SimPolicy, SimulationRunView } from "@/api/endpoints";
import { useSimulation, useSimulationDatasets, useSimulations } from "@/api/hooks";
import { useDemoSession } from "@/app/providers";
import { ErrorNotice } from "@/components/ErrorNotice";
import { SyntheticDataBadge } from "@/components/SyntheticDataBadge";
import { Button, Card, EmptyState, Field, inputClass, LoadingState } from "@/components/ui";
import { formatInstant } from "@/lib/format";
import { useCommandRunner } from "../commands";

type Metrics = SimulationRunView["scenarios"][number]["metrics"];

/** API 기본 본문 상한(`stayops.web.max-request-size`, 5MB)과 같다. */
export const MAX_DATASET_BYTES = 5 * 1024 * 1024;

const POLICIES: { value: SimPolicy; label: string }[] = [
  { value: "BASELINE_FIFO", label: "기준: 먼저 생긴 작업부터 · 교대 시작 분산" },
  { value: "DEADLINE_FIRST", label: "대안 A: 준비 마감이 급한 객실부터" },
  { value: "SAME_SHIFT_START", label: "대안 B: 하우스키핑 교대 시작을 모두 09:00으로" },
];

type Better = "higher" | "lower" | "neutral";
type Row = { key: string; label: string; better: Better; format: (v: number) => string; value: (m: Metrics) => number | null | undefined };

const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
const min = (v: number) => `${v.toFixed(1)}분`;
const count = (v: number) => `${Math.round(v)}`;

/** 비교 표의 행. key는 서버 diffFromBaseline.metrics의 키와 같다. 좋아지는 방향을 함께 정의한다. */
export const KPI_ROWS: Row[] = [
  { key: "onTimeReadinessRate", label: "정시 준비율", better: "higher", format: pct, value: (m) => m.onTimeReadinessRate },
  { key: "late", label: "마감 이후 준비 (건)", better: "lower", format: count, value: (m) => m.late },
  { key: "notReadyByHorizonEnd", label: "기간 내 미준비 (건)", better: "lower", format: count, value: (m) => m.notReadyByHorizonEnd },
  { key: "meanLatenessMinutes", label: "평균 지연", better: "lower", format: min, value: (m) => m.meanLatenessMinutes },
  { key: "p90LatenessMinutes", label: "지연 90번째 백분위", better: "lower", format: min, value: (m) => m.p90LatenessMinutes },
  { key: "taskCompletionRate", label: "작업 완료율", better: "higher", format: pct, value: (m) => m.taskCompletionRate },
  { key: "housekeeperUtilization", label: "하우스키핑 가동률", better: "neutral", format: pct, value: (m) => m.workload.HOUSEKEEPER?.utilization },
  { key: "inspectorUtilization", label: "검사 가동률", better: "neutral", format: pct, value: (m) => m.workload.INSPECTOR?.utilization },
  { key: "overtimeMinutes", label: "초과근무 (분)", better: "lower", format: count, value: (m) => Object.values(m.workload).reduce((a, w) => a + (w?.overtimeMinutes ?? 0), 0) },
  { key: "stayoverMeanWaitMinutes", label: "투숙 중 청소 평균 대기", better: "lower", format: min, value: (m) => m.stayover.meanWaitMinutes },
];

/** 기준 대비 차이를 텍스트로 (색만으로 좋고 나쁨을 전달하지 않는다). */
export function describeDiff(row: Row, diff: number | null | undefined): { text: string; verdict: "개선" | "악화" | "변화 없음" | "비교 불가" | "" } {
  if (diff === null || diff === undefined) return { text: "-", verdict: "비교 불가" };
  if (Math.abs(diff) < 1e-9) return { text: "±0", verdict: "변화 없음" };
  const sign = diff > 0 ? "+" : "−";
  const magnitude = row.format === pct ? `${(Math.abs(diff) * 100).toFixed(1)}%p` : row.format(Math.abs(diff));
  if (row.better === "neutral") return { text: `${sign}${magnitude}`, verdict: "" };
  const improved = row.better === "higher" ? diff > 0 : diff < 0;
  return { text: `${sign}${magnitude}`, verdict: improved ? "개선" : "악화" };
}

/** 시뮬레이션 (03 §3 Simulation): 합성 데이터셋 업로드 → 정책 비교 실행 → 기준 대비 KPI. */
export function SimulationView() {
  const { session } = useDemoSession();
  const isManager = session?.role === "MANAGER";
  const datasets = useSimulationDatasets();
  const runs = useSimulations();
  const [selectedRun, setSelectedRun] = useState<string | null>(null);
  const run = useSimulation(selectedRun);

  return (
    <div className="space-y-6">
      <Card className="flex flex-wrap items-center gap-3 border-amber-300 bg-amber-50">
        <SyntheticDataBadge />
        <p className="text-sm text-amber-900">
          Python 생성기로 만든 <strong>합성 데이터</strong>에 운영 정책을 적용한 상대 비교입니다. 실제 운영 성과나 최적화 보장이 아닙니다.
        </p>
      </Card>

      {isManager ? <RunPanel onRun={setSelectedRun} /> : null}

      <section aria-labelledby="runs-heading" className="space-y-2">
        <h2 id="runs-heading" className="text-lg font-semibold">실행 기록</h2>
        {runs.isPending ? <LoadingState /> : null}
        {runs.isError ? <ErrorNotice error={runs.error} /> : null}
        {runs.isSuccess && runs.data.length === 0 ? <EmptyState>아직 실행한 시뮬레이션이 없습니다.</EmptyState> : null}
        <ul className="flex flex-wrap gap-2">
          {runs.data?.map((r) => (
            <li key={r.runId}>
              <Button variant={selectedRun === r.runId ? "primary" : "secondary"} onClick={() => setSelectedRun(r.runId)} aria-pressed={selectedRun === r.runId}>
                {formatInstant(r.createdAt, session?.timezone ?? "UTC")} · seed {r.seed} · 정책 {r.policies.length}개
              </Button>
            </li>
          ))}
        </ul>
      </section>

      {datasets.isError ? <ErrorNotice error={datasets.error} /> : null}
      {run.isPending && selectedRun ? <LoadingState label="결과 불러오는 중" /> : null}
      {run.isError ? <ErrorNotice error={run.error} /> : null}
      {run.data ? <ComparisonTable run={run.data} timeZone={session?.timezone ?? "UTC"} /> : null}
    </div>
  );
}

function RunPanel({ onRun }: { onRun: (runId: string) => void }) {
  const { session } = useDemoSession();
  const datasets = useSimulationDatasets();
  const upload = useCommandRunner("importSimulationDataset", "데이터셋을 올렸습니다");
  const runner = useCommandRunner("runSimulation", "시뮬레이션을 실행했습니다");
  const [datasetId, setDatasetId] = useState("");
  const [policies, setPolicies] = useState<SimPolicy[]>(POLICIES.map((p) => p.value));
  const [fileError, setFileError] = useState<string | null>(null);
  const propertyId = session?.propertyId ?? "";
  const chosen = datasetId || datasets.data?.[0]?.datasetId || "";

  const onFile = async (file: File | undefined) => {
    setFileError(null);
    if (!file) return;
    if (file.size > MAX_DATASET_BYTES) {
      // 서버도 같은 상한으로 거절한다(413 PAYLOAD_TOO_LARGE). 미리 막아 큰 파일을 읽고 보내지 않는다.
      setFileError(`파일이 너무 큽니다 (${Math.ceil(file.size / 1024 / 1024)}MB). ${MAX_DATASET_BYTES / 1024 / 1024}MB 이하만 올릴 수 있습니다.`);
      return;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(await file.text());
    } catch {
      setFileError("JSON 파일이 아닙니다. stayops-simulator generate로 만든 파일을 선택하세요.");
      return;
    }
    const outcome = await upload.execute(propertyId, parsed as Record<string, unknown>);
    if (outcome) setDatasetId(outcome.data.datasetId);
  };

  const run = async () => {
    const outcome = await runner.execute(propertyId, chosen, policies);
    if (outcome) onRun(outcome.data.runId);
  };

  return (
    <Card className="space-y-4">
      <h2 className="text-lg font-semibold">정책 비교 실행</h2>
      <Field
        label="합성 데이터셋 올리기 (gen-v1 JSON)"
        htmlFor="dataset-file"
        hint="tools/simulator에서 `uv run stayops-simulator generate --seed 42 --out output/dataset-42.json`로 만든 파일. 같은 파일은 한 번만 저장됩니다."
        error={fileError ?? undefined}
      >
        <input id="dataset-file" type="file" accept="application/json,.json" className="text-sm" onChange={(e) => onFile(e.target.files?.[0])} disabled={upload.pending} />
      </Field>
      <ErrorNotice error={upload.error} onRetry={upload.retry} />

      <Field label="데이터셋" htmlFor="dataset-select">
        <select id="dataset-select" className={inputClass} value={chosen} onChange={(e) => setDatasetId(e.target.value)}>
          {(datasets.data ?? []).map((d) => (
            <option key={d.datasetId} value={d.datasetId}>
              {d.summary.name} · seed {d.summary.seed} · {d.summary.startDate}부터 {d.summary.days}일 · {d.summary.rooms}실 · 예약 {d.summary.reservations}건
            </option>
          ))}
        </select>
      </Field>

      <fieldset className="space-y-1">
        <legend className="text-sm font-medium">비교할 정책</legend>
        {POLICIES.map((p) => (
          <label key={p.value} className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={policies.includes(p.value)}
              disabled={p.value === "BASELINE_FIFO"}
              onChange={(e) =>
                setPolicies((current) =>
                  e.target.checked ? POLICIES.map((x) => x.value).filter((v) => v === p.value || current.includes(v)) : current.filter((v) => v !== p.value),
                )
              }
            />
            {p.label} {p.value === "BASELINE_FIFO" ? <span className="text-xs text-zinc-500">(항상 포함)</span> : null}
          </label>
        ))}
      </fieldset>
      <ErrorNotice error={runner.error} onRetry={runner.retry} />
      <Button variant="primary" onClick={run} disabled={!chosen || runner.pending}>
        {runner.pending ? "실행 중…" : "실행"}
      </Button>
    </Card>
  );
}

function ComparisonTable({ run, timeZone }: { run: SimulationRunView; timeZone: string }) {
  const baseline = run.scenarios[0];
  return (
    <section aria-labelledby="result-heading" className="space-y-3">
      <h2 id="result-heading" className="text-lg font-semibold">비교 결과</h2>
      <p className="text-sm text-zinc-600">
        {run.dataset.summary.name} · seed {run.seed} · {run.dataset.summary.startDate}부터 {run.dataset.summary.days}일 · {run.engineVersion} / {run.policyVersion} ·{" "}
        {formatInstant(run.completedAt, timeZone)} 실행 · 평가 대상 도착 {baseline.metrics.evaluatedArrivals}건 (turnover {baseline.metrics.turnovers}건)
      </p>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[40rem] text-sm">
          <caption className="sr-only">정책별 KPI와 기준 대비 차이</caption>
          <thead>
            <tr className="text-left">
              <th scope="col" className="py-2">지표</th>
              {run.scenarios.map((s) => (
                <th key={s.policy} scope="col" className="py-2">
                  {s.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {KPI_ROWS.map((row) => (
              <tr key={row.key} className="border-t border-zinc-100">
                <th scope="row" className="py-1.5 text-left font-normal">{row.label}</th>
                {run.scenarios.map((s, i) => {
                  const value = row.value(s.metrics);
                  const diff = i === 0 ? null : describeDiff(row, s.diffFromBaseline?.metrics?.[row.key]);
                  return (
                    <td key={s.policy} className="py-1.5 tabular-nums">
                      {value === null || value === undefined ? "-" : row.format(value)}
                      {diff ? (
                        <span className={`ml-2 text-xs ${diff.verdict === "개선" ? "text-emerald-700" : diff.verdict === "악화" ? "text-rose-700" : "text-zinc-500"}`}>
                          ({diff.text}{diff.verdict && diff.verdict !== "비교 불가" ? ` ${diff.verdict}` : ""})
                        </span>
                      ) : null}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details className="text-sm">
        <summary>지표 정의와 시뮬레이션 가정</summary>
        <dl className="mt-2 space-y-1">
          {Object.entries(run.definitions).map(([k, v]) => (
            <div key={k}>
              <dt className="font-medium">{k}</dt>
              <dd className="text-zinc-600">{v}</dd>
            </div>
          ))}
        </dl>
        <ul className="mt-2 list-disc pl-5 text-zinc-600">
          {run.assumptions.map((a) => <li key={a}>{a}</li>)}
        </ul>
      </details>
      <p className="text-xs text-zinc-500">{run.note}</p>
    </section>
  );
}
