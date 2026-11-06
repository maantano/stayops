"use client";

import Link from "next/link";
import { useDashboard, useReadiness, useStaffWorkloads } from "@/api/hooks";
import { useDemoSession } from "@/app/providers";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge, Card, EmptyState, LoadingState } from "@/components/ui";
import { formatInstant, formatRisk } from "@/lib/format";
import { availabilityLabels, flagLabels, labelOf, missingInputLabel, contributionLabels, roleLabels, taskStatusLabels, workflowStageLabels } from "@/lib/labels";
import { GradeBadge, ReadinessBreakdown } from "../readiness/ReadinessBreakdown";

/** 운영 대시보드 (03 §3 Dashboard). UNKNOWN을 위험·정상에 합치지 않는다. */
export function DashboardView() {
  const { session } = useDemoSession();
  const dashboard = useDashboard();
  const readiness = useReadiness();
  const workloads = useStaffWorkloads();
  const tz = session?.timezone ?? "UTC";

  if (dashboard.isPending) return <LoadingState label="대시보드 불러오는 중" />;
  if (dashboard.isError) return <ErrorNotice error={dashboard.error} />;
  const d = dashboard.data;

  return (
    <div className="space-y-6">
      <p className="text-sm text-zinc-600">
        업무일 {d.businessDate} · {formatInstant(d.generatedAt, tz)} 기준 ({d.timezone}) · 30초마다 갱신
      </p>

      <section aria-labelledby="readiness-heading" className="space-y-3">
        <h2 id="readiness-heading" className="text-lg font-semibold">
          준비 예측 — 손님 도착 전에 객실이 준비될까요?
        </h2>
        <p className="text-sm text-zinc-600">
          도착 예정 손님이 있는 객실마다 남은 청소·검사가 끝나는 예상 시각을 계산해, 준비 마감(체크인 30분 전)과 비교합니다.
        </p>
        <div className="flex flex-wrap gap-2">
          {(["AT_RISK", "UNKNOWN", "WARNING", "ON_TRACK", "READY"] as const).map((g) => (
            <Card key={g} className="min-w-28 p-3" as="div">
              <GradeBadge grade={g} />
              <p className="mt-1 text-2xl font-semibold tabular-nums">{d.readiness.summary[g] ?? 0}</p>
            </Card>
          ))}
        </div>
        {d.readiness.topRisks.length === 0 ? (
          <EmptyState>확인이 필요한 도착 예정 객실이 없습니다.</EmptyState>
        ) : (
          <ul className="space-y-2" aria-label="확인이 필요한 객실">
            {d.readiness.topRisks.map((r) => (
              <Card key={r.roomId} as="li" className="flex flex-wrap items-center gap-3 p-3">
                <Link href={`/rooms/${r.roomId}`} className="font-semibold underline-offset-2 hover:underline">
                  {r.roomNumber}호
                </Link>
                <GradeBadge grade={r.grade} />
                {r.grade === "UNKNOWN" ? null : <span className="text-sm">{formatRisk(r.riskMinutes)}</span>}
                <span className="text-sm text-zinc-600">준비 마감 {formatInstant(r.readyDeadline, tz)}</span>
                <span className="text-sm text-zinc-600">
                  원인:{" "}
                  {r.topReasons
                    .map((reason) => (reason.startsWith("MISSING:") ? missingInputLabel(reason.slice(8)) : contributionLabels[reason] ?? reason))
                    .join(" · ")}
                </span>
              </Card>
            ))}
          </ul>
        )}
      </section>

      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <h2 className="mb-2 font-semibold">객실 단계 ({d.rooms.total}실)</h2>
          <ul className="space-y-1 text-sm">
            {Object.entries(d.rooms.byStage).map(([stage, count]) => (
              <li key={stage} className="flex justify-between">
                <Link href={`/rooms?stage=${stage}`} className="hover:underline">
                  {labelOf(workflowStageLabels, stage).label}
                </Link>
                <span className="tabular-nums">{count}</span>
              </li>
            ))}
            {Object.entries(d.rooms.byAvailability).map(([a, count]) => (
              <li key={a} className="flex justify-between text-zinc-600">
                <span>{labelOf(availabilityLabels, a).label}</span>
                <span className="tabular-nums">{count}</span>
              </li>
            ))}
          </ul>
          {d.rooms.interruptedWork.length ? (
            <p className="mt-2">
              <Badge tone="danger" label={`담당자 없음 · 진행 중단 ${d.rooms.interruptedWork.length}실`} />
            </p>
          ) : null}
        </Card>
        <Card>
          <h2 className="mb-2 font-semibold">오늘 투숙</h2>
          <ul className="space-y-1 text-sm">
            <li className="flex justify-between"><span>도착 예정</span><span className="tabular-nums">{d.stays.expectedArrivals}</span></li>
            <li className="flex justify-between"><span>체크인 완료</span><span className="tabular-nums">{d.stays.checkedInToday}</span></li>
            <li className="flex justify-between"><span>출발 예정</span><span className="tabular-nums">{d.stays.expectedDepartures}</span></li>
            <li className="flex justify-between"><span>체크아웃 완료</span><span className="tabular-nums">{d.stays.checkedOutToday}</span></li>
          </ul>
        </Card>
        <Card>
          <h2 className="mb-2 font-semibold">진행 중 작업</h2>
          <ul className="space-y-1 text-sm">
            {Object.entries(d.tasks).map(([status, count]) => (
              <li key={status} className="flex justify-between">
                <Link href={`/tasks?status=${status}`} className="hover:underline">
                  {labelOf(taskStatusLabels, status).label}
                </Link>
                <span className="tabular-nums">{count}</span>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <section aria-labelledby="workload-heading" className="space-y-2">
        <h2 id="workload-heading" className="text-lg font-semibold">담당자별 업무량</h2>
        {workloads.isPending ? <LoadingState /> : null}
        {workloads.isError ? <ErrorNotice error={workloads.error} /> : null}
        {workloads.data ? (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[32rem] text-sm">
              <caption className="sr-only">담당자별 근무 가능 시간 대비 배정 작업</caption>
              <thead className="text-left text-xs text-zinc-500">
                <tr><th className="py-1">담당자</th><th>역할</th><th>배정</th><th>근무 가능</th><th>부하</th><th>표시</th></tr>
              </thead>
              <tbody>
                {workloads.data.staff.map((s) => (
                  <tr key={s.staffId} className="border-t border-zinc-100">
                    <td className="py-1">{s.displayName}</td>
                    <td>{roleLabels[s.role] ?? s.role}</td>
                    <td className="tabular-nums">{s.activeTaskCount}건 / {s.assignedEstimatedMinutes}분</td>
                    <td className="tabular-nums">{s.availableMinutes}분</td>
                    <td className="tabular-nums">{s.workloadRatio === undefined || s.workloadRatio === null ? "-" : `${Math.round(s.workloadRatio * 100)}%`}</td>
                    <td className="text-xs">{s.flags.map((f) => flagLabels[f] ?? f).join(", ")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-xs text-zinc-500">{workloads.data.note}</p>
          </div>
        ) : null}
      </section>

      <section aria-labelledby="readiness-detail-heading" className="space-y-2">
        <h2 id="readiness-detail-heading" className="text-lg font-semibold">도착 예정 객실 전체 · 예측 근거</h2>
        {readiness.isError ? <ErrorNotice error={readiness.error} /> : null}
        {readiness.data?.rooms.map((r) => (
          <details key={r.roomId} className="rounded-md border border-zinc-200 bg-white p-3">
            <summary className="flex cursor-pointer flex-wrap items-center gap-2">
              <span className="font-semibold">{r.roomNumber}호</span>
              <GradeBadge grade={r.grade} />
              <span className="text-sm text-zinc-600">{r.grade === "UNKNOWN" ? "" : formatRisk(r.riskMinutes)}</span>
            </summary>
            <div className="mt-3">
              <ReadinessBreakdown result={r} timeZone={tz} />
            </div>
          </details>
        ))}
      </section>
    </div>
  );
}
