/**
 * 화면 표시용 한국어 라벨과 상태 톤 (03 §4). 상태는 항상 텍스트로 표시하고 색은 보조로만 쓴다.
 * tone은 의미 분류이며 실제 색은 Badge가 정한다.
 */
export type Tone = "neutral" | "info" | "progress" | "success" | "warning" | "danger" | "muted";

type LabelMap = Record<string, { label: string; tone: Tone }>;

export const workflowStageLabels: LabelMap = {
  NEEDS_CLEANING: { label: "청소 필요", tone: "warning" },
  CLEANING: { label: "청소 중", tone: "progress" },
  AWAITING_INSPECTION: { label: "검사 대기", tone: "info" },
  INSPECTING: { label: "검사 중", tone: "progress" },
  READY: { label: "준비 완료", tone: "success" },
  OCCUPIED: { label: "투숙 중", tone: "neutral" },
  STAYOVER_CLEANING: { label: "투숙 중 청소", tone: "progress" },
};

export const operationalStatusLabels: LabelMap = {
  VACANT_DIRTY: { label: "공실·청소 전", tone: "warning" },
  CLEANING: { label: "청소 중", tone: "progress" },
  INSPECTING: { label: "검사 중", tone: "progress" },
  READY: { label: "준비 완료", tone: "success" },
  OCCUPIED: { label: "투숙 중", tone: "neutral" },
  STAYOVER_CLEANING: { label: "투숙 중 청소", tone: "progress" },
};

export const availabilityLabels: LabelMap = {
  AVAILABLE: { label: "판매 가능", tone: "muted" },
  OUT_OF_SERVICE: { label: "판매 중단", tone: "danger" },
};

export const taskStatusLabels: LabelMap = {
  PENDING: { label: "대기", tone: "neutral" },
  ASSIGNED: { label: "배정됨", tone: "info" },
  IN_PROGRESS: { label: "진행 중", tone: "progress" },
  BLOCKED: { label: "차단됨", tone: "danger" },
  COMPLETED: { label: "완료", tone: "success" },
  CANCELLED: { label: "취소", tone: "muted" },
};

export const taskTypeLabels: Record<string, string> = {
  CHECKOUT_CLEANING: "체크아웃 청소",
  STAYOVER_CLEANING: "투숙 중 청소",
  RE_CLEANING: "재청소",
  INSPECTION: "검사",
};

export const taskOriginLabels: Record<string, string> = {
  CHECKOUT_COMMAND: "체크아웃",
  CLEANING_COMPLETED: "청소 완료",
  INSPECTION_FAILED: "검사 실패",
  STAYOVER_REQUEST: "투숙 중 요청",
  MANAGER_REWORK: "관리자 재정비",
};

export const gradeLabels: LabelMap = {
  AT_RISK: { label: "늦을 예정", tone: "danger" },
  WARNING: { label: "빠듯함", tone: "warning" },
  ON_TRACK: { label: "여유 있음", tone: "success" },
  READY: { label: "준비 완료", tone: "success" },
  UNKNOWN: { label: "예측 불가", tone: "info" },
};

export const reservationStatusLabels: LabelMap = {
  CONFIRMED: { label: "확정", tone: "info" },
  CHECKED_IN: { label: "투숙 중", tone: "neutral" },
  CHECKED_OUT: { label: "퇴실", tone: "muted" },
  CANCELLED: { label: "취소", tone: "muted" },
  NO_SHOW: { label: "노쇼", tone: "muted" },
};

export const blockerTypeLabels: Record<string, string> = {
  DND: "방해 금지",
  LINEN_SHORTAGE: "린넨 부족",
  SUPPLY_SHORTAGE: "비품 부족",
  MAINTENANCE: "시설 점검",
  STAFF_UNAVAILABLE: "인력 부재",
  OTHER: "기타",
};

export const roleLabels: Record<string, string> = {
  MANAGER: "운영 관리자",
  FRONT_DESK: "프런트",
  HOUSEKEEPER: "하우스키핑",
  INSPECTOR: "검사",
};

export const contributionLabels: Record<string, string> = {
  WAIT_FOR_CHECKOUT: "현재 투숙객 체크아웃 대기",
  WAIT_FOR_SCHEDULE: "작업 예정 시각까지 대기",
  BLOCKER: "차단 해제 대기",
  CLEANING: "청소",
  INSPECTION: "검사",
};

export const flagLabels: Record<string, string> = {
  CANNOT_CHECK_IN_OUT_OF_SERVICE: "판매 중단 — 체크인 불가",
  UNASSIGNED: "담당자 미배정",
  OVERRUN: "예상 소요 초과",
  WORK_INTERRUPTED_NO_ASSIGNEE: "담당자 없음 · 진행 중단",
  NO_SHIFT: "근무 없음",
  OVER_CAPACITY: "근무 시간 초과 배정",
  EXPECTED_END_AFTER_SHIFT_END: "근무 종료 후 완료 예상",
  ASSIGNEE_INVALID_ON_RESUME: "담당자 무효로 대기 복귀",
};

/** readiness `missingInputs`(서버 진단 문자열)를 사용자 문장으로. 알 수 없는 값은 원문을 보여준다. */
export function missingInputLabel(input: string): string {
  if (input.startsWith("blocker.estimatedResolutionAt")) {
    return input.includes("has passed") ? "차단 해제 예상 시각이 지남" : "차단 해제 예상 시각 없음";
  }
  if (input.startsWith("currentStay.actualCheckOutAt")) return "예정 체크아웃이 지났지만 아직 투숙 중";
  if (input.startsWith("currentStay.scheduledCheckOutAt")) return "현재 투숙 정보 없음";
  if (input.startsWith("activeTask.assignee")) return "작업이 담당자 없이 중단됨";
  if (input.includes("inspection task missing")) return "검사 작업 없음";
  if (input.includes("cleaning task missing")) return "청소 작업 없음";
  if (input.startsWith("activeTask")) return "작업 상태를 판단할 수 없음";
  return input;
}

export function labelOf(map: LabelMap, value: string | undefined | null) {
  if (!value) return { label: "-", tone: "muted" as Tone };
  return map[value] ?? { label: value, tone: "neutral" as Tone };
}
