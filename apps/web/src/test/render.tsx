import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi } from "vitest";
import type { StayOpsApi } from "@/api/endpoints";
import { ApiProvider } from "@/api/hooks";
import type { DemoSession } from "@/api/session";
import { SessionContext } from "@/app/providers";
import { ToastProvider } from "@/components/Toast";

export const PROPERTY = "22222222-2222-2222-2222-222222222222";

export function session(role: string, staffId = "staff-self"): DemoSession {
  return { staffId, propertyId: PROPERTY, displayName: `${role}-USER`, role, propertyName: "테스트 호텔", timezone: "Asia/Seoul" };
}

/** 세션·API·Query·Toast를 주입해 렌더링한다. api는 필요한 메서드만 채운 부분 객체로 준다. */
export function renderWithApp(ui: ReactElement, { as, api = {} }: { as: DemoSession; api?: Omit<Partial<StayOpsApi>, "commands"> & { commands?: Partial<StayOpsApi["commands"]> } }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const fullApi = { ...api, commands: { ...(api.commands ?? {}) } } as unknown as StayOpsApi;
  const setSession = vi.fn();
  const result = render(
    <QueryClientProvider client={queryClient}>
      <SessionContext.Provider value={{ session: as, hydrated: true, setSession }}>
        <ApiProvider api={fullApi} propertyId={as.propertyId}>
          <ToastProvider>{ui}</ToastProvider>
        </ApiProvider>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
  return { ...result, setSession, queryClient };
}
