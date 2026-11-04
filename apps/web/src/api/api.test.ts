import { describe, expect, test, vi } from "vitest";
import { createStayOpsApi } from "./endpoints";
import { ApiError, errorAction, PROBLEM_CODES, toApiError } from "./errors";
import { invalidationPrefixes, invalidationTargets, queryKeys } from "./queryKeys";

const STAFF = "11111111-1111-1111-1111-111111111111";
const PROPERTY = "22222222-2222-2222-2222-222222222222";
const TASK = "33333333-3333-3333-3333-333333333333";

type Captured = { url: string; method: string; headers: Headers; body: string | null };

function fakeFetch(status: number, body: unknown, headers: Record<string, string> = {}) {
  const calls: Captured[] = [];
  const fn = vi.fn(async (input: Request | string | URL) => {
    const request = input as Request;
    calls.push({ url: request.url, method: request.method, headers: request.headers, body: request.body ? await request.text() : null });
    const contentType = status >= 400 ? "application/problem+json" : "application/json";
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType, ...headers } });
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

function api(fetch: typeof globalThis.fetch, staffId: string | null = STAFF) {
  return createStayOpsApi({ baseUrl: "http://api.test", getStaffId: () => staffId, fetch });
}

describe("endpoints", () => {
  test("queries send the demo actor header and no idempotency key", async () => {
    const { fn, calls } = fakeFetch(200, { items: [], nextCursor: null, limit: 50 });
    await api(fn).tasks(PROPERTY, { status: ["PENDING", "BLOCKED"], limit: 10 });

    const call = calls[0];
    expect(call.method).toBe("GET");
    expect(call.url).toBe(`http://api.test/api/v1/properties/${PROPERTY}/tasks?status=PENDING&status=BLOCKED&limit=10`);
    expect(call.headers.get("X-Demo-Staff-Id")).toBe(STAFF);
    expect(call.headers.get("Idempotency-Key")).toBeNull();
  });

  test("commands send the given idempotency key and surface replay and correlation headers", async () => {
    const { fn, calls } = fakeFetch(200, { taskId: TASK }, { "Idempotency-Replayed": "true", "X-Correlation-Id": "c-1" });
    const outcome = await api(fn).commands.cancel(TASK, "운영 변경", "key-1");

    expect(calls[0].method).toBe("POST");
    expect(calls[0].headers.get("Idempotency-Key")).toBe("key-1");
    expect(JSON.parse(calls[0].body ?? "{}")).toEqual({ reason: "운영 변경" });
    expect(outcome).toEqual({ data: { taskId: TASK }, replayed: true, correlationId: "c-1" });
  });

  test("problem responses become ApiError with code, extensions and correlation id", async () => {
    const problem = { type: "urn:stayops:problem:stayover-in-progress", status: 409, code: "STAYOVER_IN_PROGRESS", correlationId: "c-9", blockingTask: { taskId: TASK } };
    const { fn } = fakeFetch(409, problem);
    const error = await api(fn).commands.checkOut("r-1", "key-2").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.code).toBe("STAYOVER_IN_PROGRESS");
    expect(apiError.correlationId).toBe("c-9");
    expect(apiError.extension<{ taskId: string }>("blockingTask")?.taskId).toBe(TASK);
    expect(errorAction(apiError)).toBe("stayover-blocks-checkout");
  });

  test("network failure becomes NETWORK_ERROR so the caller retries with the same key", async () => {
    const failing = vi.fn(async () => {
      throw new TypeError("fetch failed");
    }) as unknown as typeof fetch;
    const error = (await api(failing).commands.start(TASK, "key-3").catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe("NETWORK_ERROR");
    expect(errorAction(error)).toBe("retry-same-key");
  });

  test("without a demo actor no request is sent", async () => {
    const { fn, calls } = fakeFetch(200, {});
    const error = (await api(fn, null).dashboard(PROPERTY).catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe("NO_DEMO_ACTOR");
    expect(errorAction(error)).toBe("choose-demo-user");
    expect(calls).toHaveLength(0);
  });
});

describe("errors", () => {
  test("every server problem code has an explicit UX action", () => {
    const unmapped = PROBLEM_CODES.filter((code) => errorAction(toApiError(409, { code })) === "generic").sort();
    // REQUEST_NOT_SUPPORTED·INTERNAL_ERROR만 일반 오류 화면(03 §5).
    expect(unmapped).toEqual(["INTERNAL_ERROR", "REQUEST_NOT_SUPPORTED"]);
  });

  test("unknown codes and non-JSON bodies are UNKNOWN", () => {
    expect(toApiError(500, { code: "SOMETHING_NEW" }).code).toBe("UNKNOWN");
    expect(toApiError(502, "Bad Gateway").problem.detail).toBe("Bad Gateway");
  });
});

describe("query keys and invalidation", () => {
  test("keys include property and filters but not the cursor", () => {
    expect(queryKeys.tasks(PROPERTY, { status: ["PENDING"] })).toEqual(["stayops", PROPERTY, "tasks", { status: ["PENDING"] }]);
  });

  test("every command invalidates the shared operational views", () => {
    for (const [command, targets] of Object.entries(invalidationTargets)) {
      expect(targets, command).toEqual(expect.arrayContaining(["dashboard", "readiness", "rooms", "room", "timeline", "tasks"]));
    }
    expect(invalidationPrefixes(PROPERTY, "checkIn")).toContainEqual(["stayops", PROPERTY, "reservations"]);
    expect(invalidationPrefixes(PROPERTY, "start")).not.toContainEqual(["stayops", PROPERTY, "reservations"]);
  });
});
