import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, test } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { GUIDE, GUIDE_TABS, guideToMarkdown, TOKEN_PATTERN } from "./content";
import { GUIDE_LABELS, GuideView } from "./GuideView";

const MD_PATH = path.resolve(__dirname, "../../../../../docs/USER_GUIDE.md");

const allText = () =>
  GUIDE.flatMap((s) => [
    s.title,
    ...s.blocks.flatMap((b) => {
      switch (b.kind) {
        case "list":
        case "steps":
          return b.items;
        case "table":
          return [...b.head, ...b.rows.flat()];
        case "flow":
          return b.items.flatMap((i) => [i.label, i.text]);
        case "cards":
          return b.items.flatMap((c) => [c.title, ...c.fields.flatMap((f) => f.steps ?? [f.text ?? ""])]);
        default:
          return [b.text];
      }
    }),
  ]);

afterEach(() => {
  window.location.hash = "";
});

describe("user guide content", () => {
  test("docs/USER_GUIDE.md is generated from content.ts (STAYOPS_WRITE_GUIDE=true to regenerate)", () => {
    const expected = guideToMarkdown(GUIDE_LABELS);
    if (process.env.STAYOPS_WRITE_GUIDE === "true") writeFileSync(MD_PATH, expected);
    expect(existsSync(MD_PATH), "run: STAYOPS_WRITE_GUIDE=true pnpm test guide").toBe(true);
    expect(readFileSync(MD_PATH, "utf8")).toBe(expected);
  });

  test("every role/state token names a label the app really shows", () => {
    const unknown = allText()
      .flatMap((t) => [...t.matchAll(TOKEN_PATTERN)])
      .filter(([, kind, value]) => kind !== "btn" && !GUIDE_LABELS[kind]?.[value])
      .map(([whole]) => whole);
    expect(unknown).toEqual([]);
  });

  test("ids are unique and tables are rectangular", () => {
    const ids = [...GUIDE_TABS.map((t) => t.id), ...GUIDE.map((s) => s.id)];
    expect(new Set(ids).size).toBe(ids.length);
    for (const block of GUIDE.flatMap((s) => s.blocks)) {
      if (block.kind === "table") for (const row of block.rows) expect(row.length, row[0]).toBe(block.head.length);
    }
  });

  test("no leftover wording the user asked to remove", () => {
    expect(allText().join("\n")).not.toMatch(/합성|자주 만나는|준비 위험|판정 불가/);
  });
});

describe("guide page", () => {
  test("tabs switch panels, keep the choice in the URL and open the tab of a linked section", async () => {
    render(<GuideView />);
    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((t) => t.textContent)).toEqual(GUIDE_TABS.map((t) => t.title));
    expect(screen.getByRole("tab", { name: "빠른 시작" }).getAttribute("aria-selected")).toBe("true");

    fireEvent.click(screen.getByRole("tab", { name: "역할별" }));
    expect(window.location.hash).toBe("#roles");
    expect((await screen.findByRole("tabpanel", { name: "역할별" })).textContent).toContain("하루 흐름");

    act(() => {
      window.location.hash = "readiness";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(await screen.findByRole("heading", { level: 2, name: "준비 예측" })).toBeDefined();
    expect(screen.getByRole("tab", { name: "상태별" }).getAttribute("aria-selected")).toBe("true");
  });

  test("roles render as chips and states as the same badges the app uses", async () => {
    render(<GuideView />);
    fireEvent.click(screen.getByRole("tab", { name: "상태별" }));
    const panel = await screen.findByRole("tabpanel", { name: "상태별" });
    expect(panel.textContent).toContain("역할: 하우스키핑");
    expect(panel.textContent).toContain("늦을 예정");
    expect(panel.textContent).not.toContain("[[");
  });
});
