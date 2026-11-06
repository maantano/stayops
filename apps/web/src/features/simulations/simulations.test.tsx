import { describe, expect, test, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { renderWithApp, session } from "@/test/render";
import { describeDiff, KPI_ROWS, MAX_DATASET_BYTES, SimulationView } from "./SimulationView";

const metrics = (onTime: number, late: number, meanLate: number) => ({
  turnovers: 24, evaluatedArrivals: 20, onTime: Math.round(onTime * 20), late, // 비율과 건수가 서로 맞게 (16/20 = 80%) notReadyByHorizonEnd: 0, onTimeReadinessRate: onTime,
  meanLatenessMinutes: meanLate, p90LatenessMinutes: 60, maxLatenessMinutes: 90, inspectionFailures: 1,
  jobs: {}, taskCompletionRate: 1, stayover: { requested: 2, started: 2, completed: 2, meanWaitMinutes: 5 },
  workload: { HOUSEKEEPER: { staff: 2, availableMinutes: 900, busyMinutes: 450, utilization: 0.5, overtimeMinutes: 10 }, INSPECTOR: { staff: 1, availableMinutes: 450, busyMinutes: 90, utilization: 0.2, overtimeMinutes: 0 } },
});

const run = {
  runId: "run-1", status: "COMPLETED", seed: 42, engineVersion: "sim-engine-v1", policyVersion: "sim-policy-v1",
  startedAt: "2026-11-04T10:00:00Z", completedAt: "2026-11-04T10:00:01Z", synthetic: true, note: "데모 데이터 비교",
  definitions: { onTimeReadinessRate: "정의" }, assumptions: ["가정"],
  dataset: { datasetId: "d-1", contentHash: "h", createdAt: "2026-11-04T09:00:00Z", created: false, synthetic: true,
    summary: { name: "데모 호텔 A", seed: 42, generatorVersion: "gen-v1", configHash: "c", startDate: "2026-11-09", days: 14, timezone: "Asia/Seoul", rooms: 80, staff: 8, reservations: 481 } },
  scenarios: [
    { policy: "BASELINE_FIFO", label: "기준", dispatchRule: "FIFO", sameHousekeeperShiftStart: false, parameters: {}, metrics: metrics(0.8, 4, 50) },
    { policy: "DEADLINE_FIRST", label: "대안 A", dispatchRule: "DEADLINE_FIRST", sameHousekeeperShiftStart: false, parameters: {}, metrics: metrics(0.95, 1, 20),
      diffFromBaseline: { metrics: { onTimeReadinessRate: 0.15, late: -3, meanLatenessMinutes: -30, housekeeperUtilization: 0 }, parameters: {} } },
  ],
};

describe("describeDiff", () => {
  const row = (key: string) => KPI_ROWS.find((r) => r.key === key)!;

  test("direction-aware verdicts in words", () => {
    expect(describeDiff(row("onTimeReadinessRate"), 0.147)).toEqual({ text: "+14.7%p", verdict: "개선" });
    expect(describeDiff(row("meanLatenessMinutes"), 12)).toEqual({ text: "+12.0분", verdict: "악화" });
    expect(describeDiff(row("late"), -3)).toEqual({ text: "−3", verdict: "개선" });
    expect(describeDiff(row("housekeeperUtilization"), 0.01).verdict).toBe(""); // 가동률은 좋고 나쁨을 판정하지 않는다
    expect(describeDiff(row("late"), 0).verdict).toBe("변화 없음");
    expect(describeDiff(row("meanLatenessMinutes"), null).verdict).toBe("비교 불가");
  });
});

describe("percent formatting", () => {
  test("on-time rate is computed from counts and rounded once", () => {
    const row = KPI_ROWS.find((r) => r.key === "onTimeReadinessRate")!;
    // 327/401 = 81.546% → 81.5%. 서버의 4자리 반올림값(0.8155)을 다시 반올림하면 81.6%가 될 수 있었다.
    const m = { ...metrics(0.8155, 74, 54.2), onTime: 327, evaluatedArrivals: 401 };
    expect(row.format(row.value(m as never)!)).toBe("81.5%");
    expect(row.format(0.81554)).toBe("81.6%");
  });
});

describe("SimulationView", () => {
  test("shows the comparison with baseline differences as text", async () => {
    const api = {
      simulationDatasets: vi.fn().mockResolvedValue([run.dataset]),
      simulations: vi.fn().mockResolvedValue([{ runId: "run-1", datasetId: "d-1", seed: 42, status: "COMPLETED", createdAt: "2026-11-04T10:00:00Z", policies: ["BASELINE_FIFO", "DEADLINE_FIRST"], onTimeReadinessRate: {} }]),
      simulation: vi.fn().mockResolvedValue(run),
    };
    renderWithApp(<SimulationView />, { as: session("FRONT_DESK", "fd"), api: api as never });

    expect(screen.queryByRole("heading", { name: "정책 비교 실행" })).toBeNull(); // 프런트는 조회만
    fireEvent.click(await screen.findByRole("button", { name: /seed 42/ }));
    const table = await screen.findByRole("table");
    const onTimeRow = within(table).getByRole("row", { name: /정시 준비율/ });
    expect(onTimeRow.textContent).toContain("80.0%");
    expect(onTimeRow.textContent).toContain("95.0%");
    expect(onTimeRow.textContent).toContain("+15.0%p 개선");
    expect(within(table).getByRole("row", { name: /평균 지연/ }).textContent).toContain("−30.0분 개선");
    expect(screen.getByText(/실제 운영 성과나 최적화 보장이 아닙니다/)).toBeDefined();
  });

  test("a non-JSON file is rejected before any request", async () => {
    const importSimulationDataset = vi.fn();
    const api = { simulationDatasets: vi.fn().mockResolvedValue([]), simulations: vi.fn().mockResolvedValue([]), commands: { importSimulationDataset } };
    renderWithApp(<SimulationView />, { as: session("MANAGER", "mgr"), api: api as never });

    const input = await screen.findByLabelText("데모 데이터셋 올리기 (gen-v1 JSON)");
    fireEvent.change(input, { target: { files: [new File(["not json"], "x.json", { type: "application/json" })] } });

    expect(await screen.findByText(/JSON 파일이 아닙니다/)).toBeDefined();
    await waitFor(() => expect(importSimulationDataset).not.toHaveBeenCalled());
  });

  test("a file over the size limit is rejected before it is read or sent", async () => {
    const importSimulationDataset = vi.fn();
    const api = { simulationDatasets: vi.fn().mockResolvedValue([]), simulations: vi.fn().mockResolvedValue([]), commands: { importSimulationDataset } };
    renderWithApp(<SimulationView />, { as: session("MANAGER", "mgr"), api: api as never });

    const big = new File(["{}"], "big.json", { type: "application/json" });
    Object.defineProperty(big, "size", { value: MAX_DATASET_BYTES + 1 });
    fireEvent.change(await screen.findByLabelText("데모 데이터셋 올리기 (gen-v1 JSON)"), { target: { files: [big] } });

    expect(await screen.findByText(/파일이 너무 큽니다/)).toBeDefined();
    expect(importSimulationDataset).not.toHaveBeenCalled();
  });
});
