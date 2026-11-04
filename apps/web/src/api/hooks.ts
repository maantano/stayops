"use client";

import { createContext, createElement, useCallback, useContext, useRef, type ReactNode } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { CommandName, CommandOutcome, ReservationsQuery, RoomsQuery, StayOpsApi, TasksQuery } from "./endpoints";
import { isApiError } from "./errors";
import { invalidationPrefixes, POLLING, queryKeys } from "./queryKeys";
import { newIdempotencyKey } from "./session";

type ApiContextValue = { api: StayOpsApi; propertyId: string | null };

const ApiContext = createContext<ApiContextValue | null>(null);

export function ApiProvider({ api, propertyId, children }: ApiContextValue & { children: ReactNode }) {
  return createElement(ApiContext.Provider, { value: { api, propertyId } }, children);
}

function useApi(): { api: StayOpsApi; propertyId: string } {
  const value = useContext(ApiContext);
  if (!value) throw new Error("ApiProvider is missing");
  return { api: value.api, propertyId: value.propertyId ?? "" };
}

function useEnabled(): boolean {
  return Boolean(useContext(ApiContext)?.propertyId);
}

// ------------------------------------------------------------------- queries

export function useDashboard() {
  const { api, propertyId } = useApi();
  return useQuery({
    queryKey: queryKeys.dashboard(propertyId),
    queryFn: () => api.dashboard(propertyId),
    enabled: useEnabled(),
    refetchInterval: POLLING.dashboardMs,
    staleTime: POLLING.staleMs,
  });
}

/** [allowed]가 false면 호출하지 않는다 (readiness는 FRONT_DESK·MANAGER 전용, 04 §5). */
export function useReadiness(allowed = true) {
  const { api, propertyId } = useApi();
  const enabled = useEnabled();
  return useQuery({
    queryKey: queryKeys.readiness(propertyId),
    queryFn: () => api.readiness(propertyId),
    enabled: enabled && allowed,
    refetchInterval: POLLING.readinessMs,
    staleTime: POLLING.staleMs,
  });
}

export function useRoom(roomId: string) {
  const { api, propertyId } = useApi();
  return useQuery({ queryKey: queryKeys.room(propertyId, roomId), queryFn: () => api.room(propertyId, roomId), enabled: useEnabled() && Boolean(roomId) });
}

export function useRooms(filter: Omit<RoomsQuery, "cursor"> = {}) {
  const { api, propertyId } = useApi();
  return useInfiniteQuery({
    queryKey: queryKeys.rooms(propertyId, filter),
    queryFn: ({ pageParam }) => api.rooms(propertyId, { ...filter, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: useEnabled(),
  });
}

export function useRoomTimeline(roomId: string) {
  const { api, propertyId } = useApi();
  return useInfiniteQuery({
    queryKey: queryKeys.timeline(propertyId, roomId),
    queryFn: ({ pageParam }) => api.timeline(propertyId, roomId, { cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: useEnabled() && Boolean(roomId),
  });
}

export function useTasks(filter: Omit<TasksQuery, "cursor"> = {}) {
  const { api, propertyId } = useApi();
  return useInfiniteQuery({
    queryKey: queryKeys.tasks(propertyId, filter),
    queryFn: ({ pageParam }) => api.tasks(propertyId, { ...filter, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: useEnabled(),
  });
}

export function useReservations(filter: Omit<ReservationsQuery, "cursor"> = {}) {
  const { api, propertyId } = useApi();
  return useInfiniteQuery({
    queryKey: queryKeys.reservations(propertyId, filter),
    queryFn: ({ pageParam }) => api.reservations(propertyId, { ...filter, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: useEnabled(),
  });
}

export function useSimulationDatasets(allowed = true) {
  const { api, propertyId } = useApi();
  const enabled = useEnabled();
  return useQuery({ queryKey: queryKeys.simulationDatasets(propertyId), queryFn: () => api.simulationDatasets(propertyId), enabled: enabled && allowed });
}

export function useSimulations(allowed = true) {
  const { api, propertyId } = useApi();
  const enabled = useEnabled();
  return useQuery({ queryKey: queryKeys.simulations(propertyId), queryFn: () => api.simulations(propertyId), enabled: enabled && allowed });
}

export function useSimulation(simulationId: string | null) {
  const { api, propertyId } = useApi();
  const enabled = useEnabled();
  return useQuery({
    queryKey: queryKeys.simulation(propertyId, simulationId ?? ""),
    queryFn: () => api.simulation(propertyId, simulationId!),
    enabled: enabled && Boolean(simulationId),
    staleTime: Infinity, // 완료된 실행 결과는 바뀌지 않는다
  });
}

export function useStaffWorkloads(date?: string) {
  const { api, propertyId } = useApi();
  return useQuery({ queryKey: queryKeys.staffWorkloads(propertyId, date), queryFn: () => api.staffWorkloads(propertyId, date), enabled: useEnabled() });
}

// ------------------------------------------------------------------ commands

type Commands = StayOpsApi["commands"];
/** command 인자에서 마지막 Idempotency-Key를 뺀 나머지. */
type ArgsOf<C extends CommandName> = Parameters<Commands[C]> extends [...infer A, string] ? A : never;
type DataOf<C extends CommandName> = Awaited<ReturnType<Commands[C]>> extends CommandOutcome<infer T> ? T : never;

/**
 * command 실행 hook (03 §5).
 * - `run(...)`: 사용자 행동 1회. 새 Idempotency-Key를 만든다.
 * - `retry()`: 직전 행동을 **같은 key·같은 인자**로 다시 보낸다(타임아웃처럼 결과가 불확실할 때).
 * - 성공 또는 VERSION_CONFLICT면 관련 query를 무효화해 서버 상태로 다시 그린다(낙관적 상태를 영구 신뢰하지 않음).
 * - mutation 자동 재시도는 하지 않는다. 중복 클릭은 `isPending`으로 막는다.
 */
export function useCommand<C extends CommandName>(name: C) {
  const { api, propertyId } = useApi();
  const queryClient = useQueryClient();
  const last = useRef<{ key: string; args: ArgsOf<C> } | null>(null);

  const invalidate = useCallback(
    () => Promise.all(invalidationPrefixes(propertyId, name).map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
    [name, propertyId, queryClient],
  );

  const mutation = useMutation<CommandOutcome<DataOf<C>>, Error, { key: string; args: ArgsOf<C> }>({
    mutationFn: ({ key, args }) => {
      const fn = api.commands[name] as unknown as (...a: unknown[]) => Promise<CommandOutcome<DataOf<C>>>;
      return fn(...(args as unknown[]), key);
    },
    retry: false,
    onSuccess: () => invalidate(),
    onError: (error) => {
      if (isApiError(error) && (error.code === "VERSION_CONFLICT" || error.code === "INVALID_STATE")) return invalidate();
    },
  });

  const run = useCallback(
    (...args: ArgsOf<C>) => {
      last.current = { key: newIdempotencyKey(), args };
      return mutation.mutateAsync(last.current);
    },
    [mutation],
  );

  const retry = useCallback(() => {
    if (!last.current) throw new Error("nothing to retry");
    return mutation.mutateAsync(last.current);
  }, [mutation]);

  return { ...mutation, run, retry, lastIdempotencyKey: () => last.current?.key };
}
