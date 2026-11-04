"use client";

import Link from "next/link";
import { useReservations } from "@/api/hooks";
import type { components } from "@/api/schema";
import { useDemoSession } from "@/app/providers";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge, Button, Card, EmptyState, LoadingState } from "@/components/ui";
import { formatInstant } from "@/lib/format";
import { labelOf, reservationStatusLabels } from "@/lib/labels";
import { useCommandRunner } from "../commands";

type Reservation = components["schemas"]["ReservationListItem"];

/** 예약: 도착 예정 체크인, 투숙 중 체크아웃 (02 §4). 체크아웃 거부(STAYOVER_IN_PROGRESS)는 원인과 해결 방법을 안내한다. */
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
  return (
    <Card as="li" className="space-y-2 p-3">
      <div className="flex flex-wrap items-center gap-3">
        <Link href={`/rooms/${r.roomId}`} className="font-semibold underline-offset-2 hover:underline">
          {r.roomNumber}호
        </Link>
        <Badge tone={status.tone} label={status.label} prefix="예약 상태" />
        <span className="text-sm text-zinc-600">
          {formatInstant(r.scheduledCheckInAt, tz)} → {formatInstant(r.scheduledCheckOutAt, tz)} · {r.guestCount}명
        </span>
        <Button className="ml-auto" variant="primary" disabled={runner.pending} onClick={() => runner.execute(r.reservationId)}>
          {runner.pending ? "처리 중…" : action === "checkIn" ? "체크인" : "체크아웃"}
        </Button>
      </div>
      <ErrorNotice error={runner.error} onRetry={runner.retry} />
    </Card>
  );
}
