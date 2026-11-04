"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import type { RoomsQuery } from "@/api/endpoints";
import { useRooms } from "@/api/hooks";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge, Button, Card, EmptyState, Field, inputClass, LoadingState } from "@/components/ui";
import { availabilityLabels, flagLabels, labelOf, taskStatusLabels, taskTypeLabels, workflowStageLabels } from "@/lib/labels";

type Stage = NonNullable<RoomsQuery["stage"]>;

/** 객실 목록. 필터는 URL query(`?stage=`)에 둔다 (03 §6). */
export function RoomsView() {
  const params = useSearchParams();
  const router = useRouter();
  const stage = (params.get("stage") as Stage | null) ?? undefined;
  const rooms = useRooms(stage ? { stage } : {});
  const items = rooms.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="space-y-4">
      <div className="max-w-xs">
        <Field label="객실 단계" htmlFor="stage-filter">
          <select
            id="stage-filter"
            className={inputClass}
            value={stage ?? ""}
            onChange={(e) => router.replace(e.target.value ? `/rooms?stage=${e.target.value}` : "/rooms")}
          >
            <option value="">전체</option>
            {Object.entries(workflowStageLabels).map(([value, { label }]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
      </div>

      {rooms.isPending ? <LoadingState label="객실 불러오는 중" /> : null}
      {rooms.isError ? <ErrorNotice error={rooms.error} /> : null}
      {rooms.isSuccess && items.length === 0 ? <EmptyState>조건에 맞는 객실이 없습니다.</EmptyState> : null}

      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((room) => {
          const stageLabel = labelOf(workflowStageLabels, room.workflowStage);
          return (
            <Card key={room.roomId} as="li" className="space-y-2">
              <div className="flex items-center justify-between">
                <Link href={`/rooms/${room.roomId}`} className="text-lg font-semibold underline-offset-2 hover:underline">
                  {room.roomNumber}호
                </Link>
                <span className="text-xs text-zinc-500">
                  {room.roomType} · {room.floor}층
                </span>
              </div>
              <div className="flex flex-wrap gap-1">
                <Badge tone={stageLabel.tone} label={stageLabel.label} prefix="업무 단계" />
                {room.availabilityStatus === "OUT_OF_SERVICE" ? (
                  <Badge tone="danger" label={labelOf(availabilityLabels, room.availabilityStatus).label} prefix="판매" title={room.availabilityReason ?? undefined} />
                ) : null}
                {room.alerts.map((a) => (
                  <Badge key={a} tone="danger" label={flagLabels[a] ?? a} />
                ))}
              </div>
              {room.activeTask ? (
                <p className="text-sm text-zinc-700">
                  {taskTypeLabels[room.activeTask.type]} · {labelOf(taskStatusLabels, room.activeTask.status).label}
                </p>
              ) : (
                <p className="text-sm text-zinc-500">진행 중인 작업 없음</p>
              )}
            </Card>
          );
        })}
      </ul>
      {rooms.hasNextPage ? (
        <Button onClick={() => rooms.fetchNextPage()} disabled={rooms.isFetchingNextPage}>
          {rooms.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </Button>
      ) : null}
    </div>
  );
}
