import { Suspense } from "react";
import { AppShell } from "@/components/AppShell";
import { LoadingState } from "@/components/ui";
import { RoomsView } from "@/features/rooms/RoomsView";

/** URL을 읽는 client hook(usePathname·useSearchParams)이 있으므로 전체를 Suspense로 감싼다 (Next 16 prerender 규칙). */
export default function Page() {
  return (
    <Suspense fallback={<LoadingState />}>
      <AppShell title="객실">
        <RoomsView />
      </AppShell>
    </Suspense>
  );
}
