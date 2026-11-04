import type { TaskListItem } from "@/api/endpoints";

export type TaskAction = "assign" | "unassign" | "start" | "block" | "resolve" | "complete" | "inspect" | "cancel";

export type Viewer = { staffId: string; role: string };

const ACTIVE = new Set(["PENDING", "ASSIGNED", "IN_PROGRESS", "BLOCKED"]);

/**
 * 현재 사용자에게 보여줄 작업 버튼 (03 §3 Task board, 04 §5 RBAC, 02 §4).
 * UI 편의를 위한 숨김일 뿐이며 최종 판단은 서버가 한다. "시작 전/후"는 `actualStartedAt`으로 판단한다(02 §2.8).
 */
export function availableTaskActions(task: Pick<TaskListItem, "status" | "type" | "actualStartedAt" | "assignee">, viewer: Viewer): TaskAction[] {
  const isManager = viewer.role === "MANAGER";
  const isAssignee = task.assignee?.staffId === viewer.staffId;
  const started = Boolean(task.actualStartedAt);
  const active = ACTIVE.has(task.status);
  const actions: TaskAction[] = [];

  if (isManager && (task.status === "PENDING" || task.status === "ASSIGNED")) actions.push("assign");
  if (isManager && task.status === "ASSIGNED" && task.assignee) actions.push("unassign");
  if (isAssignee && task.status === "ASSIGNED") actions.push("start");
  if (active && (isManager || isAssignee)) actions.push("block");
  if (task.status === "BLOCKED" && (isManager || isAssignee)) actions.push("resolve");
  if (isAssignee && task.status === "IN_PROGRESS" && task.type !== "INSPECTION") actions.push("complete");
  if (isAssignee && task.status === "IN_PROGRESS" && task.type === "INSPECTION") actions.push("inspect");
  if (active) {
    const canCancel = started ? isManager : isManager || (viewer.role === "FRONT_DESK" && task.type === "STAYOVER_CLEANING");
    if (canCancel) actions.push("cancel");
  }
  return actions;
}

/** 배정 가능한 역할 (02 §2 Staff). */
export function assigneeRoleFor(type: string): "INSPECTOR" | "HOUSEKEEPER" {
  return type === "INSPECTION" ? "INSPECTOR" : "HOUSEKEEPER";
}
