import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

afterEach(cleanup);

// jsdom은 <dialog>의 showModal/close를 구현하지 않는다.
if (typeof HTMLDialogElement !== "undefined" && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute("open");
    // 브라우저처럼 close 이벤트는 task로 나중에 보낸다(HTML 명세 "queue an element task"). 동기로 보내면 타이밍 버그를 못 잡는다.
    setTimeout(() => this.dispatchEvent(new Event("close")));
  };
}

// next/navigation은 App Router 밖(jsdom)에서 쓸 수 없으므로 최소 동작으로 대체한다.
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/",
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));
