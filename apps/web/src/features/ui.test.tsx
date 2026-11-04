import { describe, expect, test, vi } from "vitest";
import { useState } from "react";
import { fireEvent, render as renderPlain, screen, waitFor, within } from "@testing-library/react";
import type { TaskListItem } from "@/api/endpoints";
import { ApiError } from "@/api/errors";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge } from "@/components/ui";
import { formatInstant, formatRisk } from "@/lib/format";
import { gradeLabels, taskStatusLabels, workflowStageLabels } from "@/lib/labels";
import { renderWithApp, session } from "@/test/render";
import { DashboardView } from "./dashboard/DashboardView";
import { TaskCard } from "./tasks/TaskCard";
import { availableTaskActions } from "./tasks/taskActions";

function task(overrides: Partial<TaskListItem> = {}): TaskListItem {
  return {
    taskId: "t-1", roomId: "r-1", roomNumber: "203", type: "CHECKOUT_CLEANING", status: "ASSIGNED", origin: "CHECKOUT_COMMAND",
    scheduledAt: "2026-11-05T01:00:00Z", estimatedDurationMinutes: 45, durationSource: "CONFIG_DEFAULT", activeBlockers: [], version: 1,
    assignee: { assignmentId: "a-1", staffId: "staff-self", displayName: "HK-01" },
    ...overrides,
  } as TaskListItem;
}

// ------------------------------------------------------------ action rules

describe("availableTaskActions (03 §3, 04 §5)", () => {
  const hk = { staffId: "staff-self", role: "HOUSEKEEPER" };
  const manager = { staffId: "mgr", role: "MANAGER" };
  const frontDesk = { staffId: "fd", role: "FRONT_DESK" };

  test("assignee starts an assigned task; a manager cannot start it on their behalf", () => {
    expect(availableTaskActions(task(), hk)).toContain("start");
    expect(availableTaskActions(task(), manager)).not.toContain("start");
    expect(availableTaskActions(task(), manager)).toEqual(expect.arrayContaining(["assign", "unassign", "block", "cancel"]));
  });

  test("inspection tasks offer the result dialog instead of complete", () => {
    const inspection = task({ type: "INSPECTION", status: "IN_PROGRESS", actualStartedAt: "2026-11-05T01:00:00Z" });
    expect(availableTaskActions(inspection, hk)).toContain("inspect");
    expect(availableTaskActions(inspection, hk)).not.toContain("complete");
  });

  test("started tasks can only be cancelled by a manager, even while blocked (N3)", () => {
    const blockedStarted = task({ type: "STAYOVER_CLEANING", status: "BLOCKED", actualStartedAt: "2026-11-05T01:00:00Z" });
    expect(availableTaskActions(blockedStarted, frontDesk)).not.toContain("cancel");
    expect(availableTaskActions(blockedStarted, hk)).not.toContain("cancel");
    expect(availableTaskActions(blockedStarted, manager)).toContain("cancel");
    expect(availableTaskActions(task({ type: "STAYOVER_CLEANING", status: "PENDING", assignee: undefined }), frontDesk)).toContain("cancel");
  });

  test("terminal tasks offer nothing", () => {
    expect(availableTaskActions(task({ status: "COMPLETED" }), manager)).toEqual([]);
  });
});

// ------------------------------------------------------- status labelling

describe("status presentation (03 §4)", () => {
  test("every badge carries a text label, never color alone", () => {
    for (const map of [workflowStageLabels, taskStatusLabels, gradeLabels]) {
      for (const { label, tone } of Object.values(map)) {
        const { container, unmount } = renderPlain(<Badge tone={tone} label={label} />);
        expect(container.textContent).toContain(label);
        unmount();
      }
    }
  });

  test("times are shown in the property timezone, not the browser's", () => {
    expect(formatInstant("2026-11-04T15:30:00Z", "Asia/Seoul")).toBe("11/05 00:30");
    expect(formatInstant("2026-11-04T15:30:00Z", "America/New_York")).toBe("11/04 10:30");
    expect(formatRisk(25)).toBe("25분 늦음");
    expect(formatRisk(-15)).toBe("15분 여유");
  });
});


