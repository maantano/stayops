import { afterEach, expect, test } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import Home from "./page";

afterEach(cleanup);

test("홈 화면에 제품명 heading이 있다", () => {
  render(<Home />);
  expect(screen.getByRole("heading", { level: 1, name: /StayOps/ })).toBeDefined();
});

test("합성 데이터 배지를 텍스트로 표시한다", () => {
  render(<Home />);
  expect(screen.getByText("합성 데이터")).toBeDefined();
});
