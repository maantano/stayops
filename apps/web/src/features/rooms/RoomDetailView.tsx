"use client";

import { useParams } from "next/navigation";
import { useReadiness, useRoom, useRoomTimeline, useTasks } from "@/api/hooks";
import { useDemoSession } from "@/app/providers";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge, Button, Card, EmptyState, LoadingState } from "@/components/ui";
import { formatInstant } from "@/lib/format";
import { availabilityLabels, flagLabels, labelOf, operationalStatusLabels, workflowStageLabels } from "@/lib/labels";
import { ReadinessBreakdown } from "../readiness/ReadinessBreakdown";
import { TaskCard } from "../tasks/TaskCard";
import { RoomCommands } from "./RoomCommands";

const ACTIVE = ["PENDING", "ASSIGNED", "IN_PROGRESS", "BLOCKED"] as const;

const EVENT_LABELS: Record<string, string> = {
  RESERVATION_CHECKED_IN: "체크인",
  RESERVATION_CHECKED_OUT: "체크아웃",
  ROOM_STATUS_CHANGED: "객실 상태 변경",
  ROOM_AVAILABILITY_CHANGED: "판매 상태 변경",
  HOUSEKEEPING_TASK_CREATED: "작업 생성",
  TASK_ASSIGNED: "담당자 배정",
  TASK_UNASSIGNED: "배정 해제",
  TASK_STARTED: "작업 시작",
  TASK_RESUMED: "작업 재개",
  BLOCKER_ADDED: "차단 등록",
  BLOCKER_RESOLVED: "차단 해제",
  TASK_COMPLETED: "작업 완료",
  TASK_CANCELLED: "작업 취소",
  INSPECTION_RECORDED: "검사 결과",
};

