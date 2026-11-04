"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useState, useSyncExternalStore, type ReactNode } from "react";
import { createStayOpsApi } from "@/api/endpoints";
import { isApiError } from "@/api/errors";
import { ApiProvider } from "@/api/hooks";
import { loadDemoSession, saveDemoSession, type DemoSession } from "@/api/session";

/** API base URL. 배포 환경에서는 NEXT_PUBLIC_STAYOPS_API_BASE_URL로 지정한다. */
const API_BASE_URL = process.env.NEXT_PUBLIC_STAYOPS_API_BASE_URL ?? "http://localhost:8080";

type SessionContextValue = {
  session: DemoSession | null;
  /** 세션 읽기가 끝났는지(서버 렌더 중에는 false). 화면은 이 값으로 로그인 리다이렉트를 늦춘다. */
  hydrated: boolean;
  setSession: (session: DemoSession | null) => void;
};

/** 테스트에서 세션을 주입할 수 있도록 export 한다. */
export const SessionContext = createContext<SessionContextValue | null>(null);

export function useDemoSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error("Providers is missing");
  return value;
}

const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // 4xx는 재시도해도 결과가 같으므로 재시도하지 않는다. 네트워크·5xx만 1회.
            retry: (failureCount, error) => failureCount < 1 && !(isApiError(error) && error.status >= 400 && error.status < 500),
            refetchOnWindowFocus: true,
          },
          mutations: { retry: false },
        },
      }),
  );
  const [api] = useState(() => createStayOpsApi({ baseUrl: API_BASE_URL, getStaffId: () => loadDemoSession()?.staffId ?? null }));
  const session = useSyncExternalStore(subscribe, loadDemoSession, () => null);
  const hydrated = useSyncExternalStore(subscribe, () => true, () => false);

  const setSession = useCallback(
    (next: DemoSession | null) => {
      saveDemoSession(next);
      queryClient.clear(); // 다른 사용자·property의 캐시를 섞지 않는다.
      listeners.forEach((listener) => listener());
    },
    [queryClient],
  );

  return (
    <QueryClientProvider client={queryClient}>
      <SessionContext.Provider value={{ session, hydrated, setSession }}>
        <ApiProvider api={api} propertyId={session?.propertyId ?? null}>
          {children}
        </ApiProvider>
      </SessionContext.Provider>
    </QueryClientProvider>
  );
}
