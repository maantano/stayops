import { SyntheticDataBadge } from "@/components/SyntheticDataBadge";

export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-6 py-16">
      <header className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">
          StayOps — Room Readiness &amp; Operations
        </h1>
        <SyntheticDataBadge />
      </header>
      <p className="text-zinc-600">
        객실 준비 상태, 청소·검사 작업, 차단 사유, 담당자 부하를 한 흐름으로 확인하는 운영 지원
        도구입니다. 현재는 프로젝트 골격 단계이며 운영 화면은 이후 단계에서 구현합니다.
      </p>
    </main>
  );
}
