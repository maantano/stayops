"use client";

import Link from "next/link";
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

/** 첫 화면에서 사용 설명서의 자주 찾는 탭으로 바로 간다. */
const GUIDE_SHORTCUTS = [
  { hash: "start", label: "빠른 시작" },
  { hash: "roles", label: "역할별 할 일" },
  { hash: "help", label: "막혔을 때" },
];

const ROLE_HINT: Record<string, string> = {
  MANAGER: "배정·취소·판매 중단·재정비, 전체 현황",
  FRONT_DESK: "체크인·체크아웃, 투숙 중 청소 요청, 준비 예측 확인",
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
          실제 로그인 대신 직원을 골라 그 역할로 화면을 봅니다. 보안 수단이 아닌 데모용입니다.
        </p>
      </header>

      <section aria-labelledby="guide-cta-title" className="flex flex-wrap items-center gap-4 rounded-lg border border-sky-300 bg-sky-50 p-5">
        <div className="min-w-60 flex-1 space-y-2">
          <h2 id="guide-cta-title" className="text-lg font-semibold text-sky-900">
            처음 사용하시나요?
          </h2>
          <p className="text-sm text-sky-900">역할별로 할 일, 화면에 보이는 상태의 뜻, 막혔을 때 해결 방법을 사용 설명서에 정리했습니다.</p>
          <nav aria-label="사용 설명서 바로 가기" className="flex flex-wrap gap-2 text-sm">
            {GUIDE_SHORTCUTS.map((s) => (
              <Link key={s.hash} href={`/guide#${s.hash}`} className="rounded-full border border-sky-300 bg-white px-3 py-1 text-sky-900 hover:bg-sky-100">
                {s.label}
              </Link>
            ))}
          </nav>
        </div>
        <Link
          href="/guide"
          className="inline-flex items-center gap-2 rounded-md bg-zinc-900 px-5 py-2.5 font-semibold text-white hover:bg-zinc-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-600"
        >
          사용 설명서 보기 <span aria-hidden="true">→</span>
        </Link>
      </section>

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
