import createClient from "openapi-fetch";
import type { components, operations } from "./schema";
import { ApiError, NETWORK_ERROR, NO_DEMO_ACTOR, toApiError } from "./errors";

/**
 * StayOps API 클라이언트. 타입은 `docs/api/openapi.json`에서 생성한 `schema.d.ts`를 따른다 (ADR-006).
 * - 모든 요청에 데모 요청자 헤더(`X-Demo-Staff-Id`), command에는 호출자가 준 `Idempotency-Key`를 붙인다.
 * - 오류는 Problem Details를 [ApiError]로 바꿔 throw 한다. 응답을 받지 못하면 `NETWORK_ERROR`.
 * - 서버 상태 판단(허용 여부·readiness 계산)은 하지 않는다 (03 §1).
 */
type Schemas = components["schemas"];
export type Dashboard = Schemas["Dashboard"];
export type RoomView = Schemas["RoomView"];
export type RoomPage = Schemas["PageResponseRoomView"];
export type TimelinePage = Schemas["PageResponseTimelineEntry"];
export type TaskPage = Schemas["PageResponseTaskListItem"];
export type TaskListItem = Schemas["TaskListItem"];
export type ReservationPage = Schemas["PageResponseReservationListItem"];
export type ReadinessReport = Schemas["ReadinessReport"];
export type StaffWorkloadReport = Schemas["StaffWorkloadReport"];
export type TaskCommandResponse = Schemas["TaskCommandResponse"];
export type StayCommandResponse = Schemas["StayCommandResponse"];
export type RoomCommandResponse = Schemas["RoomCommandResponse"];

export type RoomsQuery = NonNullable<operations["rooms"]["parameters"]["query"]>;
export type TasksQuery = NonNullable<operations["tasks"]["parameters"]["query"]>;
export type ReservationsQuery = NonNullable<operations["reservations"]["parameters"]["query"]>;
export type TimelineQuery = NonNullable<operations["timeline"]["parameters"]["query"]>;

/** command 응답 + 재사용 여부·correlation id (응답 헤더). */
export type CommandOutcome<T> = { data: T; replayed: boolean; correlationId: string | undefined };

export type StayOpsApiOptions = {
  baseUrl: string;
  /** 현재 데모 요청자. null이면 요청을 보내지 않고 NO_DEMO_ACTOR 오류. */
  getStaffId: () => string | null;
  fetch?: typeof globalThis.fetch;
};

type FetchResult<T> = { data?: T; error?: unknown; response: Response };

