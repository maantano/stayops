import { describe, expect, test, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { toApiError } from "@/api/errors";
import { ErrorNotice } from "@/components/ErrorNotice";
import { renderWithApp, session } from "@/test/render";
import { checkInHint, ReservationsView } from "./ReservationsView";

const row = (roomNumber: string, roomWorkflowStage: string, roomAvailabilityStatus = "AVAILABLE", status = "CONFIRMED") => ({
  reservationId: `res-${roomNumber}`, roomId: `room-${roomNumber}`, roomNumber, status, roomWorkflowStage, roomAvailabilityStatus,
  scheduledCheckInAt: "2026-11-04T12:00:00Z", scheduledCheckOutAt: "2026-11-05T12:00:00Z", guestCount: 2, version: 0,
});

describe("check-in eligibility is explained before clicking", () => {
  test("hint names what is left, or the out-of-service reason", () => {
    expect(checkInHint("READY", false)).toBeNull();
    expect(checkInHint("READY", true)).toContain("판매를 재개");
    expect(checkInHint("CLEANING", false)).toContain("청소 완료와 검사가 남았습니다");
    expect(checkInHint("AWAITING_INSPECTION", false)).toContain("검사 배정과 검사");
  });

  test("rows show the room stage; only a ready, available room can be checked in", async () => {
    const reservations = vi.fn(async (_p: string, q: { status?: string[] }) =>
      q.status?.includes("CONFIRMED")
        ? { items: [row("101", "READY"), row("202", "NEEDS_CLEANING"), row("102", "READY", "OUT_OF_SERVICE")], nextCursor: null, limit: 50 }
        : { items: [], nextCursor: null, limit: 50 },
    );
    renderWithApp(<ReservationsView />, { as: session("FRONT_DESK", "fd"), api: { reservations } as never });

    const arrivals = await screen.findByRole("region", { name: "도착 예정" });
    const item = (n: string) => within(arrivals).getAllByRole("listitem").find((li) => li.textContent?.includes(`${n}호`))!;
    await screen.findByText(/202호/);

    expect(within(item("101")).getByRole("button", { name: "체크인" }).hasAttribute("disabled")).toBe(false);
    const dirty = within(item("202")).getByRole("button", { name: "체크인" });
    expect(dirty.hasAttribute("disabled")).toBe(true);
    expect(item("202").textContent).toContain("객실 청소 필요");
    expect(document.getElementById(dirty.getAttribute("aria-describedby")!)?.textContent).toContain("청소 배정·청소·검사가 남았습니다");
    expect(within(item("102")).getByRole("button", { name: "체크인" }).hasAttribute("disabled")).toBe(true);
    expect(item("102").textContent).toContain("판매 중단된 객실이라 체크인할 수 없습니다");
  });
});

describe("unknown room state", () => {
  test("without room fields (older API) nothing is blocked and the server decides", async () => {
    const legacy = { ...row("101", "READY") } as Record<string, unknown>;
    delete legacy.roomWorkflowStage;
    delete legacy.roomAvailabilityStatus;
    const reservations = vi.fn(async (_p: string, q: { status?: string[] }) =>
      q.status?.includes("CONFIRMED") ? { items: [legacy], nextCursor: null, limit: 50 } : { items: [], nextCursor: null, limit: 50 },
    );
    renderWithApp(<ReservationsView />, { as: session("FRONT_DESK", "fd"), api: { reservations } as never });
    const button = await screen.findByRole("button", { name: "체크인" });
    expect(button.hasAttribute("disabled")).toBe(false);
    expect(screen.queryByText(/준비 완료가 되어야/)).toBeNull();
  });
});

describe("error notice", () => {
  test("shows a Korean message; the English server detail is only a secondary line", () => {
    const error = toApiError(409, { code: "INVALID_STATE", detail: "room is not ready", roomOperationalStatus: "CLEANING", correlationId: "c-1" });
    render(<ErrorNotice error={error} />);
    const alert = screen.getByRole("alert");
    expect(alert.querySelector("p")?.textContent).toBe("현재 상태에서는 할 수 없는 요청입니다. 화면을 최신 상태로 다시 불러왔으니 상태를 확인하세요.");
    expect(alert.textContent).toContain("객실 상태: 청소 중");
    expect(alert.textContent).toContain("서버 설명: room is not ready");
  });
});
