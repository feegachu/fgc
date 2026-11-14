import type { ScreenHelpStep } from '../assistant/guide/ScreenHelp'

const brief = (step: Omit<ScreenHelpStep, 'details' | 'concise'>): ScreenHelpStep => ({
  presentation: {}, ...step, concise: true, details: [],
})

/** Seven short explanations, grouping related cards and the four branch metrics. */
export const dashboardWalkthrough: readonly ScreenHelpStep[] = [
  brief({
    targetId: 'components.month-selector.button', attribute: 'data-guide-feature', scope: 'shell',
    group: '조회 기준', title: '기준 정산월',
    instruction: '선택한 월의 수수료 및 검증 결과입니다.',
    interaction: '원장 및 미처리 예외는 전체 기간입니다.',
  }),
  brief({
    targetId: 'features.dashboard.dashboard-page.section', attribute: 'data-guide-feature',
    group: '핵심 지표', title: 'KPI 카드',
    instruction: '검증 결과와 미처리 건수를 확인합니다.',
    interaction: '카드를 누르면 상세 결과로 이동합니다.',
  }),
  brief({
    targetId: 'features.dashboard.monthly-commissions.section', attribute: 'data-guide-feature',
    presentation: { side: 'right' },
    group: '수수료 흐름', title: '월별 수수료',
    instruction: '확정된 GA 수령액 및 설계사 지급액입니다.',
    interaction: '전월 증감과 월별 막대를 비교하세요.',
  }),
  brief({
    targetId: 'dashboard.recent-activity',
    presentation: { side: 'either' },
    group: '최근 업무', title: '최근 예외와 통합검증',
    instruction: '예외 처리 상태와 검증 진행을 봅니다.',
    interaction: '각 항목을 누르면 상세로 이동합니다.',
  }),
  brief({
    targetId: 'features.dashboard.dashboard-page.composition', attribute: 'data-guide-feature',
    presentation: { side: 'right' },
    group: '검증 결과 비교', title: '검증 항목별 건수 비중',
    instruction: '항목별 건수를 비교한 비율입니다.',
    interaction: '전체 계약의 위반률은 아닙니다.',
  }),
  brief({
    targetId: 'features.dashboard.agent-status-panel.section', attribute: 'data-guide-feature',
    presentation: { side: 'either' },
    group: '지점별 비교', title: '지점별 현황',
    instruction: '수수료, 설계사, 계약 및 예외 현황입니다.',
    interaction: '아이콘을 고르고 막대를 눌러보세요.',
  }),
  brief({
    targetId: 'dashboard.support',
    presentation: { side: 'either' },
    group: '업무 이어가기', title: '업무 가이드와 보험GA협회',
    instruction: '업무 가이드로 정산을 따라갑니다.',
    interaction: '협회에서 업계 및 제도 소식을 봅니다.',
  }),
]
