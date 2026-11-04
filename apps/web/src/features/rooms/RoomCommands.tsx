"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import type { RoomView } from "@/api/endpoints";
import { useDemoSession } from "@/app/providers";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button, Field, inputClass } from "@/components/ui";
import { useCommandRunner } from "../commands";

const reason = z.string().trim().min(1, "사유를 입력하세요").max(500, "500자 이내로 입력하세요");
const optionalMinutes = z.string().regex(/^$|^\d+$/, "숫자로 입력하세요").refine((v) => v === "" || (Number(v) >= 1 && Number(v) <= 480), "1~480분 사이로 입력하세요");

/**
 * 객실 기준 명령 (02 §4): 투숙 중 청소 요청, 관리자 재정비, 판매 중단/해제.
 * 버튼은 역할·단계로 거르지만 최종 판단은 서버다. 재검사는 "검사 대기" 단계에서만 의미가 있다.
 */
export function RoomCommands({ room }: { room: RoomView }) {
  const { session } = useDemoSession();
  const [open, setOpen] = useState<"stayover" | "rework-cleaning" | "rework-inspection" | "availability" | null>(null);
  if (!session) return null;
  const isManager = session.role === "MANAGER";
  const canStayover = (isManager || session.role === "FRONT_DESK") && room.workflowStage === "OCCUPIED" && !room.activeTask;
  const canReworkCleaning = isManager && room.workflowStage === "NEEDS_CLEANING" && !room.activeTask;
  const canReworkInspection = isManager && room.workflowStage === "AWAITING_INSPECTION" && !room.activeTask;

  if (!canStayover && !canReworkCleaning && !canReworkInspection && !isManager) return null;
  return (
    <div className="flex flex-wrap gap-2">
      {canStayover ? <Button onClick={() => setOpen("stayover")}>투숙 중 청소 요청</Button> : null}
      {canReworkCleaning ? <Button onClick={() => setOpen("rework-cleaning")}>재청소 작업 만들기</Button> : null}
      {canReworkInspection ? <Button onClick={() => setOpen("rework-inspection")}>재검사 작업 만들기</Button> : null}
      {isManager ? (
        <Button variant={room.availabilityStatus === "AVAILABLE" ? "danger" : "secondary"} onClick={() => setOpen("availability")}>
          {room.availabilityStatus === "AVAILABLE" ? "판매 중단" : "판매 재개"}
        </Button>
      ) : null}

      {open === "stayover" ? <StayoverDialog room={room} onClose={() => setOpen(null)} /> : null}
      {open === "rework-cleaning" ? <ReworkDialog room={room} kind="CLEANING" onClose={() => setOpen(null)} /> : null}
      {open === "rework-inspection" ? <ReworkDialog room={room} kind="INSPECTION" onClose={() => setOpen(null)} /> : null}
      {open === "availability" ? <AvailabilityDialog room={room} onClose={() => setOpen(null)} /> : null}
    </div>
  );
}

const stayoverSchema = z.object({ notes: z.string().trim().max(500, "500자 이내로 입력하세요"), minutes: optionalMinutes });

