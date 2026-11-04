"use client";

import Link from "next/link";
import { useState } from "react";
import type { TaskListItem } from "@/api/endpoints";
import { useDemoSession } from "@/app/providers";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge, Button, Card } from "@/components/ui";
import { formatInstant } from "@/lib/format";
import { blockerTypeLabels, labelOf, taskOriginLabels, taskStatusLabels, taskTypeLabels } from "@/lib/labels";
import { useCommandRunner } from "../commands";
import { AssignDialog, BlockDialog, CancelDialog, InspectionDialog } from "./TaskDialogs";
import { availableTaskActions } from "./taskActions";

type OpenDialog = "assign" | "block" | "inspect" | "cancel" | null;

/** 작업 카드: 상태·담당·차단 사유를 보여주고 현재 사용자에게 허용될 법한 명령만 노출한다 (03 §3 Task board). */
export function TaskCard({ task, highlighted = false }: { task: TaskListItem; highlighted?: boolean }) {
  const { session } = useDemoSession();
  const [dialog, setDialog] = useState<OpenDialog>(null);
  const start = useCommandRunner("start", task.actualStartedAt ? "작업을 재개했습니다" : "작업을 시작했습니다");
  const complete = useCommandRunner("complete", "작업을 완료했습니다");
  const resolve = useCommandRunner("resolveBlocker", "차단을 해제했습니다");
  const unassign = useCommandRunner("unassign", "배정을 해제했습니다");
  if (!session) return null;

  const tz = session.timezone;
  const actions = availableTaskActions(task, session);
  const status = labelOf(taskStatusLabels, task.status);
  const inlineError = start.error ?? complete.error ?? resolve.error ?? unassign.error;
  const retry = start.error ? start.retry : complete.error ? complete.retry : resolve.error ? resolve.retry : unassign.retry;

  return (
    <Card as="article" className={`space-y-3 ${highlighted ? "ring-2 ring-sky-500" : ""}`}>
      <div className="flex flex-wrap items-center gap-2">
        <Link href={`/rooms/${task.roomId}`} className="font-semibold underline-offset-2 hover:underline">
          {task.roomNumber}호
        </Link>
        <span className="text-sm">{taskTypeLabels[task.type] ?? task.type}</span>
        <Badge tone={status.tone} label={status.label} prefix="작업 상태" />
        {task.statusBeforeBlocked ? <span className="text-xs text-zinc-500">차단 전: {labelOf(taskStatusLabels, task.statusBeforeBlocked).label}</span> : null}
        {task.actualStartedAt && task.status === "PENDING" ? <Badge tone="danger" label="담당자 없음 · 진행 중단" /> : null}
      </div>

      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4">
        <div>
          <dt className="text-xs text-zinc-500">예정</dt>
          <dd>{formatInstant(task.scheduledAt, tz)}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">예상 소요</dt>
          <dd>
            {task.estimatedDurationMinutes}분{task.durationSource === "CONFIG_DEFAULT" ? <span className="text-xs text-zinc-500"> (기본값)</span> : null}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">담당</dt>
          <dd>{task.assignee?.displayName ?? "미배정"}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">시작</dt>
          <dd>{formatInstant(task.actualStartedAt, tz)}</dd>
        </div>
        <div className="col-span-2 sm:col-span-4">
          <dt className="sr-only">생성 원인</dt>
          <dd className="text-xs text-zinc-500">
            생성: {taskOriginLabels[task.origin] ?? task.origin}
            {task.originReason ? ` — ${task.originReason}` : ""}
            {task.notes ? ` · 메모: ${task.notes}` : ""}
          </dd>
        </div>
      </dl>

      {task.activeBlockers.length ? (
        <ul aria-label="활성 차단 사유" className="space-y-1">
          {task.activeBlockers.map((b) => (
            <li key={b.blockerId} className="flex flex-wrap items-center gap-2 rounded-md bg-rose-50 px-2 py-1 text-sm text-rose-900">
              <span aria-hidden="true">!</span>
              {blockerTypeLabels[b.type] ?? b.type}
              {b.description ? ` — ${b.description}` : ""}
              <span className="text-xs">해제 예상 {b.estimatedResolutionAt ? formatInstant(b.estimatedResolutionAt, tz) : "없음"}</span>
              {actions.includes("resolve") ? (
                <Button className="ml-auto" disabled={resolve.pending} onClick={() => resolve.execute(task.taskId, b.blockerId, undefined)}>
                  해제
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      {actions.length ? (
        <div className="flex flex-wrap gap-2">
          {actions.includes("start") ? (
            <Button variant="primary" disabled={start.pending} onClick={() => start.execute(task.taskId)}>
              {task.actualStartedAt ? "재개" : "시작"}
            </Button>
          ) : null}
          {actions.includes("complete") ? (
            <Button variant="primary" disabled={complete.pending} onClick={() => complete.execute(task.taskId)}>
              완료
            </Button>
          ) : null}
          {actions.includes("inspect") ? (
            <Button variant="primary" onClick={() => setDialog("inspect")}>
              검사 결과 입력
            </Button>
          ) : null}
          {actions.includes("assign") ? <Button onClick={() => setDialog("assign")}>{task.assignee ? "재배정" : "배정"}</Button> : null}
          {actions.includes("unassign") && task.assignee ? (
            <Button disabled={unassign.pending} onClick={() => unassign.execute(task.taskId, task.assignee!.assignmentId)}>
              배정 해제
            </Button>
          ) : null}
          {actions.includes("block") ? <Button onClick={() => setDialog("block")}>차단 등록</Button> : null}
          {actions.includes("cancel") ? (
            <Button variant="ghost" onClick={() => setDialog("cancel")}>
              작업 취소
            </Button>
          ) : null}
        </div>
      ) : null}

      <ErrorNotice error={inlineError} onRetry={retry} />

      {dialog === "assign" ? <AssignDialog task={task} open onClose={() => setDialog(null)} /> : null}
      {dialog === "block" ? <BlockDialog task={task} open onClose={() => setDialog(null)} /> : null}
      {dialog === "inspect" ? <InspectionDialog task={task} open onClose={() => setDialog(null)} /> : null}
      {dialog === "cancel" ? <CancelDialog task={task} open onClose={() => setDialog(null)} /> : null}
    </Card>
  );
}
