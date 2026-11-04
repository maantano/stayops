"use client";

import { useCallback } from "react";
import type { CommandName } from "@/api/endpoints";
import { useCommand } from "@/api/hooks";
import { useToast } from "@/components/Toast";

/**
 * 화면용 command 실행기: 성공 알림(재사용 응답이면 표시), 오류는 `error`로 노출해 ErrorNotice가 설명한다.
 * 중복 클릭은 `pending`으로 막는다 (03 §5).
 */
export function useCommandRunner<C extends CommandName>(name: C, successMessage: string) {
  const command = useCommand(name);
  const toast = useToast();

  const notify = useCallback(
    (replayed: boolean) => toast(replayed ? `${successMessage} (이미 처리된 요청의 결과)` : successMessage),
    [successMessage, toast],
  );

  const execute = useCallback(
    async (...args: Parameters<typeof command.run>) => {
      try {
        const outcome = await command.run(...args);
        notify(outcome.replayed);
        return outcome;
      } catch {
        return undefined;
      }
    },
    [command, notify],
  );

  const retry = useCallback(async () => {
    try {
      const outcome = await command.retry();
      notify(outcome.replayed);
      return outcome;
    } catch {
      return undefined;
    }
  }, [command, notify]);

  return { execute, retry, error: command.error, pending: command.isPending, reset: command.reset };
}
