/**
 * 사용 설명서의 단일 원본. 앱의 `/guide` 페이지와 저장소의 `docs/USER_GUIDE.md`가 모두 이 데이터에서 만들어진다.
 * 문서를 고치면 `STAYOPS_WRITE_GUIDE=true pnpm test guide`로 Markdown을 다시 만든다 (테스트가 불일치를 잡는다).
 * 규칙의 근거는 docs/v4.1/02_DOMAIN_AND_API.md(도메인)와 04 §5(권한)이다. 규칙이 바뀌면 이 문서도 함께 고친다.
 *
 * 글 안의 표시(토큰) — 화면에서는 칩·배지·버튼 모양으로, Markdown에서는 글자로 바뀐다.
 *   [[role:MANAGER]]  역할 칩        [[stage:NEEDS_CLEANING]] 객실 업무 단계 배지
 *   [[task:ASSIGNED]] 작업 상태 배지  [[grade:AT_RISK]]        준비 예측 배지
 *   [[btn:체크인]]     화면의 버튼    [[avail:OUT_OF_SERVICE]]  판매 상태 배지
 */

export type GuideBlock =
  | { kind: "p"; text: string }
  | { kind: "h3"; text: string }
  | { kind: "note"; text: string }
  | { kind: "list"; items: string[] }
  | { kind: "steps"; items: string[] }
  | { kind: "table"; head: string[]; rows: string[][] }
  | { kind: "flow"; items: { label: string; text: string }[] }
  | { kind: "cards"; items: { title: string; fields: GuideField[] }[] };

/** 카드 항목. 순서가 있는 일은 steps로 적어 번호 목록으로 보인다. */
export type GuideField = { label: string; text?: string; steps?: string[] };

export type GuideSection = { id: string; title: string; blocks: GuideBlock[] };
export type GuideTab = { id: string; title: string; summary: string; sections: GuideSection[] };

export const GUIDE_TITLE = "StayOps 사용 설명서";
export const GUIDE_SUMMARY =
  "손님이 도착하기 전에 객실 청소와 검사를 끝내도록 돕는 도구입니다. 내 역할에서 무엇을 하면 되는지, 화면에 보이는 상태가 무슨 뜻인지, 막혔을 때 어떻게 하는지를 정리했습니다.";

const ROLE = { M: "[[role:MANAGER]]", F: "[[role:FRONT_DESK]]", H: "[[role:HOUSEKEEPER]]", I: "[[role:INSPECTOR]]" };

