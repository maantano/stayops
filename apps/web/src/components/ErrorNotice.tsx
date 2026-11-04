"use client";

import Link from "next/link";
import { errorAction, isApiError } from "@/api/errors";
import { blockerTypeLabels, labelOf, reservationStatusLabels, taskStatusLabels } from "@/lib/labels";
import { Button } from "./ui";

type BlockingTask = {
  taskId: string;
  status: string;
  statusBeforeBlocked?: string | null;
  actualStartedAt?: string | null;
  assignee?: { displayName?: string } | null;
  activeBlockers?: { type: string; estimatedResolutionAt?: string | null }[];
};

const RESOLUTION_LABELS: Record<string, string> = {
  RESOLVE_BLOCKERS_AND_COMPLETE: "담당자가 차단을 해제하고 청소를 완료",
  CANCEL_TASK_AS_MANAGER: "관리자가 투숙 중 청소를 취소한 뒤 체크아웃",
};

/**
 * 오류를 `errorAction` 방침에 따라 설명한다 (03 §5 오류 코드 매핑).
 * 서버 응답의 설명(detail)과 correlationId를 함께 보여 문의·추적이 가능하게 한다.
 */
export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null;
  if (!isApiError(error)) {
    return (
      <p role="alert" className="text-sm text-rose-700">
        알 수 없는 오류가 발생했습니다.
      </p>
    );
  }
  const action = errorAction(error);
  const box = "space-y-2 rounded-md border border-rose-200 bg-rose-50 p-3 text-sm text-rose-900";

  return (
    <div role="alert" className={box}>
      <p className="font-medium">{message(action, error.problem.detail)}</p>

      {action === "show-field-errors" && error.problem.fieldErrors?.length ? (
        <ul className="list-disc pl-5">
          {error.problem.fieldErrors.map((f) => (
            <li key={f.field}>
              {f.field}: {f.message}
            </li>
          ))}
        </ul>
      ) : null}

      {action === "refetch-and-explain" && error.extension<string>("currentStatus") ? (
        <p>현재 상태: {statusText(error.extension<string>("currentStatus")!)}</p>
      ) : null}

      {action === "show-blockers" ? (
        <ul className="list-disc pl-5">
          {(error.extension<{ type: string }[]>("activeBlockers") ?? []).map((b, i) => (
            <li key={i}>{blockerTypeLabels[b.type] ?? b.type}</li>
          ))}
        </ul>
      ) : null}

      {action === "go-to-existing-task" && error.extension<string>("existingTaskId") ? (
        <p>
          <Link className="underline" href={`/tasks?focus=${error.extension<string>("existingTaskId")}`}>
            진행 중인 작업 보기
          </Link>
        </p>
      ) : null}

      {action === "stayover-blocks-checkout" ? <StayoverBlocking error={error} /> : null}

      {action === "retry-same-key" && onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          같은 요청으로 다시 시도
        </Button>
      ) : null}

      {error.correlationId ? <p className="text-xs text-rose-700">추적 ID: {error.correlationId}</p> : null}
    </div>
  );
}

function StayoverBlocking({ error }: { error: import("@/api/errors").ApiError }) {
  const task = error.extension<BlockingTask>("blockingTask");
  const options = error.extension<string[]>("resolutionOptions") ?? [];
  if (!task) return null;
  return (
    <div className="space-y-1">
      <p>
        막고 있는 작업: 투숙 중 청소 · {labelOf(taskStatusLabels, task.status).label}
        {task.statusBeforeBlocked ? ` (차단 전 ${labelOf(taskStatusLabels, task.statusBeforeBlocked).label})` : ""}
        {task.assignee?.displayName ? ` · 담당 ${task.assignee.displayName}` : " · 담당자 없음"}
      </p>
      {task.activeBlockers?.length ? <p>차단 사유: {task.activeBlockers.map((b) => blockerTypeLabels[b.type] ?? b.type).join(", ")}</p> : null}
      <p>해결 방법:</p>
      <ul className="list-disc pl-5">
        {options.map((o) => (
          <li key={o}>{RESOLUTION_LABELS[o] ?? o}</li>
        ))}
      </ul>
      <Link className="underline" href={`/tasks?focus=${task.taskId}`}>
        해당 작업으로 이동
      </Link>
    </div>
  );
}

function statusText(status: string): string {
  return taskStatusLabels[status]?.label ?? reservationStatusLabels[status]?.label ?? status;
}

function message(action: ReturnType<typeof errorAction>, detail?: string): string {
  switch (action) {
    case "show-field-errors":
      return "입력 값을 확인하세요.";
    case "choose-demo-user":
      return "데모 사용자를 먼저 선택하세요.";
    case "forbidden":
      return "이 역할로는 할 수 없는 작업입니다.";
    case "not-found":
      return "대상을 찾을 수 없습니다.";
    case "refetch-and-retry":
      return "다른 변경과 충돌했습니다. 최신 상태를 불러왔으니 확인 후 다시 시도하세요.";
    case "refetch-and-explain":
      return `현재 상태에서 할 수 없는 요청입니다. ${detail ?? ""}`.trim();
    case "show-blockers":
      return "해제되지 않은 차단 사유가 있습니다.";
    case "go-to-assignment":
      return `담당자 배정을 확인하세요. ${detail ?? ""}`.trim();
    case "go-to-existing-task":
      return "이 객실에 진행 중인 작업이 이미 있습니다.";
    case "stayover-blocks-checkout":
      return "시작된 투숙 중 청소가 있어 체크아웃할 수 없습니다.";
    case "retry-with-new-key":
      return "같은 요청 키가 다른 요청에 쓰였습니다. 다시 시도하세요.";
    case "retry-same-key":
      return "서버 응답을 받지 못했습니다. 처리 여부가 불확실하니 같은 요청으로 다시 시도하세요.";
    case "contact-admin":
      return "데이터 상태 확인이 필요합니다. 관리자에게 아래 추적 ID를 전달하세요.";
    default:
      return "요청을 처리하지 못했습니다.";
  }
}
