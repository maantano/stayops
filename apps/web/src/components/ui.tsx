import type { ButtonHTMLAttributes, ReactNode } from "react";
import type { Tone } from "@/lib/labels";

const TONE_CLASS: Record<Tone, string> = {
  neutral: "border-zinc-300 bg-zinc-50 text-zinc-800",
  info: "border-sky-300 bg-sky-50 text-sky-900",
  progress: "border-indigo-300 bg-indigo-50 text-indigo-900",
  success: "border-emerald-300 bg-emerald-50 text-emerald-900",
  warning: "border-amber-400 bg-amber-50 text-amber-900",
  danger: "border-rose-400 bg-rose-50 text-rose-900",
  muted: "border-zinc-200 bg-white text-zinc-500",
};

/** 상태를 색만으로 전달하지 않는다: 항상 텍스트 라벨을, 필요하면 앞에 짧은 기호를 함께 쓴다 (03 §4, §8). */
const TONE_MARK: Partial<Record<Tone, string>> = { danger: "!", warning: "△", success: "✓", progress: "…" };

export function Badge({ tone, label, prefix, title }: { tone: Tone; label: string; prefix?: string; title?: string }) {
  const mark = TONE_MARK[tone];
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium ${TONE_CLASS[tone]}`} title={title}>
      {mark ? <span aria-hidden="true">{mark}</span> : null}
      {prefix ? <span className="sr-only">{prefix}: </span> : null}
      {label}
    </span>
  );
}

type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";
const BUTTON_CLASS: Record<ButtonVariant, string> = {
  primary: "bg-zinc-900 text-white hover:bg-zinc-700 disabled:bg-zinc-400",
  secondary: "border border-zinc-300 bg-white text-zinc-900 hover:bg-zinc-50 disabled:text-zinc-400",
  danger: "bg-rose-700 text-white hover:bg-rose-600 disabled:bg-rose-300",
  ghost: "text-zinc-700 hover:bg-zinc-100 disabled:text-zinc-400",
};

export function Button({ variant = "secondary", className = "", ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex items-center justify-center gap-1 rounded-md px-3 py-1.5 text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-600 disabled:cursor-not-allowed ${BUTTON_CLASS[variant]} ${className}`}
    />
  );
}

export function Card({ children, className = "", as: Tag = "section" }: { children: ReactNode; className?: string; as?: "section" | "article" | "div" | "li" }) {
  return <Tag className={`rounded-lg border border-zinc-200 bg-white p-4 shadow-sm ${className}`}>{children}</Tag>;
}

/** 로딩·빈 상태·오류는 서로 다른 화면이다 (03 §4). */
export function LoadingState({ label = "불러오는 중" }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" className="space-y-2 py-4">
      <span className="sr-only">{label}</span>
      <div className="h-4 w-1/3 animate-pulse rounded bg-zinc-200" />
      <div className="h-4 w-2/3 animate-pulse rounded bg-zinc-200" />
    </div>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="rounded-md border border-dashed border-zinc-300 p-4 text-sm text-zinc-600">{children}</p>;
}

export function Field({ label, htmlFor, error, hint, children }: { label: string; htmlFor: string; error?: string; hint?: string; children: ReactNode }) {
  return (
    <div className="space-y-1">
      <label htmlFor={htmlFor} className="block text-sm font-medium text-zinc-800">
        {label}
      </label>
      {children}
      {hint ? <p className="text-xs text-zinc-500">{hint}</p> : null}
      {error ? (
        <p role="alert" className="text-xs text-rose-700">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export const inputClass = "w-full rounded-md border border-zinc-300 px-2 py-1.5 text-sm focus-visible:outline-2 focus-visible:outline-sky-600";