export const GUIDE_TABS: GuideTab[] = [
  {
    id: "start",
    title: "빠른 시작",
    summary: "처음 들어왔다면 여기부터 보세요.",
    sections: [
      {
        id: "what",
        title: "StayOps는 무엇을 하나요?",
        blocks: [
          {
            kind: "p",
            text: "손님이 체크아웃하면 객실은 청소와 검사를 거쳐야 다음 손님을 받을 수 있습니다. StayOps는 이 과정에서 지금 어디가 막혀 있는지, 누가 무엇을 해야 하는지, 다음 손님이 오기 전에 준비가 끝날지를 한 화면에서 보여 줍니다.",
          },
          {
            kind: "flow",
            items: [
              { label: "[[stage:OCCUPIED]]", text: "손님이 머무는 중" },
              { label: "[[stage:NEEDS_CLEANING]]", text: "체크아웃 후 청소 대기" },
              { label: "[[stage:CLEANING]]", text: "하우스키핑이 청소" },
              { label: "[[stage:AWAITING_INSPECTION]]", text: "청소 끝, 검사 대기" },
              { label: "[[stage:INSPECTING]]", text: "검사자가 확인" },
              { label: "[[stage:READY]]", text: "다음 손님 체크인 가능" },
            ],
          },
        ],
      },
      {
        id: "first-steps",
        title: "처음 사용하기",
        blocks: [
          {
            kind: "steps",
            items: [
              "첫 화면에서 직원을 한 명 고릅니다. 실제 로그인이 아니라, 고른 직원의 역할로 화면을 보는 데모 방식입니다.",
              `처음이라면 ${ROLE.M}(MGR-01)로 시작하세요. 모든 메뉴를 볼 수 있습니다.`,
              "다른 역할로 바꾸려면 오른쪽 위 [[btn:사용자 변경]]을 누릅니다. 한 객실의 일을 끝까지 해 보려면 역할을 여러 번 바꿔야 합니다.",
              "언제든 오른쪽 위 [[btn:사용법]]을 누르면 이 설명서로 돌아옵니다.",
            ],
          },
          {
            kind: "note",
            text: "화면의 모든 데이터는 데모 데이터입니다. 매일 새벽 4시(한국 시간)에 지금 시각 기준으로 새로 만들어지므로, 그 사이에 바꾼 내용은 다음 날 처음 상태로 돌아갑니다.",
          },
          {
            kind: "list",
            items: [
              "시각은 모두 호텔 시간(한국 시간)으로 보입니다.",
              "대시보드와 준비 예측은 30초마다 저절로 새로 고쳐집니다. 다른 역할로 처리한 일도 곧바로 반영됩니다.",
            ],
          },
        ],
      },
      {
        id: "walkthrough",
        title: "데모로 따라 해 보기",
        blocks: [
          { kind: "p", text: "데모 데이터가 새로 만들어졌을 때 각 객실의 상태입니다. 아래 순서대로 해 보면 주요 기능을 모두 써 볼 수 있습니다." },
          {
            kind: "table",
            head: ["객실", "처음 상태", "해 볼 것"],
            rows: [
              ["202호", "[[stage:NEEDS_CLEANING]] 담당자 없음", `${ROLE.M} 배정 → ${ROLE.H} HK-02 시작·완료 → ${ROLE.M} 검사 배정 → ${ROLE.I} 검사(실패도 해 보기) → ${ROLE.F} 체크인`],
              ["101호", "[[stage:READY]]", `${ROLE.F} 예약 화면에서 바로 [[btn:체크인]]`],
              ["203호", "[[stage:CLEANING]] HK-01 담당", `${ROLE.H} HK-01로 바꿔 [[btn:차단 등록]] → [[btn:해제]] → [[btn:완료]]`],
              ["204호", "[[stage:CLEANING]] 린넨 부족으로 [[task:BLOCKED]]", "대시보드에서 차단 해제 예상 시각이 예측에 반영된 것 확인 → HK-02로 [[btn:해제]]"],
              ["301호 / 302호", "[[stage:AWAITING_INSPECTION]] / [[stage:INSPECTING]]", `${ROLE.I} IN-01로 바꿔 검사 시작, [[btn:검사 결과 입력]]`],
              ["303호", "검사 실패 후 재청소 대기", "작업 카드의 생성 원인 '검사 실패' 확인 → 재청소 배정"],
              ["304호", "방해 금지(DND)로 [[task:BLOCKED]], 해제 예상 시각 없음", "준비 예측이 [[grade:UNKNOWN]]인 이유 확인"],
              ["102호", "[[stage:READY]]이지만 [[avail:OUT_OF_SERVICE]]", `${ROLE.F} 체크인 버튼이 꺼진 이유 확인 → ${ROLE.M} 객실 상세에서 [[btn:판매 재개]] → 체크인`],
              ["104호", "[[stage:OCCUPIED]], 투숙 중 청소 요청 있음", `${ROLE.M} 배정 → ${ROLE.H} HK-01 시작 → ${ROLE.F} 체크아웃 시도(거부와 해결 방법 확인) → HK-01 완료 → 체크아웃`],
              ["201호", "예정 퇴실 시각이 지났는데 아직 투숙 중", "준비 예측이 [[grade:UNKNOWN]]인 이유 확인 → 체크아웃하면 청소가 시작됨"],
            ],
          },
        ],
      },
    ],
  },
  {
    id: "roles",
    title: "역할별",
    summary: "내 역할에서 하루 동안 무엇을 하는지 정리했습니다.",
    sections: [
      {
        id: "role-cards",
        title: "역할별 업무",
        blocks: [
          {
            kind: "cards",
            items: [
              {
                title: `${ROLE.M} MGR-01`,
                fields: [
                  { label: "보이는 메뉴", text: "대시보드 · 객실 · 작업 · 예약 · 시뮬레이션" },
                  { label: "하루 흐름", steps: [
                    "대시보드에서 [[grade:AT_RISK]]·[[grade:UNKNOWN]] 객실 확인",
                    "작업 화면에서 [[task:PENDING]] 작업을 근무 중인 직원에게 [[btn:배정]]",
                    "청소가 끝나 생긴 검사 작업을 검사자에게 배정",
                    "[[task:BLOCKED]] 작업의 원인 해결을 돕고 [[btn:해제]]",
                    "필요하면 [[btn:판매 중단]]·[[btn:판매 재개]], [[btn:재청소 작업 만들기]]",
                  ] },
                  { label: "할 수 없는 일", text: "직원 대신 작업을 시작·완료하거나 검사 결과를 입력하는 일. 실제 작업은 담당자 본인이 합니다." },
                ],
              },
              {
                title: `${ROLE.F} FD-01`,
                fields: [
                  { label: "보이는 메뉴", text: "대시보드 · 객실 · 작업 · 예약 · 시뮬레이션(보기만)" },
                  { label: "하루 흐름", steps: [
                    "대시보드에서 오늘 도착 손님의 객실이 준비될지 확인",
                    "퇴실 손님 [[btn:체크아웃]] (청소 작업이 자동으로 생김)",
                    "도착 손님 [[btn:체크인]] ([[stage:READY]]이고 판매 중일 때만)",
                    "투숙 손님이 청소를 원하면 객실 상세에서 [[btn:투숙 중 청소 요청]]",
                  ] },
                  { label: "할 수 없는 일", text: "작업 배정, 판매 중단, 시작된 작업 취소. 시작 전인 투숙 중 청소는 취소할 수 있습니다." },
                ],
              },
              {
                title: `${ROLE.H} HK-01 · HK-02`,
                fields: [
                  { label: "보이는 메뉴", text: "객실 · 작업 (내게 배정된 작업만 보입니다)" },
                  { label: "하루 흐름", steps: [
                    "작업 화면에서 내 작업 확인",
                    "객실에 들어가며 [[btn:시작]]",
                    "린넨이 없거나 방해 금지 등으로 막히면 [[btn:차단 등록]] (풀릴 때까지 몇 분인지 적어 주세요)",
                    "풀리면 [[btn:해제]]",
                    "끝나면 [[btn:완료]]. 그러면 검사 작업이 자동으로 생깁니다.",
                  ] },
                  { label: "참고", text: "HK-03은 근무가 끝난 직원입니다. 배정하면 '근무 시간이 아니다'라는 안내가 나오는 예시입니다." },
                ],
              },
              {
                title: `${ROLE.I} IN-01`,
                fields: [
                  { label: "보이는 메뉴", text: "객실 · 작업 (내게 배정된 검사만 보입니다)" },
                  { label: "하루 흐름", steps: [
                    "작업 화면에서 내 검사 확인",
                    "[[btn:시작]]",
                    "[[btn:검사 결과 입력]]에서 욕실·침구·바닥·비품을 확인하고 통과 또는 실패 선택",
                    "통과하면 객실이 [[stage:READY]]가 됩니다. 실패하면 사유를 적고, 재청소 작업이 자동으로 생깁니다.",
                  ] },
                  { label: "할 수 없는 일", text: "청소 작업 처리, 다른 사람의 검사 결과 입력." },
                ],
              },
            ],
          },
          {
            kind: "note",
            text: "화면에 버튼이 안 보인다면 그 역할로는 할 수 없는 일입니다. 버튼을 숨기는 것은 편의일 뿐이고, 최종 권한은 서버가 다시 확인합니다.",
          },
        ],
      },
    ],
  },
  {
    id: "states",
    title: "상태별",
    summary: "화면의 상태 표시가 무슨 뜻이고, 그다음 무엇을 하면 되는지.",
    sections: [
      {
        id: "room-states",
        title: "객실 업무 단계",
        blocks: [
          { kind: "p", text: "객실 화면과 예약 화면에 보이는 단계입니다. 단계마다 다음에 할 일과 담당 역할이 정해져 있습니다." },
          {
            kind: "table",
            head: ["단계", "뜻", "다음에 할 일", "담당"],
            rows: [
              ["[[stage:NEEDS_CLEANING]]", "손님이 나갔고 아직 청소 전", "청소 작업을 배정하고 시작. 작업이 없으면(취소된 경우) 객실 상세에서 [[btn:재청소 작업 만들기]]", `${ROLE.M} → ${ROLE.H}`],
              ["[[stage:CLEANING]]", "청소하는 중", "청소를 끝내고 [[btn:완료]]. 막히면 [[btn:차단 등록]]", ROLE.H],
              ["[[stage:AWAITING_INSPECTION]]", "청소는 끝났고 검사 전", "검사를 배정하고 시작. 검사 작업이 없으면 [[btn:재검사 작업 만들기]]", `${ROLE.M} → ${ROLE.I}`],
              ["[[stage:INSPECTING]]", "검사하는 중", "[[btn:검사 결과 입력]]", ROLE.I],
              ["[[stage:READY]]", "검사 통과, 손님을 받을 수 있음", "손님이 오면 [[btn:체크인]]. 판매 중단 상태라면 먼저 [[btn:판매 재개]]", `${ROLE.F} ${ROLE.M}`],
              ["[[stage:OCCUPIED]]", "손님이 머무는 중", "청소 요청이 있으면 [[btn:투숙 중 청소 요청]], 퇴실하면 [[btn:체크아웃]]", `${ROLE.F} ${ROLE.M}`],
              ["[[stage:STAYOVER_CLEANING]]", "머무는 손님의 방을 청소하는 중", "청소를 끝내고 [[btn:완료]]. 이 동안에는 체크아웃할 수 없습니다", ROLE.H],
            ],
          },
          {
            kind: "list",
            items: [
              "[[avail:OUT_OF_SERVICE]]은 업무 단계와 별개입니다. 새 손님의 체크인만 막고, 이미 머무는 손님이나 진행 중인 작업에는 영향이 없습니다.",
              "한 객실에는 진행 중인 작업이 하나만 있을 수 있습니다.",
              "작업을 만들거나 배정만 해서는 객실 단계가 바뀌지 않습니다. 담당자가 [[btn:시작]]을 눌러야 바뀝니다.",
            ],
          },
        ],
      },
      {
        id: "task-states",
        title: "작업 상태",
        blocks: [
          {
            kind: "table",
            head: ["상태", "뜻", "누를 수 있는 버튼"],
            rows: [
              ["[[task:PENDING]]", "담당자가 아직 없음", `${ROLE.M} [[btn:배정]] [[btn:차단 등록]] [[btn:작업 취소]] · ${ROLE.F} 투숙 중 청소만 [[btn:작업 취소]]`],
              ["[[task:ASSIGNED]]", "담당자가 정해졌고 시작 전", `담당자 [[btn:시작]] [[btn:차단 등록]] · ${ROLE.M} [[btn:재배정]] [[btn:배정 해제]] [[btn:작업 취소]]`],
              ["[[task:IN_PROGRESS]]", "작업하는 중", `${ROLE.H} [[btn:완료]] · ${ROLE.I} [[btn:검사 결과 입력]] · 담당자·${ROLE.M} [[btn:차단 등록]] · ${ROLE.M} [[btn:작업 취소]] (사유 필수)`],
              ["[[task:BLOCKED]]", "차단 사유 때문에 멈춤", "차단 사유 옆 [[btn:해제]] (담당자·관리자). 모두 풀리면 멈추기 전 상태로 돌아갑니다"],
              ["[[task:COMPLETED]] [[task:CANCELLED]]", "끝난 작업", "없음. 작업 화면 위쪽 상태 버튼에서 '완료'·'취소'를 켜면 볼 수 있습니다"],
            ],
          },
          {
            kind: "list",
            items: [
              "[[btn:재개]]: 시작했다가 멈춘 작업을 다시 시작할 때 [[btn:시작]] 대신 보입니다. 처음 시작한 시각은 그대로 남습니다.",
              "'담당자 없음 · 진행 중단' 표시: 시작했던 작업이 담당자 없이 대기로 돌아간 경우입니다. 관리자가 다시 배정해야 합니다.",
              "작업을 취소하면: 시작 전이었다면 객실 단계는 그대로이고, 시작한 뒤였다면 이전 단계로 돌아갑니다(청소·검사 → [[stage:NEEDS_CLEANING]], 투숙 중 청소 → [[stage:OCCUPIED]]).",
            ],
          },
        ],
      },
      {
        id: "readiness",
        title: "준비 예측",
        blocks: [
          {
            kind: "p",
            text: "도착 예정 손님이 있는 객실마다, 남은 일(퇴실 대기 → 청소 → 검사)이 끝나는 시각을 계산해 '준비 마감'(체크인 30분 전)과 비교합니다. 이미 일한 시간과 차단이 풀릴 예상 시각도 반영합니다.",
          },
          {
            kind: "table",
            head: ["표시", "뜻", "할 일"],
            rows: [
              ["[[grade:AT_RISK]]", "이대로면 준비 마감보다 늦게 끝납니다 ('N분 늦음')", "원인을 보고 배정을 서두르거나 차단을 먼저 해결하세요"],
              ["[[grade:UNKNOWN]]", "예측에 필요한 정보가 없어 계산하지 않았습니다", "이유를 확인하세요. 예: 풀릴 시각을 모르는 차단, 퇴실 예정이 지났는데 아직 투숙 중, 담당자 없이 멈춘 작업"],
              ["[[grade:WARNING]]", "마감 전에 끝나지만 여유가 30분이 안 됩니다 ('N분 여유')", "늦어지지 않도록 지켜보세요"],
              ["[[grade:ON_TRACK]]", "마감보다 30분 이상 일찍 끝납니다", "따로 할 일은 없습니다"],
              ["[[grade:READY]]", "이미 검사를 통과했습니다", "판매 중단 상태라면 체크인할 수 없으니 확인하세요"],
            ],
          },
          {
            kind: "list",
            items: [
              "정보가 부족하면 짐작해서 '여유 있음'으로 보여 주지 않고 [[grade:UNKNOWN]]로 표시합니다.",
              "근무표와 검사 실패 가능성은 예측에 넣지 않습니다(검사는 통과한다고 봅니다).",
              "여유 30분과 작업별 예상 시간(체크아웃 청소 45분, 재청소 30분, 검사 10분, 투숙 중 청소 25분)은 데모용으로 정한 값입니다.",
            ],
          },
        ],
      },
    ],
  },
  {
    id: "menus",
    title: "메뉴별",
    summary: "화면마다 무엇을 볼 수 있고 어떻게 쓰는지.",
    sections: [
      {
        id: "menu-dashboard",
        title: "대시보드",
        blocks: [
          { kind: "p", text: `볼 수 있는 역할: ${ROLE.M} ${ROLE.F}` },
          {
            kind: "list",
            items: [
              "맨 위 '준비 예측'에서 도착 예정 객실을 표시별로 셉니다. 그 아래는 확인이 필요한 객실 목록으로, 몇 분 늦을지와 원인을 함께 보여 줍니다.",
              "가운데는 객실 단계별 수, 오늘 도착·출발, 상태별 작업 수, 직원별 업무량입니다. 작업 수를 누르면 그 상태만 걸러진 작업 화면으로 갑니다.",
              "맨 아래 '도착 예정 객실 전체 · 예측 근거'를 펼치면 객실마다 어떤 일이 몇 분씩 걸리는지 볼 수 있습니다.",
            ],
          },
        ],
      },
      {
        id: "menu-rooms",
        title: "객실 · 객실 상세",
        blocks: [
          { kind: "p", text: `볼 수 있는 역할: ${ROLE.M} ${ROLE.F} ${ROLE.H} ${ROLE.I}` },
          {
            kind: "list",
            items: [
              "객실 번호를 누르면 상세 화면이 열립니다. 업무 단계·판매 상태, 진행 중인 작업, 준비 예측 근거, 누가 언제 무엇을 했는지(이벤트 기록)를 볼 수 있습니다.",
              `[[btn:투숙 중 청소 요청]] — ${ROLE.M} ${ROLE.F}, 손님이 머물고 진행 중인 작업이 없을 때`,
              `[[btn:재청소 작업 만들기]] — ${ROLE.M}, [[stage:NEEDS_CLEANING]]인데 작업이 없을 때 (사유 입력)`,
              `[[btn:재검사 작업 만들기]] — ${ROLE.M}, [[stage:AWAITING_INSPECTION]]인데 작업이 없을 때 (사유 입력)`,
              `[[btn:판매 중단]] · [[btn:판매 재개]] — ${ROLE.M} (사유 입력)`,
            ],
          },
        ],
      },
      {
        id: "menu-tasks",
        title: "작업",
        blocks: [
          { kind: "p", text: `볼 수 있는 역할: ${ROLE.M} ${ROLE.F} ${ROLE.H} ${ROLE.I} (하우스키핑·검사는 자기 작업만)` },
          {
            kind: "list",
            items: [
              "처음에는 [[task:PENDING]] [[task:ASSIGNED]] [[task:IN_PROGRESS]] [[task:BLOCKED]] 작업만 보입니다. 위쪽 상태·작업 종류 버튼으로 걸러 볼 수 있고, 걸러 둔 조건은 주소에 남아 그대로 공유할 수 있습니다.",
              "작업 카드에는 객실 번호(누르면 객실 상세), 작업 종류, 상태, 예정 시각, 예상 시간('(기본값)'은 설정값), 담당자, 시작 시각, 생긴 이유, 차단 사유가 보입니다.",
              "[[btn:배정]]: 청소는 하우스키핑, 검사는 검사 직원만 고를 수 있습니다. 목록에 직원별 현재 배정 건수와 근무 경고가 함께 보입니다.",
              "[[btn:차단 등록]]: 사유(방해 금지·린넨 부족·비품 부족·시설 점검·인력 부재·기타)를 고르고 풀릴 때까지 몇 분인지 적습니다. 비워 두면 준비 예측이 [[grade:UNKNOWN]]가 됩니다. '기타'는 설명을 적어야 합니다.",
              "[[btn:검사 결과 입력]]: 욕실·침구·바닥·비품을 확인하고 통과 또는 실패를 고릅니다. 실패하면 사유를 적어야 합니다.",
            ],
          },
        ],
      },
      {
        id: "menu-reservations",
        title: "예약",
        blocks: [
          { kind: "p", text: `볼 수 있는 역할: ${ROLE.M} ${ROLE.F}` },
          {
            kind: "list",
            items: [
              "왼쪽 '도착 예정'에서 [[btn:체크인]], 오른쪽 '투숙 중'에서 [[btn:체크아웃]]을 합니다. 예약마다 객실의 지금 단계가 함께 보입니다.",
              "체크인은 객실이 [[stage:READY]]이고 판매 중일 때만 됩니다. 아직 안 되는 객실은 버튼이 꺼져 있고, 무엇이 남았는지와 객실로 가는 링크가 나옵니다. 예정보다 일찍 와도 객실이 준비됐다면 체크인할 수 있습니다.",
              "체크아웃하면 청소 작업이 자동으로 생깁니다. 아직 시작하지 않은 투숙 중 청소는 함께 취소됩니다.",
              "투숙 중 청소가 진행 중인 객실은 체크아웃이 막힌다는 안내가 미리 보입니다. 그래도 누르면 담당자와 해결 방법을 알려 줍니다.",
            ],
          },
        ],
      },
      {
        id: "menu-simulations",
        title: "시뮬레이션",
        blocks: [
          { kind: "p", text: `실행: ${ROLE.M} · 보기: ${ROLE.F}` },
          {
            kind: "steps",
            items: [
              "데모 데이터셋 파일(JSON, 5MB 이하)을 올립니다. 저장소의 tools/simulator에서 `uv run stayops-simulator generate --seed 42 --out output/dataset-42.json`으로 만들 수 있습니다. 같은 파일은 한 번만 저장됩니다.",
              "데이터셋과 비교할 운영 방식을 고릅니다. 기준 방식(먼저 생긴 작업부터)은 항상 포함됩니다.",
              "[[btn:실행]]을 누르면 결과 표가 나옵니다. 방식마다 기준과 비교한 차이와 '개선'·'악화'가 글로 표시됩니다. 가동률처럼 높고 낮음만으로 좋고 나쁨을 말할 수 없는 지표는 판단하지 않습니다.",
              "이전에 실행한 결과는 '실행 기록'에서 다시 볼 수 있습니다.",
            ],
          },
          { kind: "note", text: "시뮬레이션 결과는 데모 데이터로 운영 방식끼리 비교한 값입니다. 실제 운영 성과나 최적의 방법을 보장하지 않습니다." },
        ],
      },
    ],
  },
  {
    id: "help",
    title: "문제 해결",
    summary: "버튼이 꺼져 있거나 거절 안내가 나올 때 이유와 해결 방법.",
    sections: [
      {
        id: "help-cases",
        title: "막혔을 때 해결 방법",
        blocks: [
          {
            kind: "cards",
            items: [
              {
                title: "체크인 버튼이 꺼져 있어요 — '객실이 준비 완료가 되어야 체크인할 수 있습니다'",
                fields: [
                  { label: "이유", text: "정상입니다. 청소와 검사가 끝난 객실만 손님을 받습니다." },
                  { label: "해결", text: "안내에 적힌 남은 일을 진행하세요(빠른 시작의 흐름 참고). 데모에서는 101호가 처음부터 [[stage:READY]]입니다." },
                ],
              },
              {
                title: "체크인 버튼이 꺼져 있어요 — '판매 중단된 객실이라 체크인할 수 없습니다'",
                fields: [
                  { label: "이유", text: "판매 중단된 객실은 새 손님을 받지 않습니다." },
                  { label: "해결", text: `${ROLE.M}가 객실 상세에서 [[btn:판매 재개]] (사유 입력). 판매 재개는 판매 여부만 바꾸고, 객실을 준비 완료로 만들지는 않습니다.` },
                ],
              },
              {
                title: "체크아웃이 안 돼요 — '시작된 투숙 중 청소가 있어 체크아웃할 수 없습니다'",
                fields: [
                  { label: "이유", text: "청소하는 중에 방을 비우면 작업 기록이 어긋나서 막습니다." },
                  { label: "해결", text: `담당 ${ROLE.H}가 청소를 마치거나(차단이 있으면 먼저 해제), ${ROLE.M}가 그 청소를 취소한 뒤 다시 체크아웃하세요.` },
                ],
              },
              {
                title: "배정이 안 돼요 — '선택한 직원이 작업 예정 시각에 근무하지 않습니다'",
                fields: [
                  { label: "이유", text: "고른 직원이 그 시각에 근무하지 않습니다(예: HK-03)." },
                  { label: "해결", text: "근무 중인 다른 직원을 고르세요." },
                ],
              },
              {
                title: "시작·완료가 안 돼요 — '해제되지 않은 차단 사유가 있습니다'",
                fields: [
                  { label: "이유", text: "[[task:BLOCKED]] 작업은 시작하거나 완료할 수 없습니다." },
                  { label: "해결", text: "차단 사유 옆 [[btn:해제]]를 누른 뒤 다시 시도하세요." },
                ],
              },
              {
                title: "작업을 만들 수 없어요 — '이 객실에 진행 중인 작업이 이미 있습니다'",
                fields: [
                  { label: "이유", text: "한 객실에는 진행 중인 작업이 하나만 있을 수 있습니다." },
                  { label: "해결", text: "안내에 있는 '진행 중인 작업 보기'를 눌러 기존 작업부터 처리하세요." },
                ],
              },
              {
                title: "청소를 취소했더니 객실이 [[stage:NEEDS_CLEANING]]인데 작업이 없어요",
                fields: [
                  { label: "이유", text: "취소한 작업은 다시 살아나지 않습니다." },
                  { label: "해결", text: `${ROLE.M}가 객실 상세에서 [[btn:재청소 작업 만들기]] (사유 입력).` },
                ],
              },
              {
                title: "'다른 변경과 충돌했습니다'",
                fields: [
                  { label: "이유", text: "다른 사람이 같은 객실이나 작업을 먼저 바꿨습니다." },
                  { label: "해결", text: "화면이 최신 상태로 바뀌었으니 확인하고 다시 시도하세요." },
                ],
              },
              {
                title: "'서버 응답을 받지 못했습니다'",
                fields: [
                  { label: "이유", text: "네트워크 문제로 처리됐는지 알 수 없습니다." },
                  { label: "해결", text: "[[btn:같은 요청으로 다시 시도]]를 누르세요. 같은 요청은 두 번 처리되지 않습니다." },
                ],
              },
              {
                title: "'데이터 상태 확인이 필요합니다'",
                fields: [
                  { label: "이유", text: "저장된 상태끼리 서로 맞지 않습니다. 서버가 함부로 고치지 않고 멈춘 것입니다." },
                  { label: "해결", text: "화면의 추적 ID를 관리자에게 알려 주세요." },
                ],
              },
              {
                title: "버튼이 아예 안 보여요",
                fields: [
                  { label: "이유", text: "역할과 작업 상태에 따라 할 수 있는 버튼만 보입니다." },
                  { label: "해결", text: "'역할별'·'상태별' 탭에서 누가 할 수 있는 일인지 확인하고 [[btn:사용자 변경]]으로 역할을 바꾸세요." },
                ],
              },
            ],
          },
          { kind: "note", text: "모든 안내에는 '추적 ID'가 함께 나옵니다. 문제를 알릴 때 이 값을 같이 전달하면 서버 기록에서 해당 요청을 바로 찾을 수 있습니다." },
        ],
      },
    ],
  },
];