function StayoverDialog({ room, onClose }: { room: RoomView; onClose: () => void }) {
  const runner = useCommandRunner("createStayover", "투숙 중 청소를 요청했습니다");
  const form = useForm<z.infer<typeof stayoverSchema>>({ resolver: zodResolver(stayoverSchema), defaultValues: { notes: "", minutes: "" } });
  const submit = form.handleSubmit(async ({ notes, minutes }) => {
    const outcome = await runner.execute(room.roomId, { notes: notes || undefined, estimatedDurationMinutes: minutes ? Number(minutes) : undefined });
    if (outcome) onClose();
  });
  return (
    <Dialog open title={`${room.roomNumber}호 투숙 중 청소 요청`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Field label="요청 메모" htmlFor="stayover-notes" hint="고객 이름·연락처 등 개인정보는 적지 마세요." error={form.formState.errors.notes?.message}>
          <input id="stayover-notes" className={inputClass} {...form.register("notes")} />
        </Field>
        <Field label="예상 소요 (분, 선택)" htmlFor="stayover-minutes" hint="비우면 설정 기본값을 씁니다." error={form.formState.errors.minutes?.message}>
          <input id="stayover-minutes" inputMode="numeric" className={inputClass} {...form.register("minutes")} />
        </Field>
        <ErrorNotice error={runner.error} onRetry={runner.retry} />
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={runner.pending}>요청</Button>
        </div>
      </form>
    </Dialog>
  );
}

const reworkSchema = z.object({ reason, minutes: optionalMinutes });

function ReworkDialog({ room, kind, onClose }: { room: RoomView; kind: "CLEANING" | "INSPECTION"; onClose: () => void }) {
  const runner = useCommandRunner("createRework", kind === "CLEANING" ? "재청소 작업을 만들었습니다" : "재검사 작업을 만들었습니다");
  const form = useForm<z.infer<typeof reworkSchema>>({ resolver: zodResolver(reworkSchema), defaultValues: { reason: "", minutes: "" } });
  const submit = form.handleSubmit(async ({ reason: text, minutes }) => {
    const outcome = await runner.execute(room.roomId, { kind, reason: text, estimatedDurationMinutes: minutes ? Number(minutes) : undefined });
    if (outcome) onClose();
  });
  return (
    <Dialog open title={`${room.roomNumber}호 ${kind === "CLEANING" ? "재청소" : "재검사"} 작업`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <p className="text-sm text-zinc-700">작업을 만들어도 객실 상태는 바로 바뀌지 않습니다. 담당자가 시작할 때 바뀝니다.</p>
        <Field label="사유" htmlFor="rework-reason" error={form.formState.errors.reason?.message}>
          <input id="rework-reason" className={inputClass} {...form.register("reason")} />
        </Field>
        <Field label="예상 소요 (분, 선택)" htmlFor="rework-minutes" error={form.formState.errors.minutes?.message}>
          <input id="rework-minutes" inputMode="numeric" className={inputClass} {...form.register("minutes")} />
        </Field>
        <ErrorNotice error={runner.error} onRetry={runner.retry} />
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={runner.pending}>만들기</Button>
        </div>
      </form>
    </Dialog>
  );
}

const availabilitySchema = z.object({ reason });

function AvailabilityDialog({ room, onClose }: { room: RoomView; onClose: () => void }) {
  const toOos = room.availabilityStatus === "AVAILABLE";
  const runner = useCommandRunner("changeAvailability", toOos ? "판매를 중단했습니다" : "판매를 재개했습니다");
  const form = useForm<z.infer<typeof availabilitySchema>>({ resolver: zodResolver(availabilitySchema), defaultValues: { reason: "" } });
  const submit = form.handleSubmit(async ({ reason: text }) => {
    const outcome = await runner.execute(room.roomId, { status: toOos ? "OUT_OF_SERVICE" : "AVAILABLE", reason: text });
    if (outcome) onClose();
  });
  return (
    <Dialog open title={`${room.roomNumber}호 ${toOos ? "판매 중단" : "판매 재개"}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <p className="text-sm text-zinc-700">
          {toOos
            ? "신규 체크인만 막습니다. 투숙 중인 예약이나 진행 중인 작업은 바꾸지 않습니다."
            : "판매만 재개합니다. 객실이 준비 완료 상태가 되는 것은 아닙니다 — 필요하면 재청소·재검사를 만드세요."}
        </p>
        <Field label="사유" htmlFor="availability-reason" error={form.formState.errors.reason?.message}>
          <input id="availability-reason" className={inputClass} {...form.register("reason")} />
        </Field>
        <ErrorNotice error={runner.error} onRetry={runner.retry} />
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant={toOos ? "danger" : "primary"} disabled={runner.pending}>
            {toOos ? "판매 중단" : "판매 재개"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