// ----------------------------------------------------------------- TaskCard

describe("TaskCard", () => {
  test("start sends the command with an idempotency key and refreshes", async () => {
    const start = vi.fn().mockResolvedValue({ data: {}, replayed: false, correlationId: "c" });
    renderWithApp(<TaskCard task={task()} />, { as: session("HOUSEKEEPER"), api: { commands: { start } } });

    fireEvent.click(screen.getByRole("button", { name: "시작" }));

    await waitFor(() => expect(start).toHaveBeenCalledTimes(1));
    expect(start.mock.calls[0][0]).toBe("t-1");
    expect(start.mock.calls[0][1]).toMatch(/^web-/);
    expect(await screen.findByText("작업을 시작했습니다")).toBeDefined();
  });

  test("a resumed task says 재개 and an interrupted task is flagged", () => {
    renderWithApp(<TaskCard task={task({ actualStartedAt: "2026-11-05T00:00:00Z" })} />, { as: session("HOUSEKEEPER") });
    expect(screen.getByRole("button", { name: "재개" })).toBeDefined();
    cleanupAndRender(<TaskCard task={task({ status: "PENDING", assignee: undefined, actualStartedAt: "2026-11-05T00:00:00Z" })} />);
    expect(screen.getByText("담당자 없음 · 진행 중단")).toBeDefined();
  });

  test("cancel requires a reason before sending", async () => {
    const cancel = vi.fn().mockResolvedValue({ data: {}, replayed: false, correlationId: "c" });
    renderWithApp(<TaskCard task={task({ status: "PENDING", assignee: undefined })} />, { as: session("MANAGER", "mgr"), api: { commands: { cancel } } });

    fireEvent.click(screen.getByRole("button", { name: "작업 취소" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "작업 취소" }));
    expect(await within(dialog).findByText("사유를 입력하세요")).toBeDefined();
    expect(cancel).not.toHaveBeenCalled();

    fireEvent.change(within(dialog).getByLabelText("취소 사유"), { target: { value: "운영 계획 변경" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "작업 취소" }));
    await waitFor(() => expect(cancel).toHaveBeenCalledWith("t-1", "운영 계획 변경", expect.stringMatching(/^web-/)));
  });

  test("a network error offers retrying with the same key", async () => {
    const start = vi.fn().mockRejectedValueOnce(new ApiError(0, "NETWORK_ERROR", {})).mockResolvedValueOnce({ data: {}, replayed: true, correlationId: "c" });
    renderWithApp(<TaskCard task={task()} />, { as: session("HOUSEKEEPER"), api: { commands: { start } } });

    fireEvent.click(screen.getByRole("button", { name: "시작" }));
    fireEvent.click(await screen.findByRole("button", { name: "같은 요청으로 다시 시도" }));

    await waitFor(() => expect(start).toHaveBeenCalledTimes(2));
    expect(start.mock.calls[1][1]).toBe(start.mock.calls[0][1]);
    expect(await screen.findByText("작업을 시작했습니다 (이미 처리된 요청의 결과)")).toBeDefined();
  });
});

let lastRender: ReturnType<typeof renderWithApp> | null = null;
function cleanupAndRender(ui: React.ReactElement) {
  lastRender?.unmount();
  document.body.innerHTML = "";
  lastRender = renderWithApp(ui, { as: session("HOUSEKEEPER") });
}

// ------------------------------------------------------------- ErrorNotice