/** 객실 상세 (03 §3 Room detail): 상태 두 축 + 업무 단계, 진행 작업, 준비 예측 근거, 이벤트 타임라인. */
export function RoomDetailView() {
  const { roomId } = useParams<{ roomId: string }>();
  const { session } = useDemoSession();
  const room = useRoom(roomId);
  const tasks = useTasks({ roomId, status: [...ACTIVE] });
  const timeline = useRoomTimeline(roomId);
  const canSeeReadiness = session?.role === "MANAGER" || session?.role === "FRONT_DESK";
  const readiness = useReadiness(canSeeReadiness);
  const tz = session?.timezone ?? "UTC";

  if (room.isPending) return <LoadingState label="객실 불러오는 중" />;
  if (room.isError) return <ErrorNotice error={room.error} />;
  const r = room.data;
  const stage = labelOf(workflowStageLabels, r.workflowStage);
  const op = labelOf(operationalStatusLabels, r.operationalStatus);
  const availability = labelOf(availabilityLabels, r.availabilityStatus);
  const readinessRow = canSeeReadiness ? readiness.data?.rooms.find((x) => x.roomId === roomId) : undefined;
  const events = timeline.data?.pages.flatMap((p) => p.items) ?? [];
  const activeTasks = tasks.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="space-y-6">
      <Card className="space-y-3">
        <div className="flex flex-wrap items-baseline gap-3">
          <h2 className="text-xl font-semibold">{r.roomNumber}호</h2>
          <span className="text-sm text-zinc-500">
            {r.roomType} · {r.floor}층
          </span>
        </div>
        <dl className="flex flex-wrap gap-6 text-sm">
          <div>
            <dt className="text-xs text-zinc-500">업무 단계</dt>
            <dd><Badge tone={stage.tone} label={stage.label} prefix="업무 단계" /></dd>
          </div>
          <div>
            <dt className="text-xs text-zinc-500">객실 운영 상태</dt>
            <dd><Badge tone={op.tone} label={op.label} prefix="운영 상태" /></dd>
          </div>
          <div>
            <dt className="text-xs text-zinc-500">판매 상태</dt>
            <dd>
              <Badge tone={availability.tone} label={availability.label} prefix="판매 상태" />
              {r.availabilityReason ? <span className="ml-2 text-xs text-zinc-600">사유: {r.availabilityReason}</span> : null}
            </dd>
          </div>
        </dl>
        {r.operationalStatus === "READY" && r.availabilityStatus === "OUT_OF_SERVICE" ? (
          <p className="text-sm text-rose-800">준비는 완료됐지만 판매 중단 상태라 체크인할 수 없습니다.</p>
        ) : null}
        {r.alerts.map((a) => (
          <p key={a}><Badge tone="danger" label={flagLabels[a] ?? a} /></p>
        ))}
        <RoomCommands room={r} />
      </Card>

      <section aria-labelledby="active-task-heading" className="space-y-2">
        <h2 id="active-task-heading" className="text-lg font-semibold">진행 중인 작업</h2>
        {tasks.isPending ? <LoadingState /> : null}
        {tasks.isError ? <ErrorNotice error={tasks.error} /> : null}
        {tasks.isSuccess && activeTasks.length === 0 ? <EmptyState>진행 중인 작업이 없습니다.</EmptyState> : null}
        {activeTasks.map((t) => <TaskCard key={t.taskId} task={t} />)}
      </section>

      {canSeeReadiness ? (
        <section aria-labelledby="readiness-heading" className="space-y-2">
          <h2 id="readiness-heading" className="text-lg font-semibold">준비 예측</h2>
          {readiness.isPending ? <LoadingState /> : null}
          {readiness.isError ? <ErrorNotice error={readiness.error} /> : null}
          {readiness.isSuccess && !readinessRow ? <EmptyState>24시간 안에 도착 예정인 예약이 없어 평가하지 않습니다.</EmptyState> : null}
          {readinessRow ? <Card><ReadinessBreakdown result={readinessRow} timeZone={tz} /></Card> : null}
        </section>
      ) : null}

      <section aria-labelledby="timeline-heading" className="space-y-2">
        <h2 id="timeline-heading" className="text-lg font-semibold">이벤트 타임라인</h2>
        {timeline.isPending ? <LoadingState /> : null}
        {timeline.isError ? <ErrorNotice error={timeline.error} /> : null}
        {timeline.isSuccess && events.length === 0 ? <EmptyState>기록된 이벤트가 없습니다.</EmptyState> : null}
        <ol className="space-y-1">
          {events.map((e) => (
            <li key={e.eventId} className="flex flex-wrap gap-x-3 border-b border-zinc-100 py-1 text-sm">
              <time dateTime={e.occurredAt} className="tabular-nums text-zinc-500">{formatInstant(e.occurredAt, tz)}</time>
              <span className="font-medium">{EVENT_LABELS[e.eventType] ?? e.eventType}</span>
              <span className="text-zinc-600">{summarize(e.payload)}</span>
              <span className="ml-auto text-xs text-zinc-500">{e.actorDisplayName ?? e.actorType}</span>
            </li>
          ))}
        </ol>
        {timeline.hasNextPage ? (
          <Button onClick={() => timeline.fetchNextPage()} disabled={timeline.isFetchingNextPage}>이전 이벤트 더 보기</Button>
        ) : null}
      </section>
    </div>
  );
}

function summarize(payload: Record<string, unknown>): string {
  const parts: string[] = [];
  if (payload.from && payload.to) parts.push(`${labelOf(operationalStatusLabels, String(payload.from)).label} → ${labelOf(operationalStatusLabels, String(payload.to)).label}`);
  if (payload.type && typeof payload.type === "string") parts.push(String(payload.type));
  if (payload.result) parts.push(String(payload.result) === "PASSED" ? "통과" : `실패${payload.failureReason ? `: ${payload.failureReason}` : ""}`);
  if (payload.reason && typeof payload.reason === "string") parts.push(payload.reason);
  if (payload.restoredStatus) parts.push(`복귀: ${payload.restoredStatus}`);
  return parts.join(" · ");
}
