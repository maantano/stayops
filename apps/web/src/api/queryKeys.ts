import type { CommandName, ReservationsQuery, RoomsQuery, TasksQuery } from "./endpoints";

/**
 * TanStack Query 키 (03 §6). 모든 키는 `["stayops", propertyId, 자원, ...]` 형태이며 filter·정렬을 포함한다.
 * cursor는 infinite query의 page param이므로 키에 넣지 않는다.
 */
export const queryKeys = {
  all: (propertyId: string) => ["stayops", propertyId] as const,
  dashboard: (propertyId: string) => ["stayops", propertyId, "dashboard"] as const,
  readiness: (propertyId: string) => ["stayops", propertyId, "readiness"] as const,
  rooms: (propertyId: string, filter: Omit<RoomsQuery, "cursor"> = {}) => ["stayops", propertyId, "rooms", filter] as const,
  room: (propertyId: string, roomId: string) => ["stayops", propertyId, "room", roomId] as const,
  timeline: (propertyId: string, roomId: string) => ["stayops", propertyId, "timeline", roomId] as const,
  tasks: (propertyId: string, filter: Omit<TasksQuery, "cursor"> = {}) => ["stayops", propertyId, "tasks", filter] as const,
  reservations: (propertyId: string, filter: Omit<ReservationsQuery, "cursor"> = {}) => ["stayops", propertyId, "reservations", filter] as const,
  staffWorkloads: (propertyId: string, date?: string) => ["stayops", propertyId, "staff-workloads", date ?? "today"] as const,
  simulationDatasets: (propertyId: string) => ["stayops", propertyId, "simulations", "datasets"] as const,
  simulations: (propertyId: string) => ["stayops", propertyId, "simulations", "runs"] as const,
  simulation: (propertyId: string, simulationId: string) => ["stayops", propertyId, "simulations", "run", simulationId] as const,
};

type Resource = "dashboard" | "readiness" | "rooms" | "room" | "timeline" | "tasks" | "reservations" | "staff-workloads" | "simulations";

/**
 * command 성공 후 무효화할 자원 (03 §6 "관련 query invalidate").
 * 객실 상태·작업·준비 예측은 거의 모든 command에 영향을 받으므로 공통으로 무효화하고, 예약·업무량은 해당 command만.
 */
const COMMON: Resource[] = ["dashboard", "readiness", "rooms", "room", "timeline", "tasks"];

export const invalidationTargets: Record<CommandName, Resource[]> = {
  checkIn: [...COMMON, "reservations"],
  checkOut: [...COMMON, "reservations", "staff-workloads"],
  assign: [...COMMON, "staff-workloads"],
  unassign: [...COMMON, "staff-workloads"],
  start: COMMON,
  addBlocker: COMMON,
  resolveBlocker: [...COMMON, "staff-workloads"],
  complete: [...COMMON, "staff-workloads"],
  inspectionResult: [...COMMON, "staff-workloads"],
  cancel: [...COMMON, "staff-workloads"],
  createStayover: COMMON,
  createRework: [...COMMON, "staff-workloads"],
  changeAvailability: COMMON,
  // 시뮬레이션은 데모 데이터만 다루므로 운영 화면 query에 영향이 없다.
  importSimulationDataset: ["simulations"],
  runSimulation: ["simulations"],
};

/** 무효화용 키 접두사. 같은 자원의 모든 filter·id 변형을 함께 무효화한다. */
export function invalidationPrefixes(propertyId: string, command: CommandName): (readonly unknown[])[] {
  return invalidationTargets[command].map((resource) => ["stayops", propertyId, resource] as const);
}

/** dashboard·readiness 자동 갱신 주기 (03 §6, 설정값). websocket은 MVP 제외. */
export const POLLING = { dashboardMs: 30_000, readinessMs: 30_000, staleMs: 10_000 } as const;
