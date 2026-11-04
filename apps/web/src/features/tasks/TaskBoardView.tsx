"use client";

import { useRouter, useSearchParams } from "next/navigation";
import type { TasksQuery } from "@/api/endpoints";
import { useTasks } from "@/api/hooks";
import { useDemoSession } from "@/app/providers";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button, EmptyState, LoadingState } from "@/components/ui";
import { taskStatusLabels, taskTypeLabels } from "@/lib/labels";
import { TaskCard } from "./TaskCard";

type Status = NonNullable<TasksQuery["status"]>[number];
type Type = NonNullable<TasksQuery["type"]>[number];
const DEFAULT_STATUSES: Status[] = ["PENDING", "ASSIGNED", "IN_PROGRESS", "BLOCKED"];

/**
 * 작업 보드 (03 §3 Task board). 필터는 URL query(`?status=…&type=…&focus=`)에 둔다.
 * 하우스키핑·검사 역할은 서버가 본인 작업만 돌려준다 (04 §5).
 */
export function TaskBoardView() {
  const params = useSearchParams();
  const router = useRouter();
  const { session } = useDemoSession();
  const statuses = (params.getAll("status") as Status[]).length ? (params.getAll("status") as Status[]) : DEFAULT_STATUSES;
  const types = params.getAll("type") as Type[];
  const focus = params.get("focus");
  const tasks = useTasks({ status: statuses, type: types.length ? types : undefined });
  const items = tasks.data?.pages.flatMap((p) => p.items) ?? [];

  const toggle = (key: "status" | "type", value: string, current: string[]) => {
    const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
    const query = new URLSearchParams();
    (key === "status" ? next : statuses).forEach((s) => query.append("status", s));
    (key === "type" ? next : types).forEach((t) => query.append("type", t));
    router.replace(`/tasks?${query.toString()}`);
  };

  return (
    <div className="space-y-4">
      {session?.role === "HOUSEKEEPER" || session?.role === "INSPECTOR" ? (
        <p className="text-sm text-zinc-600">본인에게 배정된 작업만 표시합니다.</p>
      ) : null}
      <fieldset className="flex flex-wrap gap-2">
        <legend className="mb-1 w-full text-sm font-medium">상태</legend>
        {Object.entries(taskStatusLabels).map(([value, { label }]) => (
          <label key={value} className="inline-flex items-center gap-1 rounded-full border border-zinc-300 px-3 py-1 text-sm has-[:checked]:border-zinc-900 has-[:checked]:bg-zinc-900 has-[:checked]:text-white">
            <input type="checkbox" className="sr-only" checked={statuses.includes(value as Status)} onChange={() => toggle("status", value, statuses)} />
            {label}
          </label>
        ))}
      </fieldset>
      <fieldset className="flex flex-wrap gap-2">
        <legend className="mb-1 w-full text-sm font-medium">작업 유형</legend>
        {Object.entries(taskTypeLabels).map(([value, label]) => (
          <label key={value} className="inline-flex items-center gap-1 rounded-full border border-zinc-300 px-3 py-1 text-sm has-[:checked]:border-zinc-900 has-[:checked]:bg-zinc-900 has-[:checked]:text-white">
            <input type="checkbox" className="sr-only" checked={types.includes(value as Type)} onChange={() => toggle("type", value, types)} />
            {label}
          </label>
        ))}
      </fieldset>

      {tasks.isPending ? <LoadingState label="작업 불러오는 중" /> : null}
      {tasks.isError ? <ErrorNotice error={tasks.error} /> : null}
      {tasks.isSuccess && items.length === 0 ? <EmptyState>조건에 맞는 작업이 없습니다.</EmptyState> : null}
      <div className="grid gap-3 lg:grid-cols-2">
        {items.map((t) => (
          <TaskCard key={t.taskId} task={t} highlighted={t.taskId === focus} />
        ))}
      </div>
      {tasks.hasNextPage ? (
        <Button onClick={() => tasks.fetchNextPage()} disabled={tasks.isFetchingNextPage}>
          {tasks.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </Button>
      ) : null}
    </div>
  );
}
