import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, test } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { GUIDE, guideToMarkdown } from "./content";
import { GuideView } from "./GuideView";

const MD_PATH = path.resolve(__dirname, "../../../../../docs/USER_GUIDE.md");

describe("user guide", () => {
  test("docs/USER_GUIDE.md is generated from content.ts (STAYOPS_WRITE_GUIDE=true to regenerate)", () => {
    const expected = guideToMarkdown();
    if (process.env.STAYOPS_WRITE_GUIDE === "true") writeFileSync(MD_PATH, expected);
    expect(existsSync(MD_PATH), "run: STAYOPS_WRITE_GUIDE=true pnpm test guide").toBe(true);
    expect(readFileSync(MD_PATH, "utf8")).toBe(expected);
  });

  test("section ids are unique and tables are rectangular", () => {
    expect(new Set(GUIDE.map((s) => s.id)).size).toBe(GUIDE.length);
    for (const block of GUIDE.flatMap((s) => s.blocks)) {
      if (block.kind === "table") for (const row of block.rows) expect(row.length, row[0]).toBe(block.head.length);
    }
  });

  test("the page renders a table of contents linking to every section", () => {
    render(<GuideView />);
    const toc = screen.getByRole("navigation", { name: "설명서 목차" });
    expect(within(toc).getAllByRole("link").map((a) => a.getAttribute("href"))).toEqual(GUIDE.map((s) => `#${s.id}`));
    for (const s of GUIDE) expect(screen.getByRole("heading", { level: 2, name: s.title })).toBeDefined();
  });
});
