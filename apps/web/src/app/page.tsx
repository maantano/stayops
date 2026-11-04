import { DemoLogin } from "@/features/demo/DemoLogin";

const API_BASE_URL = process.env.NEXT_PUBLIC_STAYOPS_API_BASE_URL ?? "http://localhost:8080";

export default function Home() {
  return <DemoLogin baseUrl={API_BASE_URL} />;
}
