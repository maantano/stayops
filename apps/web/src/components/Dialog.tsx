"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";

/**
 * 접근 가능한 modal (03 §8): 네이티브 `<dialog>`의 `showModal()`로 포커스를 가두고 Esc로 닫는다.
 * 닫히면 열기 전 포커스로 되돌린다. 제목은 `aria-labelledby`로 연결한다.
 */
export function Dialog({ open, title, onClose, children }: { open: boolean; title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  const restoreFocus = useRef<HTMLElement | null>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      restoreFocus.current = document.activeElement as HTMLElement | null;
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const handleClose = () => {
      restoreFocus.current?.focus();
      onClose();
    };
    dialog.addEventListener("close", handleClose);
    return () => dialog.removeEventListener("close", handleClose);
  }, [onClose]);

  // ×·취소 버튼이나 제출 성공으로 부모가 dialog를 unmount하면 close 이벤트가 오지 않는다 — 이때도 포커스를 되돌린다 (P12 E2E에서 발견).
  useEffect(() => {
    const dialog = ref.current;
    const restore = restoreFocus;
    return () => {
      if (dialog?.open) dialog.close();
      const target = restore.current;
      if (target?.isConnected) target.focus();
    };
  }, []);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      className="m-auto w-[min(32rem,calc(100vw-2rem))] rounded-lg border border-zinc-200 p-0 shadow-xl backdrop:bg-black/40"
    >
      {open ? (
        <div className="space-y-4 p-5">
          <div className="flex items-start justify-between gap-4">
            <h2 id={titleId} className="text-lg font-semibold">
              {title}
            </h2>
            <button type="button" onClick={onClose} className="rounded px-2 text-zinc-500 hover:bg-zinc-100" aria-label="닫기">
              ×
            </button>
          </div>
          {children}
        </div>
      ) : null}
    </dialog>
  );
}
