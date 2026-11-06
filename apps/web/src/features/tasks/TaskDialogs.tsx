"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useWatch } from "react-hook-form";
import { z } from "zod";
import type { TaskListItem } from "@/api/endpoints";
import { useStaffWorkloads } from "@/api/hooks";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button, Field, inputClass } from "@/components/ui";
import { blockerTypeLabels, flagLabels, taskTypeLabels } from "@/lib/labels";
import { useCommandRunner } from "../commands";
import { assigneeRoleFor } from "./taskActions";

type DialogProps = { task: TaskListItem; open: boolean; onClose: () => void };

/** 제출 시점 기준 N분 뒤 (렌더 중이 아니라 이벤트 처리 중에 호출된다). */
function minutesFromNow(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

const reason = z.string().trim().min(1, "사유를 입력하세요").max(500, "500자 이내로 입력하세요");

// ---------------------------------------------------------------- assign

const assignSchema = z.object({ staffId: z.string().min(1, "담당자를 선택하세요") });

export function AssignDialog({ task, open, onClose }: DialogProps) {
  const runner = useCommandRunner("assign", "담당자를 배정했습니다");
  const workloads = useStaffWorkloads();
  const role = assigneeRoleFor(task.type);
  const candidates = (workloads.data?.staff ?? []).filter((s) => s.role === role);
  const form = useForm<z.infer<typeof assignSchema>>({ resolver: zodResolver(assignSchema), defaultValues: { staffId: task.assignee?.staffId ?? "" } });

  const submit = form.handleSubmit(async ({ staffId }) => {
    const outcome = await runner.execute(task.taskId, staffId);
    if (outcome) onClose();
  });

  return (
    <Dialog open={open} title={`${task.roomNumber}호 ${taskTypeLabels[task.type]} 담당자 배정`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Field label={`담당자 (${role === "INSPECTOR" ? "검사" : "하우스키핑"})`} htmlFor="assign-staff" error={form.formState.errors.staffId?.message}>
          <select id="assign-staff" className={inputClass} {...form.register("staffId")}>
            <option value="">선택</option>
            {candidates.map((s) => (
              <option key={s.staffId} value={s.staffId}>
                {s.displayName} · 배정 {s.activeTaskCount}건/{s.assignedEstimatedMinutes}분
                {s.flags.length ? ` · ${s.flags.map((f) => flagLabels[f] ?? f).join(", ")}` : ""}
              </option>
            ))}
          </select>
        </Field>
        <ErrorNotice error={runner.error} onRetry={runner.retry} />
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={runner.pending}>
            {runner.pending ? "배정 중…" : "배정"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

// ----------------------------------------------------------------- block

const blockSchema = z
  .object({
    type: z.enum(["DND", "LINEN_SHORTAGE", "SUPPLY_SHORTAGE", "MAINTENANCE", "STAFF_UNAVAILABLE", "OTHER"]),
    description: z.string().trim().max(500, "500자 이내로 입력하세요"),
    resolveInMinutes: z.string().regex(/^$|^\d+$/, "숫자로 입력하세요"),
  })
  .refine((v) => v.type !== "OTHER" || v.description.length > 0, { path: ["description"], message: "기타 사유는 설명이 필요합니다" })
  .refine((v) => v.resolveInMinutes === "" || (Number(v.resolveInMinutes) >= 1 && Number(v.resolveInMinutes) <= 480), {
    path: ["resolveInMinutes"],
    message: "1~480분 사이로 입력하세요",
  });

export function BlockDialog({ task, open, onClose }: DialogProps) {
  const runner = useCommandRunner("addBlocker", "차단 사유를 등록했습니다");
  const form = useForm<z.infer<typeof blockSchema>>({
    resolver: zodResolver(blockSchema),
    defaultValues: { type: "LINEN_SHORTAGE", description: "", resolveInMinutes: "" },
  });

  const submit = form.handleSubmit(async ({ type, description, resolveInMinutes }) => {
    const estimatedResolutionAt = resolveInMinutes ? minutesFromNow(Number(resolveInMinutes)) : undefined;
    const outcome = await runner.execute(task.taskId, { type, description: description || undefined, estimatedResolutionAt });
    if (outcome) onClose();
  });

  return (
    <Dialog open={open} title={`${task.roomNumber}호 작업 차단 등록`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Field label="차단 사유" htmlFor="block-type">
          <select id="block-type" className={inputClass} {...form.register("type")}>
            {Object.entries(blockerTypeLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="설명" htmlFor="block-description" error={form.formState.errors.description?.message}>
          <input id="block-description" className={inputClass} {...form.register("description")} />
        </Field>
        <Field
          label="예상 해제까지 (분, 선택)"
          htmlFor="block-eta"
          hint="비워 두면 준비 예측이 '예측 불가'가 됩니다 — 시간을 짐작해 넣지 않습니다."
          error={form.formState.errors.resolveInMinutes?.message}
        >
          <input id="block-eta" inputMode="numeric" className={inputClass} {...form.register("resolveInMinutes")} />
        </Field>
        <ErrorNotice error={runner.error} onRetry={runner.retry} />
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={runner.pending}>
            {runner.pending ? "등록 중…" : "차단 등록"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

// ------------------------------------------------------------ inspection

const CHECKLIST = [
  ["bathroom", "욕실"],
  ["bed", "침구"],
  ["floor", "바닥"],
  ["amenities", "비품"],
] as const;

const inspectionSchema = z
  .object({
    result: z.enum(["PASSED", "FAILED"]),
    failureReason: z.string().trim().max(500, "500자 이내로 입력하세요"),
    bathroom: z.boolean(),
    bed: z.boolean(),
    floor: z.boolean(),
    amenities: z.boolean(),
  })
  .refine((v) => v.result === "PASSED" || v.failureReason.length > 0, { path: ["failureReason"], message: "실패 사유를 입력하세요" });

export function InspectionDialog({ task, open, onClose }: DialogProps) {
  const runner = useCommandRunner("inspectionResult", "검사 결과를 제출했습니다");
  const form = useForm<z.infer<typeof inspectionSchema>>({
    resolver: zodResolver(inspectionSchema),
    defaultValues: { result: "PASSED", failureReason: "", bathroom: true, bed: true, floor: true, amenities: true },
  });
  const failed = useWatch({ control: form.control, name: "result" }) === "FAILED";

  const submit = form.handleSubmit(async (v) => {
    const checklist = Object.fromEntries(CHECKLIST.map(([key]) => [key, v[key]]));
    const outcome = await runner.execute(task.taskId, { result: v.result, failureReason: v.result === "FAILED" ? v.failureReason : undefined, checklist });
    if (outcome) onClose();
  });

  return (
    <Dialog open={open} title={`${task.roomNumber}호 검사 결과`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">체크리스트</legend>
          {CHECKLIST.map(([key, label]) => (
            <label key={key} className="mr-4 inline-flex items-center gap-1 text-sm">
              <input type="checkbox" {...form.register(key)} /> {label}
            </label>
          ))}
        </fieldset>
        <fieldset className="space-y-1">
          <legend className="text-sm font-medium">결과</legend>
          <label className="mr-4 inline-flex items-center gap-1 text-sm">
            <input type="radio" value="PASSED" {...form.register("result")} /> 통과 → 객실 준비 완료
          </label>
          <label className="inline-flex items-center gap-1 text-sm">
            <input type="radio" value="FAILED" {...form.register("result")} /> 실패 → 재청소 작업 생성
          </label>
        </fieldset>
        {failed ? (
          <Field label="실패 사유" htmlFor="inspection-reason" error={form.formState.errors.failureReason?.message}>
            <input id="inspection-reason" className={inputClass} {...form.register("failureReason")} />
          </Field>
        ) : null}
        <ErrorNotice error={runner.error} onRetry={runner.retry} />
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={runner.pending}>
            {runner.pending ? "제출 중…" : "결과 제출"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------------------------------------------------------------- cancel

const cancelSchema = z.object({ reason });

export function CancelDialog({ task, open, onClose }: DialogProps) {
  const runner = useCommandRunner("cancel", "작업을 취소했습니다");
  const form = useForm<z.infer<typeof cancelSchema>>({ resolver: zodResolver(cancelSchema), defaultValues: { reason: "" } });
  const started = Boolean(task.actualStartedAt);

  const submit = form.handleSubmit(async ({ reason: text }) => {
    const outcome = await runner.execute(task.taskId, text);
    if (outcome) onClose();
  });

  return (
    <Dialog open={open} title={`${task.roomNumber}호 ${taskTypeLabels[task.type]} 취소`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <p className="text-sm text-zinc-700">
          {started
            ? task.type === "STAYOVER_CLEANING"
              ? "이미 시작한 작업입니다. 취소하면 객실은 투숙 중 상태로 돌아갑니다."
              : "이미 시작한 작업입니다. 취소하면 객실은 청소 전 상태로 돌아가며, 필요하면 재정비 작업을 만들어야 합니다."
            : "시작 전 작업이므로 객실 상태는 바뀌지 않습니다."}
        </p>
        <Field label="취소 사유" htmlFor="cancel-reason" error={form.formState.errors.reason?.message}>
          <input id="cancel-reason" className={inputClass} {...form.register("reason")} />
        </Field>
        <ErrorNotice error={runner.error} onRetry={runner.retry} />
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>닫기</Button>
          <Button type="submit" variant="danger" disabled={runner.pending}>
            {runner.pending ? "취소 중…" : "작업 취소"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
