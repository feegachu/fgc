import type { ScreenHelpStep } from '../assistant/guide/ScreenHelp'

const brief = (step: Omit<ScreenHelpStep, 'details' | 'concise'>): ScreenHelpStep => ({
  ...step, concise: true, details: [],
})

/** One short explanation per area, with each branch metric icon highlighted separately. */
export const dashboardWalkthrough: readonly ScreenHelpStep[] = [
  brief({
    targetId: 'components.month-selector.button', attribute: 'data-guide-feature', scope: 'shell',
    group: '조회 기준', title: '기준 정산월',
    instruction: '선택한 월의 수수료·검증 결과입니다.',
    interaction: '원장·미처리 예외는 전체 기간입니다.',
  }),
  brief({
    targetId: 'features.dashboard.dashboard-page.section', attribute: 'data-guide-feature',
    group: '핵심 지표', title: 'KPI 카드',
    instruction: '검증 결과와 미처리 건수를 확인합니다.',
    interaction: '카드를 누르면 상세 결과로 이동합니다.',
  }),
  brief({
    targetId: 'features.dashboard.monthly-commissions.section', attribute: 'data-guide-feature',
    group: '수수료 흐름', title: '월별 수수료',
    instruction: '확정된 GA 수령액·설계사 지급액입니다.',
    interaction: '전월 증감과 월별 막대를 비교하세요.',
  }),
  brief({
    targetId: 'dashboard.recent-activity',
    group: '최근 업무', title: '최근 예외와 통합검증',
    instruction: '예외 처리 상태와 검증 진행을 봅니다.',
    interaction: '각 항목을 누르면 상세로 이동합니다.',
  }),
  brief({
    targetId: 'features.dashboard.dashboard-page.composition', attribute: 'data-guide-feature',
    group: '검증 결과 비교', title: '검증 항목별 건수 비중',
    instruction: '항목별 건수를 비교한 비율입니다.',
    interaction: '전체 계약의 위반률은 아닙니다.',
  }),
  brief({
    targetId: 'dashboard.chart-view.branch-commissions', attribute: 'data-guide-feature',
    group: '지점별 현황 · 1/4', title: '수수료 아이콘',
    instruction: '지점별 수령액·지급액을 비교합니다.',
    interaction: '막대를 누르면 상세 내역을 엽니다.',
  }),
  brief({
    targetId: 'dashboard.chart-view.branch-agents', attribute: 'data-guide-feature',
    group: '지점별 현황 · 2/4', title: '설계사 아이콘',
    instruction: '지점별 등록·활동 설계사 수입니다.',
    interaction: '아이콘을 눌러 인원을 비교하세요.',
  }),
  brief({
    targetId: 'dashboard.chart-view.branch-contracts', attribute: 'data-guide-feature',
    group: '지점별 현황 · 3/4', title: '계약 아이콘',
    instruction: '지점별 신규·유효 계약 수입니다.',
    interaction: '아이콘을 눌러 계약 현황을 보세요.',
  }),
  brief({
    targetId: 'dashboard.chart-view.branch-exceptions', attribute: 'data-guide-feature',
    group: '지점별 현황 · 4/4', title: '예외 아이콘',
    instruction: '지점별 미처리 예외 건수입니다.',
    interaction: '전체 기간의 현재 상태를 보여 줍니다.',
  }),
  brief({
    targetId: 'dashboard.guide',
    group: '업무 이어가기 · 1/2', title: '전체 업무 가이드',
    instruction: '계약부터 최종 확정까지 따라갑니다.',
    interaction: '시작하기를 누르면 업무 안내를 엽니다.',
  }),
  brief({
    targetId: 'features.dashboard.dashboard-page.ga-association', attribute: 'data-guide-feature',
    group: '업무 이어가기 · 2/2', title: '보험GA협회 바로가기',
    instruction: '업계 소식과 제도 안내를 확인합니다.',
    interaction: '방문하기를 누르면 새 탭으로 엽니다.',
  }),
]
