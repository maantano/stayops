import Link from "next/link";
import { SyntheticDataBadge } from "@/components/SyntheticDataBadge";
import { GUIDE, GUIDE_SUMMARY, GUIDE_TITLE, type GuideBlock } from "./content";

/** 사용 설명서 화면. 로그인 없이 볼 수 있고, 내용은 content.ts 하나에서 온다(docs/USER_GUIDE.md와 같은 원본). */
export function GuideView() {
  return (
    <main className="mx-auto w-full max-w-4xl space-y-8 px-4 py-10">
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
        <nav aria-label="설명서 목차" className="rounded-lg border border-zinc-200 bg-white p-4">
          <h2 className="mb-2 text-sm font-semibold">목차</h2>
          <ol className="grid gap-1 text-sm sm:grid-cols-2">
            {GUIDE.map((s) => (
              <li key={s.id}>
                <a href={`#${s.id}`} className="underline-offset-2 hover:underline">
                  {s.title}
                </a>
              </li>
            ))}
          </ol>
        </nav>
      </header>

      {GUIDE.map((section) => (
        <section key={section.id} id={section.id} aria-labelledby={`${section.id}-title`} className="scroll-mt-6 space-y-3">
          <h2 id={`${section.id}-title`} className="text-xl font-semibold">
            {section.title}
          </h2>
          {section.blocks.map((block, i) => (
            <Block key={i} block={block} />
          ))}
        </section>
      ))}
    </main>
  );
}

function Block({ block }: { block: GuideBlock }) {
  switch (block.kind) {
    case "p":
      return <p className="leading-relaxed text-zinc-800">{block.text}</p>;
    case "h3":
      return <h3 className="pt-2 font-semibold">{block.text}</h3>;
    case "note":
      return <p className="rounded-md border border-sky-300 bg-sky-50 px-3 py-2 text-sm text-sky-900">{block.text}</p>;
    case "list":
      return (
        <ul className="list-disc space-y-1 pl-5 text-zinc-800">
          {block.items.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      );
    case "steps":
      return (
        <ol className="list-decimal space-y-1 pl-5 text-zinc-800">
          {block.items.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ol>
      );
    case "table":
      return (
        <div className="overflow-x-auto rounded-lg border border-zinc-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="bg-zinc-50">
              <tr>
                {block.head.map((h) => (
                  <th key={h} scope="col" className="px-3 py-2 font-semibold">
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
                      <th key={i} scope="row" className="px-3 py-2 font-medium">
                        {cell}
                      </th>
                    ) : (
                      <td key={i} className="px-3 py-2 text-zinc-700">
                        {cell}
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
