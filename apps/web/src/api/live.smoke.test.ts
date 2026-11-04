import { describe, expect, test } from "vitest";
import { createStayOpsApi } from "./endpoints";

/**
 * 실행 중인 API(demo profile)에 typed client를 붙여 실제 응답 형태를 확인하는 읽기 전용 smoke.
 * 기본 테스트에서는 건너뛴다: `STAYOPS_LIVE_API=http://localhost:8080 pnpm test live`
 */
const base = process.env.STAYOPS_LIVE_API;

describe.skipIf(!base)("live API smoke (read-only)", () => {
  test("demo actors → dashboard, readiness, rooms, tasks, timeline as front desk and housekeeper", async () => {
    const anonymous = createStayOpsApi({ baseUrl: base!, getStaffId: () => null });
    const property = (await anonymous.demoActors()).find((p) => p.name.includes("데모"));
    expect(property).toBeDefined();
    const frontDesk = property!.actors.find((a) => a.role === "FRONT_DESK")!;
    const housekeeper = property!.actors.find((a) => a.role === "HOUSEKEEPER")!;
    const asFrontDesk = createStayOpsApi({ baseUrl: base!, getStaffId: () => frontDesk.staffId });
    const asHousekeeper = createStayOpsApi({ baseUrl: base!, getStaffId: () => housekeeper.staffId });
    const pid = property!.propertyId;

    const dashboard = await asFrontDesk.dashboard(pid);
    expect(dashboard.syntheticData).toBe(true);
    expect(dashboard.rooms.total).toBeGreaterThan(0);

    const readiness = await asFrontDesk.readiness(pid);
    expect(new Set(readiness.rooms.map((r) => r.grade)).size).toBeGreaterThan(1);
    for (const r of readiness.rooms.filter((x) => x.expectedReadyAt)) {
      const sum = r.contributions.reduce((acc, c) => acc + c.seconds, 0);
      expect(sum).toBe((Date.parse(r.expectedReadyAt!) - Date.parse(r.calculatedAt)) / 1000);
    }

    const rooms = await asFrontDesk.rooms(pid, { limit: 5 });
    expect(rooms.items).toHaveLength(5);
    const next = await asFrontDesk.rooms(pid, { limit: 5, cursor: rooms.nextCursor ?? undefined });
    expect(next.items[0].roomNumber > rooms.items[4].roomNumber).toBe(true);

    const timeline = await asFrontDesk.timeline(pid, rooms.items.find((r) => r.workflowStage === "CLEANING")?.roomId ?? rooms.items[0].roomId);
    expect(Array.isArray(timeline.items)).toBe(true);

    const mine = await asHousekeeper.tasks(pid);
    expect(mine.items.every((t) => t.assignee?.staffId === housekeeper.staffId)).toBe(true);

    const forbidden = await asHousekeeper.dashboard(pid).catch((e: unknown) => e as { code?: string });
    expect((forbidden as { code?: string }).code).toBe("FORBIDDEN");
  });
});
