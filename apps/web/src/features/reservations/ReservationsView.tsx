"use client";

import Link from "next/link";
import { useReservations } from "@/api/hooks";
import type { components } from "@/api/schema";
import { useDemoSession } from "@/app/providers";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge, Button, Card, EmptyState, LoadingState } from "@/components/ui";
import { formatInstant } from "@/lib/format";
import { availabilityLabels, labelOf, reservationStatusLabels, workflowStageLabels } from "@/lib/labels";
import { useCommandRunner } from "../commands";

type Reservation = components["schemas"]["ReservationListItem"];

/**
 * 예약: 도착 예정 체크인, 투숙 중 체크아웃 (02 §4). 체크아웃 거부(STAYOVER_IN_PROGRESS)는 원인과 해결 방법을 안내한다.
 * 각 예약에 객실의 업무 단계·판매 상태를 보여 주고, 체크인할 수 없는 객실은 누르기 전에 이유와 할 일을 알려 준다.
 */
export function ReservationsView() {
  const arrivals = useReservations({ status: ["CONFIRMED"] });
  const inHouse = useReservations({ status: ["CHECKED_IN"] });

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <ReservationSection title="도착 예정" query={arrivals} action="checkIn" empty="도착 예정 예약이 없습니다." />
      <ReservationSection title="투숙 중" query={inHouse} action="checkOut" empty="투숙 중인 예약이 없습니다." />
    </div>
  );
}

function ReservationSection({
  title,
  query,
  action,
  empty,
}: {
  title: string;
  query: ReturnType<typeof useReservations>;
  action: "checkIn" | "checkOut";
  empty: string;
}) {
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <section className="space-y-2" aria-label={title}>
      <h2 className="text-lg font-semibold">{title}</h2>
      {query.isPending ? <LoadingState /> : null}
      {query.isError ? <ErrorNotice error={query.error} /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyState>{empty}</EmptyState> : null}
      <ul className="space-y-2">
        {items.map((r) => (
          <ReservationRow key={r.reservationId} reservation={r} action={action} />
        ))}
      </ul>
      {query.hasNextPage ? <Button onClick={() => query.fetchNextPage()}>더 보기</Button> : null}
    </section>
  );
}

function ReservationRow({ reservation: r, action }: { reservation: Reservation; action: "checkIn" | "checkOut" }) {
  const { session } = useDemoSession();
  const runner = useCommandRunner(action, action === "checkIn" ? `${r.roomNumber}호 체크인했습니다` : `${r.roomNumber}호 체크아웃했습니다`);
  const tz = session?.timezone ?? "UTC";
  const status = labelOf(reservationStatusLabels, r.status);
  // 객실 상태를 모르면(예: 이전 버전 API) 막지 않고 서버 판단에 맡긴다 — 모르는 것을 "준비 안 됨"으로 단정하지 않는다
  const stageKnown = Boolean(r.roomWorkflowStage);
  const stage = labelOf(workflowStageLabels, r.roomWorkflowStage);
  const outOfService = r.roomAvailabilityStatus === "OUT_OF_SERVICE";
  const hint = !stageKnown ? null : action === "checkIn" ? checkInHint(r.roomWorkflowStage, outOfService) : checkOutHint(r.roomWorkflowStage);
  const blocked = action === "checkIn" && hint !== null; // 서버도 같은 조건으로 거부한다 — 화면은 미리 알려 줄 뿐
  const hintId = `hint-${r.reservationId}`;
  return (
    <Card as="li" className="space-y-2 p-3">
      <div className="flex flex-wrap items-center gap-3">
        <Link href={`/rooms/${r.roomId}`} className="font-semibold underline-offset-2 hover:underline">
          {r.roomNumber}호
        </Link>
        <Badge tone={status.tone} label={status.label} prefix="예약 상태" />
        {stageKnown ? <Badge tone={stage.tone} label={`객실 ${stage.label}`} prefix="객실 업무 단계" /> : null}
        {outOfService ? <Badge tone={availabilityLabels.OUT_OF_SERVICE.tone} label={availabilityLabels.OUT_OF_SERVICE.label} prefix="판매 상태" /> : null}
        <span className="text-sm text-zinc-600">
          {formatInstant(r.scheduledCheckInAt, tz)} → {formatInstant(r.scheduledCheckOutAt, tz)} · {r.guestCount}명
        </span>
        <Button
          className="ml-auto"
          variant="primary"
          disabled={runner.pending || blocked}
          aria-describedby={hint ? hintId : undefined}
          onClick={() => runner.execute(r.reservationId)}
        >
          {runner.pending ? "처리 중…" : action === "checkIn" ? "체크인" : "체크아웃"}
        </Button>
      </div>
      {hint ? (
        <p id={hintId} className="text-sm text-zinc-600">
          {hint}{" "}
          <Link href={`/rooms/${r.roomId}`} className="underline underline-offset-2">
            객실 보기
          </Link>
        </p>
      ) : null}
      <ErrorNotice error={runner.error} onRetry={runner.retry} />
    </Card>
  );
}

/** 체크인 조건(02 §3.4): 객실 준비 완료 + 판매 가능. 안 되면 지금 무엇이 남았는지 알려 준다. */
export function checkInHint(stage: string, outOfService: boolean): string | null {
  if (stage === "READY") return outOfService ? "판매 중단된 객실이라 체크인할 수 없습니다. 관리자가 객실 상세에서 판매를 재개해야 합니다." : null;
  const next: Record<string, string> = {
    NEEDS_CLEANING: "청소 배정·청소·검사가 남았습니다.",
    CLEANING: "청소 완료와 검사가 남았습니다.",
    AWAITING_INSPECTION: "검사 배정과 검사가 남았습니다.",
    INSPECTING: "검사 결과 입력이 남았습니다.",
    OCCUPIED: "이전 손님이 아직 투숙 중입니다. 체크아웃 후 청소·검사가 필요합니다.",
    STAYOVER_CLEANING: "이전 손님이 아직 투숙 중입니다. 체크아웃 후 청소·검사가 필요합니다.",
  };
  return `객실이 준비 완료가 되어야 체크인할 수 있습니다 — ${next[stage] ?? "객실 상태를 확인하세요."}`;
}

/** 체크아웃은 시작된 투숙 중 청소가 있으면 거부된다. 누르면 서버가 해결 방법을 함께 알려 준다. */
export function checkOutHint(stage: string): string | null {
  return stage === "STAYOVER_CLEANING" ? "투숙 중 청소가 진행 중이라 지금은 체크아웃이 거부됩니다. 청소를 마치거나 관리자가 취소해야 합니다." : null;
}
