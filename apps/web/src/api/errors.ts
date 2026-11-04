/**
 * API 오류 모델 (docs/v4.1/02 §7.1, 03 §5).
 * 서버 응답은 RFC 9457 Problem Details이며 `code`로 분기한다. 네트워크 실패는 클라이언트 전용 코드로 표현한다.
 */
export const PROBLEM_CODES = [
  "VALIDATION_ERROR",
  "UNAUTHENTICATED",
  "FORBIDDEN",
  "NOT_FOUND",
  "REQUEST_NOT_SUPPORTED",
  "PAYLOAD_TOO_LARGE",
  "INVALID_STATE",
  "ROOM_NOT_AVAILABLE",
  "ACTIVE_BLOCKER_EXISTS",
  "ASSIGNMENT_INVALID",
  "SHIFT_CONFLICT",
  "SCHEDULE_CONFLICT",
  "VERSION_CONFLICT",
  "IDEMPOTENCY_CONFLICT",
  "ACTIVE_WORKFLOW_EXISTS",
  "STAYOVER_IN_PROGRESS",
  "STATE_INCONSISTENT",
  "INTERNAL_ERROR",
] as const;

export type ProblemCode = (typeof PROBLEM_CODES)[number];

/** 서버 응답을 받지 못함(타임아웃·연결 실패). 결과가 불확실하므로 같은 Idempotency-Key로 재시도한다. */
export const NETWORK_ERROR = "NETWORK_ERROR" as const;
/** 데모 사용자가 선택되지 않아 요청을 보내지 않음. */
export const NO_DEMO_ACTOR = "NO_DEMO_ACTOR" as const;

export type ApiErrorCode = ProblemCode | typeof NETWORK_ERROR | typeof NO_DEMO_ACTOR | "UNKNOWN";

export type Problem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  correlationId?: string;
  fieldErrors?: { field: string; message: string }[];
  [extension: string]: unknown;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: ApiErrorCode;
  readonly correlationId: string | undefined;
  readonly problem: Problem;

  constructor(status: number, code: ApiErrorCode, problem: Problem, correlationId?: string) {
    super(problem.detail ?? problem.title ?? code);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.problem = problem;
    this.correlationId = correlationId ?? problem.correlationId;
  }

  /** Problem의 확장 필드 (예: blockingTask, existingTaskId, currentStatus). */
  extension<T = unknown>(name: string): T | undefined {
    return this.problem[name] as T | undefined;
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

export function toApiError(status: number, body: unknown, correlationId?: string): ApiError {
  const problem: Problem = typeof body === "object" && body !== null ? (body as Problem) : { detail: String(body ?? "") };
  const code = (PROBLEM_CODES as readonly string[]).includes(problem.code ?? "") ? (problem.code as ProblemCode) : "UNKNOWN";
  return new ApiError(status, code, problem, correlationId);
}

/** 오류별 UX 처리 방침 (03 §5 오류 코드 매핑). 화면은 이 값으로 분기한다. */
export type ErrorAction =
  | "show-field-errors"
  | "choose-demo-user"
  | "forbidden"
  | "not-found"
  | "refetch-and-retry"
  | "refetch-and-explain"
  | "show-blockers"
  | "go-to-assignment"
  | "go-to-existing-task"
  | "stayover-blocks-checkout"
  | "retry-with-new-key"
  | "retry-same-key"
  | "contact-admin"
  | "generic";

export function errorAction(error: ApiError): ErrorAction {
  switch (error.code) {
    case "VALIDATION_ERROR":
    case "PAYLOAD_TOO_LARGE": // 서버 설명(detail)에 상한이 들어 있다
      return "show-field-errors";
    case "UNAUTHENTICATED":
    case NO_DEMO_ACTOR:
      return "choose-demo-user";
    case "FORBIDDEN":
      return "forbidden";
    case "NOT_FOUND":
      return "not-found";
    case "VERSION_CONFLICT":
      return "refetch-and-retry";
    case "INVALID_STATE":
    case "ROOM_NOT_AVAILABLE":
    case "SCHEDULE_CONFLICT":
      return "refetch-and-explain";
    case "ACTIVE_BLOCKER_EXISTS":
      return "show-blockers";
    case "ASSIGNMENT_INVALID":
    case "SHIFT_CONFLICT":
      return "go-to-assignment";
    case "ACTIVE_WORKFLOW_EXISTS":
      return "go-to-existing-task";
    case "STAYOVER_IN_PROGRESS":
      return "stayover-blocks-checkout";
    case "IDEMPOTENCY_CONFLICT":
      return "retry-with-new-key";
    case NETWORK_ERROR:
      return "retry-same-key";
    case "STATE_INCONSISTENT":
      return "contact-admin";
    default:
      return "generic";
  }
}
