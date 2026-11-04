"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { createStayOpsApi } from "@/api/endpoints";
import { useDemoSession } from "@/app/providers";
import { homePathFor } from "@/components/AppShell";
import { ErrorNotice } from "@/components/ErrorNotice";
import { SyntheticDataBadge } from "@/components/SyntheticDataBadge";
import { Card, EmptyState, LoadingState } from "@/components/ui";
import { roleLabels } from "@/lib/labels";

const ROLE_HINT: Record<string, string> = {
  MANAGER: "배정·취소·판매 중단·재정비, 전체 현황",
  FRONT_DESK: "체크인·체크아웃, 투숙 중 청소 요청, 준비 위험 확인",
  HOUSEKEEPER: "본인 작업 시작·차단·완료",
  INSPECTOR: "본인 검사 시작·결과 입력",
};

/**
 * 데모 사용자 선택 (ADR-005). 실제 로그인이 아니며 선택한 직원의 권한으로 화면과 API를 쓴다.
 * 인증 전 목록 조회이므로 요청자 헤더 없이 부른다.
 */
export function DemoLogin({ baseUrl }: { baseUrl: string }) {
  const { session, hydrated, setSession } = useDemoSession();
  const router = useRouter();
  const [api] = useState(() => createStayOpsApi({ baseUrl, getStaffId: () => null }));
  const actors = useQuery({ queryKey: ["demo-actors"], queryFn: () => api.demoActors() });

  useEffect(() => {
    if (hydrated && session) router.replace(homePathFor(session.role));
  }, [hydrated, session, router]);

  return (
    <main className="mx-auto w-full max-w-3xl space-y-6 px-4 py-12">
      <header className="space-y-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">StayOps — 데모 사용자 선택</h1>
          <SyntheticDataBadge />
        </div>
        <p className="text-sm text-zinc-600">
          실제 로그인 대신 직원을 골라 그 역할로 화면을 봅니다. 보안 수단이 아닌 합성 데이터 데모용입니다.
        </p>
      </header>

      {actors.isPending ? <LoadingState /> : null}
      {actors.isError ? <ErrorNotice error={actors.error} /> : null}
      {actors.data?.length === 0 ? (
        <EmptyState>데모 데이터가 없습니다. API를 `demo` profile로 실행하세요: ./gradlew bootRun --args=&apos;--spring.profiles.active=demo&apos;</EmptyState>
      ) : null}

      {actors.data?.map((property) => (
        <Card key={property.propertyId}>
          <h2 className="mb-3 font-semibold">
            {property.name} <span className="text-sm font-normal text-zinc-500">({property.timezone})</span>
          </h2>
          <ul className="grid gap-2 sm:grid-cols-2">
            {property.actors.map((actor) => (
              <li key={actor.staffId}>
                <button
                  type="button"
                  onClick={() =>
                    setSession({
                      staffId: actor.staffId,
                      propertyId: property.propertyId,
                      displayName: actor.displayName,
                      role: actor.role,
                      propertyName: property.name,
                      timezone: property.timezone,
                    })
                  }
                  className="w-full rounded-md border border-zinc-200 p-3 text-left hover:border-zinc-400 hover:bg-zinc-50 focus-visible:outline-2 focus-visible:outline-sky-600"
                >
                  <span className="block font-medium">{actor.displayName}</span>
                  <span className="block text-xs text-zinc-500">
                    {roleLabels[actor.role] ?? actor.role} — {ROLE_HINT[actor.role]}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </Card>
      ))}
    </main>
  );
}