describe("ErrorNotice (03 §5)", () => {
  test("a refused checkout explains the blocking stayover and how to resolve it", () => {
    const error = new ApiError(409, "STAYOVER_IN_PROGRESS", {
      code: "STAYOVER_IN_PROGRESS",
      correlationId: "corr-1",
      blockingTask: { taskId: "t-9", status: "BLOCKED", statusBeforeBlocked: "IN_PROGRESS", assignee: { displayName: "HK-02" }, activeBlockers: [{ type: "DND" }] },
      resolutionOptions: ["RESOLVE_BLOCKERS_AND_COMPLETE", "CANCEL_TASK_AS_MANAGER"],
    });
    renderPlain(<ErrorNotice error={error} />);
    const alert = screen.getByRole("alert");
    expect(alert.textContent).toContain("시작된 투숙 중 청소가 있어 체크아웃할 수 없습니다");
    expect(alert.textContent).toContain("담당 HK-02");
    expect(alert.textContent).toContain("방해 금지");
    expect(alert.textContent).toContain("관리자가 투숙 중 청소를 취소한 뒤 체크아웃");
    expect(alert.textContent).toContain("추적 ID: corr-1");
  });

  test("an existing active task links to it", () => {
    renderPlain(<ErrorNotice error={new ApiError(409, "ACTIVE_WORKFLOW_EXISTS", { existingTaskId: "t-7" })} />);
    expect(screen.getByRole("link", { name: "진행 중인 작업 보기" }).getAttribute("href")).toBe("/tasks?focus=t-7");
  });
});

// --------------------------------------------------------------- dashboard

describe("DashboardView", () => {
  test("shows UNKNOWN separately from risk and explains top reasons in words", async () => {
    const dashboard = vi.fn().mockResolvedValue({
      propertyId: "p", timezone: "Asia/Seoul", businessDate: "2026-11-05", generatedAt: "2026-11-05T00:00:00Z", syntheticData: true,
      rooms: { total: 2, byStage: { NEEDS_CLEANING: 1, READY: 1 }, byAvailability: { AVAILABLE: 2, OUT_OF_SERVICE: 0 }, interruptedWork: [] },
      stays: { expectedArrivals: 2, checkedInToday: 0, expectedDepartures: 0, checkedOutToday: 1 },
      tasks: { PENDING: 1 },
      readiness: {
        calculationVersion: "readiness-v1",
        summary: { AT_RISK: 1, UNKNOWN: 1, WARNING: 0, ON_TRACK: 0, READY: 0 },
        topRisks: [
          { roomId: "r1", roomNumber: "202", grade: "AT_RISK", riskMinutes: 26, readyDeadline: "2026-11-05T01:00:00Z", topReasons: ["CLEANING", "INSPECTION"] },
          { roomId: "r2", roomNumber: "304", grade: "UNKNOWN", readyDeadline: "2026-11-05T05:00:00Z", topReasons: ["MISSING:blocker.estimatedResolutionAt (DND x)"] },
        ],
      },
    });
    const staffWorkloads = vi.fn().mockResolvedValue({ propertyId: "p", timezone: "Asia/Seoul", businessDate: "2026-11-05", staff: [], note: "" });
    const readiness = vi.fn().mockResolvedValue({ rooms: [], summary: {} });
    renderWithApp(<DashboardView />, { as: session("MANAGER", "mgr"), api: { dashboard, staffWorkloads, readiness } as never });

    const list = await screen.findByRole("list", { name: "확인이 필요한 객실" });
    expect(list.textContent).toContain("202호");
    expect(list.textContent).toContain("26분 늦음");
    expect(list.textContent).toContain("청소 · 검사");
    expect(list.textContent).toContain("판정 불가");
    expect(list.textContent).toContain("차단 해제 예상 시각 없음");
  });
});

describe("Dialog", () => {
  function Harness() {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button onClick={() => setOpen(true)}>열기</button>
        {open ? (
          <Dialog open title="확인" onClose={() => setOpen(false)}>
            <button onClick={() => setOpen(false)}>취소</button>
          </Dialog>
        ) : null}
      </>
    );
  }

  test("focus returns to the trigger when the parent unmounts the dialog", async () => {
    renderPlain(<Harness />);
    const trigger = screen.getByRole("button", { name: "열기" });
    trigger.focus();
    fireEvent.click(trigger);
    const cancel = await screen.findByRole("button", { name: "취소" });
    cancel.focus(); // 브라우저의 showModal()은 포커스를 dialog 안으로 옮긴다 (jsdom polyfill은 하지 않음)
    expect(document.activeElement).toBe(cancel);
    fireEvent.click(cancel);
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });
});
