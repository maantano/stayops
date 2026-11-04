"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { useDemoSession } from "@/app/providers";
import { roleLabels } from "@/lib/labels";
import { SyntheticDataBadge } from "./SyntheticDataBadge";
import { ToastProvider } from "./Toast";
import { Button } from "./ui";

/** 역할별 메뉴. 서버가 최종 권한을 판단하므로 숨김은 편의일 뿐이다 (03 §1). */
const NAV = [
  { href: "/dashboard", label: "대시보드", roles: ["MANAGER", "FRONT_DESK"] },
  { href: "/rooms", label: "객실", roles: ["MANAGER", "FRONT_DESK", "HOUSEKEEPER", "INSPECTOR"] },
  { href: "/tasks", label: "작업", roles: ["MANAGER", "FRONT_DESK", "HOUSEKEEPER", "INSPECTOR"] },
  { href: "/reservations", label: "예약", roles: ["MANAGER", "FRONT_DESK"] },
  { href: "/simulations", label: "시뮬레이션", roles: ["MANAGER", "FRONT_DESK"] },
];

export function homePathFor(role: string): string {
  return role === "MANAGER" || role === "FRONT_DESK" ? "/dashboard" : "/tasks";
}

/** 로그인한 데모 사용자만 볼 수 있는 화면의 공통 틀. 세션이 없으면 사용자 선택 화면으로 보낸다. */
export function AppShell({ title, children }: { title: string; children: ReactNode }) {
  const { session, hydrated, setSession } = useDemoSession();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (hydrated && !session) router.replace("/");
  }, [hydrated, session, router]);

  if (!session) return null;

  return (
    <ToastProvider>
      <div className="min-h-full bg-zinc-50">
        <header className="border-b border-zinc-200 bg-white">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
            <Link href={homePathFor(session.role)} className="font-semibold tracking-tight">
              StayOps
            </Link>
            <SyntheticDataBadge />
            <nav aria-label="주 메뉴" className="flex gap-1">
              {NAV.filter((n) => n.roles.includes(session.role)).map((n) => (
                <Link
                  key={n.href}
                  href={n.href}
                  aria-current={pathname.startsWith(n.href) ? "page" : undefined}
                  className="rounded-md px-3 py-1.5 text-sm text-zinc-700 hover:bg-zinc-100 aria-[current=page]:bg-zinc-900 aria-[current=page]:text-white"
                >
                  {n.label}
                </Link>
              ))}
            </nav>
            <div className="ml-auto flex items-center gap-3 text-sm">
              <Link href="/guide" className="text-zinc-700 underline-offset-2 hover:underline">
                사용법
              </Link>
              <span className="text-zinc-600">
                {session.propertyName} · <strong className="text-zinc-900">{session.displayName}</strong> ({roleLabels[session.role] ?? session.role}) ·{" "}
                <span title="시각은 호텔 시간대로 표시합니다">{session.timezone}</span>
              </span>
              <Button variant="ghost" onClick={() => setSession(null)}>
                사용자 변경
              </Button>
            </div>
          </div>
        </header>
        <main className="mx-auto max-w-6xl space-y-6 px-4 py-6">
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          {children}
        </main>
      </div>
    </ToastProvider>
  );
}