export function createStayOpsApi(options: StayOpsApiOptions) {
  const client = createClient<import("./schema").paths>({ baseUrl: options.baseUrl, fetch: options.fetch });

  const actor = (): string => {
    const staffId = options.getStaffId();
    if (!staffId) throw new ApiError(0, NO_DEMO_ACTOR, { detail: "데모 사용자를 선택하세요" });
    return staffId;
  };
  const queryHeaders = () => ({ "X-Demo-Staff-Id": actor() });
  const commandHeaders = (idempotencyKey: string) => ({ "X-Demo-Staff-Id": actor(), "Idempotency-Key": idempotencyKey });

  async function call<T>(request: () => Promise<FetchResult<T>>): Promise<{ data: T; response: Response }> {
    let result: FetchResult<T>;
    try {
      result = await request();
    } catch (e) {
      if (e instanceof ApiError) throw e;
      throw new ApiError(0, NETWORK_ERROR, { detail: e instanceof Error ? e.message : String(e) });
    }
    const correlationId = result.response.headers.get("X-Correlation-Id") ?? undefined;
    if (!result.response.ok || result.error !== undefined) throw toApiError(result.response.status, result.error, correlationId);
    return { data: result.data as T, response: result.response };
  }

  async function query<T>(request: () => Promise<FetchResult<T>>): Promise<T> {
    return (await call(request)).data;
  }

  async function command<T>(request: () => Promise<FetchResult<T>>): Promise<CommandOutcome<T>> {
    const { data, response } = await call(request);
    return {
      data,
      replayed: response.headers.get("Idempotency-Replayed") === "true",
      correlationId: response.headers.get("X-Correlation-Id") ?? undefined,
    };
  }

  return {
    // ------------------------------------------------------------- queries
    dashboard: (propertyId: string) =>
      query(() => client.GET("/api/v1/properties/{propertyId}/dashboard", { params: { path: { propertyId }, header: queryHeaders() } })),
    readiness: (propertyId: string) =>
      query(() => client.GET("/api/v1/properties/{propertyId}/readiness", { params: { path: { propertyId }, header: queryHeaders() } })),
    rooms: (propertyId: string, q: RoomsQuery = {}) =>
      query(() => client.GET("/api/v1/properties/{propertyId}/rooms", { params: { path: { propertyId }, query: q, header: queryHeaders() } })),
    room: (propertyId: string, roomId: string) =>
      query(() => client.GET("/api/v1/properties/{propertyId}/rooms/{roomId}", { params: { path: { propertyId, roomId }, header: queryHeaders() } })),
    timeline: (propertyId: string, roomId: string, q: TimelineQuery = {}) =>
      query(() =>
        client.GET("/api/v1/properties/{propertyId}/rooms/{roomId}/timeline", { params: { path: { propertyId, roomId }, query: q, header: queryHeaders() } }),
      ),
    tasks: (propertyId: string, q: TasksQuery = {}) =>
      query(() => client.GET("/api/v1/properties/{propertyId}/tasks", { params: { path: { propertyId }, query: q, header: queryHeaders() } })),
    reservations: (propertyId: string, q: ReservationsQuery = {}) =>
      query(() => client.GET("/api/v1/properties/{propertyId}/reservations", { params: { path: { propertyId }, query: q, header: queryHeaders() } })),
    staffWorkloads: (propertyId: string, date?: string) =>
      query(() =>
        client.GET("/api/v1/properties/{propertyId}/staff-workloads", { params: { path: { propertyId }, query: date ? { date } : {}, header: queryHeaders() } }),
      ),

    // ------------------------------------------------------------ commands
    commands: {
      checkIn: (reservationId: string, key: string) =>
        command(() => client.POST("/api/v1/reservations/{reservationId}/check-in", { params: { path: { reservationId }, header: commandHeaders(key) } })),
      checkOut: (reservationId: string, key: string) =>
        command(() => client.POST("/api/v1/reservations/{reservationId}/check-out", { params: { path: { reservationId }, header: commandHeaders(key) } })),
      assign: (taskId: string, staffId: string, key: string) =>
        command(() => client.POST("/api/v1/tasks/{taskId}/assignments", { params: { path: { taskId }, header: commandHeaders(key) }, body: { staffId } })),
      unassign: (taskId: string, assignmentId: string, key: string) =>
        command(() => client.DELETE("/api/v1/tasks/{taskId}/assignments/{assignmentId}", { params: { path: { taskId, assignmentId }, header: commandHeaders(key) } })),
      start: (taskId: string, key: string) =>
        command(() => client.POST("/api/v1/tasks/{taskId}/start", { params: { path: { taskId }, header: commandHeaders(key) } })),
      addBlocker: (taskId: string, body: Schemas["BlockerRequest"], key: string) =>
        command(() => client.POST("/api/v1/tasks/{taskId}/blockers", { params: { path: { taskId }, header: commandHeaders(key) }, body })),
      resolveBlocker: (taskId: string, blockerId: string, note: string | undefined, key: string) =>
        command(() =>
          client.POST("/api/v1/tasks/{taskId}/blockers/{blockerId}/resolve", { params: { path: { taskId, blockerId }, header: commandHeaders(key) }, body: { note } }),
        ),
      complete: (taskId: string, key: string) =>
        command(() => client.POST("/api/v1/tasks/{taskId}/complete", { params: { path: { taskId }, header: commandHeaders(key) } })),
      inspectionResult: (taskId: string, body: Schemas["InspectionResultRequest"], key: string) =>
        command(() => client.POST("/api/v1/tasks/{taskId}/inspection-result", { params: { path: { taskId }, header: commandHeaders(key) }, body })),
      cancel: (taskId: string, reason: string, key: string) =>
        command(() => client.POST("/api/v1/tasks/{taskId}/cancel", { params: { path: { taskId }, header: commandHeaders(key) }, body: { reason } })),
      createStayover: (roomId: string, body: Schemas["StayoverRequest"], key: string) =>
        command(() => client.POST("/api/v1/rooms/{roomId}/stayover-cleanings", { params: { path: { roomId }, header: commandHeaders(key) }, body })),
      createRework: (roomId: string, body: Schemas["ReworkRequest"], key: string) =>
        command(() => client.POST("/api/v1/rooms/{roomId}/rework-tasks", { params: { path: { roomId }, header: commandHeaders(key) }, body })),
      changeAvailability: (roomId: string, body: Schemas["AvailabilityRequest"], key: string) =>
        command(() => client.POST("/api/v1/rooms/{roomId}/availability", { params: { path: { roomId }, header: commandHeaders(key) }, body })),
    },
  };
}

export type StayOpsApi = ReturnType<typeof createStayOpsApi>;
export type CommandName = keyof StayOpsApi["commands"];
