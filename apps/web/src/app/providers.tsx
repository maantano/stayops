"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { createStayOpsApi } from "@/api/endpoints";
import { isApiError } from "@/api/errors";
import { ApiProvider } from "@/api/hooks";
import { loadDemoSession } from "@/api/session";

/** API base URL. 배포 환경에서는 NEXT_PUBLIC_STAYOPS_API_BASE_URL로 지정한다. */
const API_BASE_URL = process.env.NEXT_PUBLIC_STAYOPS_API_BASE_URL ?? "http://localhost:8080";

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
  const propertyId = loadDemoSession()?.propertyId ?? null;

  return (
    <QueryClientProvider client={queryClient}>
      <ApiProvider api={api} propertyId={propertyId}>
        {children}
      </ApiProvider>
    </QueryClientProvider>
  );
}
