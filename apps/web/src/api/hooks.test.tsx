import { afterEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { StayOpsApi } from "./endpoints";
import { ApiError } from "./errors";
import { ApiProvider, useCommand } from "./hooks";

afterEach(cleanup);

const PROPERTY = "22222222-2222-2222-2222-222222222222";

function setup(start: (taskId: string, key: string) => Promise<unknown>) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const api = { commands: { start } } as unknown as StayOpsApi;
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <ApiProvider api={api} propertyId={PROPERTY}>
        {children}
      </ApiProvider>
    </QueryClientProvider>
  );
  return { invalidate, ...renderHook(() => useCommand("start"), { wrapper }) };
}

describe("useCommand", () => {
  test("retry after a network error reuses the same idempotency key and arguments", async () => {
    const start = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(0, "NETWORK_ERROR", {}))
      .mockResolvedValueOnce({ data: { taskId: "t-1" }, replayed: true, correlationId: "c" });
    const { result } = setup(start);

    await act(async () => {
      await result.current.run("t-1").catch(() => undefined);
    });
    await act(async () => {
      await result.current.retry();
    });

    expect(start).toHaveBeenCalledTimes(2);
    const [firstTask, firstKey] = start.mock.calls[0];
    const [secondTask, secondKey] = start.mock.calls[1];
    expect(secondTask).toBe(firstTask);
    expect(secondKey).toBe(firstKey);
    expect(firstKey).toMatch(/^web-/);
  });

  test("each new user action gets a new key", async () => {
    const start = vi.fn().mockResolvedValue({ data: {}, replayed: false, correlationId: undefined });
    const { result } = setup(start);
    await act(async () => {
      await result.current.run("t-1");
      await result.current.run("t-1");
    });
    expect(start.mock.calls[0][1]).not.toBe(start.mock.calls[1][1]);
  });

  test("success invalidates the related queries for the property", async () => {
    const start = vi.fn().mockResolvedValue({ data: {}, replayed: false, correlationId: undefined });
    const { result, invalidate } = setup(start);
    await act(async () => {
      await result.current.run("t-1");
    });
    await waitFor(() => expect(invalidate).toHaveBeenCalled());
    const keys = invalidate.mock.calls.map(([filters]) => filters?.queryKey);
    expect(keys).toContainEqual(["stayops", PROPERTY, "rooms"]);
    expect(keys).toContainEqual(["stayops", PROPERTY, "readiness"]);
  });

  test("version conflicts also refetch so the user sees the latest state", async () => {
    const start = vi.fn().mockRejectedValue(new ApiError(409, "VERSION_CONFLICT", {}));
    const { result, invalidate } = setup(start);
    await act(async () => {
      await result.current.run("t-1").catch(() => undefined);
    });
    await waitFor(() => expect(invalidate).toHaveBeenCalled());
  });
});
