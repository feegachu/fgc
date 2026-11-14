import type { ScreenHelpStep } from '../assistant/guide/ScreenHelp'

export const contractPaymentHelp: ScreenHelpStep[] = ([
  {
    targetId: 'payment.batch.contract',
    title: '지급할 계약 선택',
    instruction: '목록에서 지급할 계약을 선택하세요.',
    interaction: '기준 정산월까지 체결된 계약만 표시됩니다.',
    openControl: true,
    advanceOnSelection: true,
    presentation: { side: 'right', includeLabel: true },
  },
  {
    targetId: 'payment.batch.lines',
    title: '설계사별 귀속행',
    instruction: '예상 스케줄과 같은 설계사의 귀속금액을 비교하세요.',
    interaction: '두 금액을 비교하고 필요한 귀속금액을 수정하세요.',
    presentation: { side: 'below', rowLimit: 3 },
    onEnter: () => {
      const root = document.querySelector('.contract-payment-editor')
      root?.dispatchEvent(new Event('fgc-payment-compare-start'))
      return () => { root?.dispatchEvent(new Event('fgc-payment-compare-stop')) }
    },
  },
  {
    targetId: 'payment.batch.save',
    title: '임시저장',
    requireAction: true,
    disabledReason: '귀속금액을 확인하고 임시저장을 완료해야 다음 단계로 이동할 수 있습니다.',
    advanceOnEvent: 'fgc-payment-save-complete',
    instruction: '귀속금액을 확인하고 임시저장을 누르세요.',
    interaction: '작성중으로 저장되며, 지급은 확정되지 않습니다.',
    presentation: { side: 'right', preserveCardPosition: true },
  },
  {
    targetId: 'payment.batch.precheck',
    title: '사전검증',
    requireAction: true,
    disabledReason: '사전검증을 실행하고 선택한 모든 행이 통과해야 다음 단계로 이동할 수 있습니다.',
    advanceOnEvent: 'fgc-payment-precheck-complete',
    instruction: '임시저장 후 사전검증을 누르세요.',
    interaction: '차단된 행은 오른쪽 점검표에서 원인을 확인합니다.',
    presentation: { side: 'right', preserveCardPosition: true },
  },
  {
    targetId: 'payment.precheck.result',
    title: '확정 전 6단계 점검',
    instruction: '입력, 금액, 저장 및 검증 결과를 차례로 확인하세요.',
    interaction: '확인 필요 항목을 수정하고 다시 저장 및 검증합니다.',
    presentation: { side: 'left', preserveCardPosition: true },
  },
  {
    targetId: 'payment.batch.confirm',
    title: '지급 확정',
    requireAction: true,
    disabledReason: '확인창에서 지급 확정을 완료해야 월 통합검증으로 이동할 수 있습니다.',
    advanceOnEvent: 'fgc-payment-confirm-complete',
    instruction: '검증을 통과하면 확정을 누르세요.',
    interaction: '확인창에서 대상 행과 총액을 확인한 뒤 확정합니다.',
    presentation: { side: 'right', preserveCardPosition: true },
  },
] satisfies Omit<ScreenHelpStep, 'group' | 'details'>[]).map((step, index) => ({ ...step, group: '계약별 지급 등록', details: [], concise: true,
  presentation: { ...step.presentation, fixedCard: index < 2 ? 'right' as const : 'left' as const,
    cardAlign: index < 2 ? 'top' as const : undefined, preserveCardPosition: index > 2, preventScroll: true } }))
