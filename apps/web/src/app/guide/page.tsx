import type { Metadata } from "next";
import { GuideView } from "@/features/guide/GuideView";

export const metadata: Metadata = { title: "사용 설명서 · StayOps" };

export default function Page() {
  return <GuideView />;
}