/** 모든 섹션 (탭 순서대로). */
export const GUIDE: GuideSection[] = GUIDE_TABS.flatMap((t) => t.sections);

// ------------------------------------------------------------------ 토큰

export const TOKEN_PATTERN = /\[\[(role|stage|task|grade|avail|btn):([^\]]+)\]\]/g;

/** Markdown·검색용 글자 표현. 라벨은 화면과 같은 lib/labels를 쓴다. */
export function tokenText(kind: string, value: string, labels: Record<string, Record<string, string>>): string {
  if (kind === "btn") return `[${value}]`;
  const label = labels[kind]?.[value] ?? value;
  return kind === "role" ? `**${label}**` : `\`${label}\``;
}

/** 같은 내용을 Markdown으로 만든다 (`docs/USER_GUIDE.md`). */
export function guideToMarkdown(labels: Record<string, Record<string, string>>, tabs: GuideTab[] = GUIDE_TABS): string {
  const text = (s: string) => s.replace(TOKEN_PATTERN, (_, kind: string, value: string) => tokenText(kind, value, labels));
  const cell = (s: string) => text(s).replace(/\|/g, "\\|");
  const out: string[] = [
    `# ${GUIDE_TITLE}`,
    "",
    "> 이 파일은 `apps/web/src/features/guide/content.ts`에서 생성됩니다. 앱에서는 `/guide` 페이지(탭 화면)로 볼 수 있습니다.",
    "> 직접 고치지 말고 원본을 고친 뒤 `cd apps/web && STAYOPS_WRITE_GUIDE=true pnpm test guide`를 실행하세요.",
    "",
    GUIDE_SUMMARY,
    "",
    "## 목차",
    ...tabs.flatMap((t) => [`- **${t.title}** — ${t.summary}`, ...t.sections.map((s) => `  - [${s.title}](#${s.id})`)]),
  ];
  for (const tab of tabs) {
    out.push("", `## ${tab.title}`, "", `_${tab.summary}_`);
    for (const s of tab.sections) {
      out.push("", `<a id="${s.id}"></a>`, "", `### ${s.title}`);
      for (const b of s.blocks) {
        out.push("");
        switch (b.kind) {
          case "p":
            out.push(text(b.text));
            break;
          case "h3":
            out.push(`#### ${text(b.text)}`);
            break;
          case "note":
            out.push(`> ${text(b.text)}`);
            break;
          case "list":
            out.push(...b.items.map((i) => `- ${text(i)}`));
            break;
          case "steps":
            out.push(...b.items.map((i, n) => `${n + 1}. ${text(i)}`));
            break;
          case "flow":
            out.push(b.items.map((i) => `${text(i.label)} (${i.text})`).join(" → "));
            break;
          case "cards":
            for (const card of b.items) {
              out.push(`#### ${text(card.title)}`, "");
              for (const f of card.fields) {
                if (f.steps) out.push(`- **${f.label}:**`, ...f.steps.map((step, n) => `  ${n + 1}. ${text(step)}`));
                else out.push(`- **${f.label}:** ${text(f.text ?? "")}`);
              }
              out.push("");
            }
            out.pop();
            break;
          case "table":
            out.push(`| ${b.head.map(cell).join(" | ")} |`, `|${b.head.map(() => "---").join("|")}|`, ...b.rows.map((r) => `| ${r.map(cell).join(" | ")} |`));
            break;
        }
      }
    }
  }
  return out.join("\n") + "\n";
}
