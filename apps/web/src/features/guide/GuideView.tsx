"use client";

import Link from "next/link";
import { Fragment, useSyncExternalStore, type KeyboardEvent, type ReactNode } from "react";
import { SyntheticDataBadge } from "@/components/SyntheticDataBadge";
import { Badge } from "@/components/ui";
import { availabilityLabels, gradeLabels, labelOf, roleLabels, taskStatusLabels, workflowStageLabels, type Tone } from "@/lib/labels";
import { GUIDE_SUMMARY, GUIDE_TABS, GUIDE_TITLE, TOKEN_PATTERN, type GuideBlock } from "./content";

/** 토큰 → 글자 (Markdown 생성과 같은 라벨). */
const plain = (m: Record<string, { label: string }>) => Object.fromEntries(Object.entries(m).map(([k, v]) => [k, v.label]));
export const GUIDE_LABELS: Record<string, Record<string, string>> = {
  role: roleLabels,
  stage: plain(workflowStageLabels),
  task: plain(taskStatusLabels),
  grade: plain(gradeLabels),
  avail: plain(availabilityLabels),
};

const STATE_MAPS: Record<string, Record<string, { label: string; tone: Tone }>> = {
  stage: workflowStageLabels,
  task: taskStatusLabels,
  grade: gradeLabels,
  avail: availabilityLabels,
};

/** 역할마다 다른 색의 칩. 상태 배지(둥근 알약)와 헷갈리지 않게 각진 모양을 쓴다. */
const ROLE_CHIP: Record<string, string> = {
  MANAGER: "border-indigo-300 bg-indigo-50 text-indigo-900",
  FRONT_DESK: "border-sky-300 bg-sky-50 text-sky-900",
  HOUSEKEEPER: "border-emerald-300 bg-emerald-50 text-emerald-900",
  INSPECTOR: "border-amber-400 bg-amber-50 text-amber-900",
};

export function RoleChip({ role }: { role: string }) {
  return (
    <span className={`inline-flex items-center rounded-md border px-1.5 py-0.5 text-xs font-semibold ${ROLE_CHIP[role] ?? ""}`}>
      <span className="sr-only">역할: </span>
      {roleLabels[role] ?? role}
    </span>
  );
}

/** 글 안의 [[kind:value]] 토큰을 칩·배지·버튼 모양으로 바꿔 그린다. */
function Rich({ text }: { text: string }) {
  const parts: ReactNode[] = [];
  let last = 0;
  for (const m of text.matchAll(TOKEN_PATTERN)) {
    const [whole, kind, value] = m;
    if (m.index > last) parts.push(text.slice(last, m.index));
    const key = `${m.index}`;
    if (kind === "role") parts.push(<RoleChip key={key} role={value} />);
    else if (kind === "btn")
      parts.push(
        <span key={key} className="inline-block rounded border border-zinc-300 bg-white px-1.5 py-px text-xs font-medium text-zinc-900 shadow-sm">
          {value}
          <span className="sr-only"> 버튼</span>
        </span>,
      );
    else {
      const l = labelOf(STATE_MAPS[kind] ?? {}, value);
      parts.push(<Badge key={key} tone={l.tone} label={l.label} />);
    }
    last = m.index + whole.length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return <>{parts}</>;
}

// 선택한 탭은 주소의 #에 둔다(공유·새로고침 유지). 섹션 id로 들어오면 그 섹션이 있는 탭을 연다.
const subscribe = (cb: () => void) => {
  window.addEventListener("hashchange", cb);
  return () => window.removeEventListener("hashchange", cb);
};
const readHash = () => decodeURIComponent(window.location.hash.slice(1));

function tabForHash(hash: string): string {
  const byTab = GUIDE_TABS.find((t) => t.id === hash);
  if (byTab) return byTab.id;
  return GUIDE_TABS.find((t) => t.sections.some((s) => s.id === hash))?.id ?? GUIDE_TABS[0].id;
}

/** 사용 설명서 화면. 로그인 없이 볼 수 있고, 내용은 content.ts 하나에서 온다(docs/USER_GUIDE.md와 같은 원본). */
export function GuideView() {
  const hash = useSyncExternalStore(subscribe, readHash, () => "");
  const active = tabForHash(hash);
  // 주소의 #만 바꾸고(페이지 이동 없음) 구독 중인 화면에 알린다. 뒤로 가기는 이전 탭으로 돌아간다.
  const select = (id: string) => {
    window.history.pushState(null, "", `#${id}`);
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  };
  const onKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const n = GUIDE_TABS.length;
    const next = { ArrowRight: (index + 1) % n, ArrowLeft: (index - 1 + n) % n, Home: 0, End: n - 1 }[e.key];
    if (next === undefined) return;
    e.preventDefault();
    select(GUIDE_TABS[next].id);
    document.getElementById(`tab-${GUIDE_TABS[next].id}`)?.focus();
  };

  return (
    <main className="mx-auto w-full max-w-5xl space-y-6 px-4 py-10">
      <header className="space-y-3">
        <p>
          <Link href="/" className="text-sm text-zinc-600 underline underline-offset-2">
            ← StayOps로 돌아가기
          </Link>
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">{GUIDE_TITLE}</h1>
          <SyntheticDataBadge />
        </div>
        <p className="text-zinc-700">{GUIDE_SUMMARY}</p>
        <p className="flex flex-wrap items-center gap-2 text-sm text-zinc-600">
          표시 읽는 법: <RoleChip role="MANAGER" /> 역할 · <Badge tone="warning" label="청소 필요" /> 상태 ·{" "}
          <span className="inline-block rounded border border-zinc-300 bg-white px-1.5 py-px text-xs font-medium text-zinc-900 shadow-sm">체크인</span> 화면의 버튼
        </p>
      </header>

      <div role="tablist" aria-label="설명서 구분" className="flex flex-wrap gap-1 border-b border-zinc-200">
        {GUIDE_TABS.map((t, i) => (
          <button
            key={t.id}
            id={`tab-${t.id}`}
            type="button"
            role="tab"
            aria-selected={active === t.id}
            aria-controls={`panel-${t.id}`}
            tabIndex={active === t.id ? 0 : -1}
            onClick={() => select(t.id)}
            onKeyDown={(e) => onKey(e, i)}
            className="-mb-px rounded-t-md border border-transparent px-4 py-2 text-sm font-medium text-zinc-600 hover:text-zinc-900 focus-visible:outline-2 focus-visible:outline-sky-600 aria-selected:border-zinc-200 aria-selected:border-b-zinc-50 aria-selected:bg-zinc-50 aria-selected:text-zinc-900"
          >
            {t.title}
          </button>
        ))}
      </div>

      {GUIDE_TABS.map((t) => (
        <div key={t.id} id={`panel-${t.id}`} role="tabpanel" aria-labelledby={`tab-${t.id}`} hidden={active !== t.id} tabIndex={0} className="space-y-8">
          <p className="text-sm text-zinc-600">{t.summary}</p>
          {t.sections.map((section) => (
            <section key={section.id} id={section.id} aria-labelledby={`${section.id}-title`} className="scroll-mt-6 space-y-3">
              <h2 id={`${section.id}-title`} className="text-xl font-semibold">
                {section.title}
              </h2>
              {section.blocks.map((block, i) => (
                <Block key={i} block={block} />
              ))}
            </section>
          ))}
        </div>
      ))}
    </main>
  );
}

function Block({ block }: { block: GuideBlock }) {
  switch (block.kind) {
    case "p":
      return (
        <p className="leading-relaxed text-zinc-800">
          <Rich text={block.text} />
        </p>
      );
    case "h3":
      return (
        <h3 className="pt-2 font-semibold">
          <Rich text={block.text} />
        </h3>
      );
    case "note":
      return (
        <p className="rounded-md border border-sky-300 bg-sky-50 px-3 py-2 text-sm text-sky-900">
          <Rich text={block.text} />
        </p>
      );
    case "list":
      return (
        <ul className="list-disc space-y-1.5 pl-5 leading-relaxed text-zinc-800">
          {block.items.map((item) => (
            <li key={item}>
              <Rich text={item} />
            </li>
          ))}
        </ul>
      );
    case "steps":
      return (
        <ol className="space-y-2">
          {block.items.map((item, i) => (
            <li key={item} className="flex gap-3 leading-relaxed text-zinc-800">
              <span aria-hidden="true" className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-900 text-xs font-semibold text-white">
                {i + 1}
              </span>
              <span>
                <span className="sr-only">{i + 1}단계: </span>
                <Rich text={item} />
              </span>
            </li>
          ))}
        </ol>
      );
    case "flow":
      return (
        <ol aria-label="객실 준비 흐름" className="flex flex-wrap items-stretch gap-2">
          {block.items.map((item, i) => (
            <Fragment key={item.label}>
              <li className="flex min-w-32 flex-1 flex-col gap-1 rounded-lg border border-zinc-200 bg-white p-3 text-sm">
                <span>
                  <Rich text={item.label} />
                </span>
                <span className="text-zinc-600">{item.text}</span>
              </li>
              {i < block.items.length - 1 ? (
                <li aria-hidden="true" className="flex items-center text-zinc-400">
                  →
                </li>
              ) : null}
            </Fragment>
          ))}
        </ol>
      );
    case "cards":
      return (
        <div className="grid gap-3 md:grid-cols-2">
          {block.items.map((card) => (
            <article key={card.title} className="space-y-2 rounded-lg border border-zinc-200 bg-white p-4">
              <h3 className="flex flex-wrap items-center gap-2 font-semibold">
                <Rich text={card.title} />
              </h3>
              <dl className="space-y-2 text-sm">
                {card.fields.map((f) => (
                  <div key={f.label}>
                    <dt className="text-xs font-semibold text-zinc-500">{f.label}</dt>
                    <dd className="leading-relaxed text-zinc-800">
                      {f.steps ? (
                        <ol className="mt-1 list-decimal space-y-1 pl-5">
                          {f.steps.map((step) => (
                            <li key={step}>
                              <Rich text={step} />
                            </li>
                          ))}
                        </ol>
                      ) : (
                        <Rich text={f.text ?? ""} />
                      )}
                    </dd>
                  </div>
                ))}
              </dl>
            </article>
          ))}
        </div>
      );
    case "table":
      return (
        <div className="overflow-x-auto rounded-lg border border-zinc-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="bg-zinc-50">
              <tr>
                {block.head.map((h) => (
                  <th key={h} scope="col" className="whitespace-nowrap px-3 py-2 font-semibold">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row) => (
                <tr key={row.join("|")} className="border-t border-zinc-100 align-top">
                  {row.map((cell, i) =>
                    i === 0 ? (
                      <th key={i} scope="row" className="px-3 py-2 font-medium whitespace-nowrap">
                        <Rich text={cell} />
                      </th>
                    ) : (
                      <td key={i} className="px-3 py-2 leading-relaxed text-zinc-700">
                        <Rich text={cell} />
                      </td>
                    ),
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
  }
}
