import type { WorkflowDestination } from './catalog'
import { contractPaymentHelp } from '../../transaction/contract-payment-help'
import type { SettlementStep } from './state'
import type { ScreenHelpStep } from '../guide/ScreenHelp'
import { getScreenWalkthrough, guideSelector } from '../guide/page-guides'
import { useSidebarStore } from '../../../stores/sidebar'

export interface WorkflowExplanation extends Partial<Pick<ScreenHelpStep, 'follow' | 'presentation' | 'openControl' | 'advanceOnSelection' | 'advanceOnEvent' | 'requireAction' | 'disabledReason' | 'waitForTarget' | 'onEnter' | 'interaction' | 'concise' | 'liveDescriptionSelector'>> {
  id: string
  selector: string
  title: string
  instruction: string
  input?: boolean
  requiresSelectedTab?: boolean
  workflowInstruction?: string
  nextLabel?: string
  scope?: 'shell' | 'dialog'
  destination?: WorkflowDestination
  firstMatch?: boolean
}
const target = (id: string) => `[data-guide-target="${id}"]`
const step = (id: string, selector: string, title: string, instruction: string, input = false): WorkflowExplanation =>
  ({ id, selector, title, instruction, input })
const review = (id: string, title: string, instruction: string) => step(id, target(id), title, instruction)

/** Explanations never execute a business operation or assert server completion. */
function workflowInstructions(stage: SettlementStep, path: string, correctionPending = false): WorkflowExplanation[] {
  switch (stage) {
    case 'contract.create':
      if (path !== '/contracts/new') return [step('saved', 'h1', '계약 저장 결과를 확인하세요', '저장된 계약 상세인지 확인하세요. 다음 버튼으로 서버의 계약과 스케줄을 조회합니다.')]
      return getScreenWalkthrough('CONT-W03')
        .filter(entry => !entry.targetId.endsWith('next-contract-status') && !entry.targetId.endsWith('status-event-effective-at'))
        .map(entry => ({ ...entry, id: entry.targetId, selector: guideSelector(entry),
          input: entry.openControl || entry.targetId.endsWith('contract-no'),
          workflowInstruction: entry.targetId === 'contract.save' ? '직접 저장한 뒤 서버의 계약과 스케줄을 확인하고 다음 업무로 이어갑니다.' : undefined,
        }))
    case 'schedule.review':
      if (!path.startsWith('/schedules/')) return [
        review('contract.schedules', '계약의 스케줄을 선택하세요', '지급단계와 사용 중인 버전을 확인하고 회차 상세를 여세요. 결과가 없으면 원천과 생성 상태를 확인하세요.'),
      ]
      return [
        step('schedule.header', '[aria-labelledby="schedule-header-title"]', '계약과 지급단계를 확인하세요', '현재 계약과 지급단계가 맞는지 확인하세요. 4년·7년 예정은 지급 확정 흐름과 연결하지 않습니다.'),
        review('schedule.lines', '회차와 귀속일을 확인하세요', '회차별 지급 시점과 귀속일을 확인하세요. 일부 행만 보고 전체 회차를 확인한 것으로 판단하지 마세요.'),
        review('schedule.lines', '항목과 금액을 확인하세요', '회차별 수수료 항목과 예상 금액을 근거와 비교하세요. 예상 금액은 실제 지급 확정이 아닙니다.'),
        step('schedule.current', '[aria-labelledby="schedule-header-title"]', '사용 중인 스케줄을 확인하세요', '현재 버전과 사용 여부를 확인하세요. 확인 뒤 수수료 지급으로 이어갑니다.'),
      ]
    case 'payment.draft': return [] // Dedicated input-aware walkthrough.
    case 'payment.precheck':
      if (path !== '/transactions/new') return [review('payment.results', '지급 상태를 확인하세요', '선택한 지급의 상태를 확인하고 아래 버튼으로 최신 결과를 조회하세요.')]
      return [
      review('payment.scope', '저장한 지급 대상을 확인하세요', '정산월·지급단계·수령인이 맞는지 확인하세요. 입력을 수정했다면 다시 저장해야 합니다.'),
      review('payment.attributions', '귀속과 합계를 확인하세요', '계약·귀속일·산입 여부와 합계를 확인하세요. 누락 증빙과 검토필요 항목을 처리하세요.'),
      review('payment.precheck', '사전검증을 실행하세요', '화면의 사전검증 버튼을 누르세요. 이 동작은 지급 확정이 아닙니다.'),
      review('payment.precheck.result', '사전검증 결과를 읽으세요', '한도와 차단 사유를 확인하세요. 통과 여부는 최신 결과로 다시 확인하며 차단 사유가 있으면 다음 업무로 넘어가지 않습니다.'),
    ]
    case 'payment.confirm':
      if (path !== '/transactions/new') return [review('payment.results', '확정된 지급을 확인하세요', '목록에서 지급 상태를 확인하세요. 아래 버튼으로 확정 결과를 조회한 뒤 월 검증으로 이동합니다.')]
      return [
      review('payment.precheck.result', '최신 사전검증 결과를 확인하세요', '저장본과 일치하는 최신 결과인지 확인하세요. 값이 바뀌면 재저장·재검증이 필요합니다.'),
      review('payment.attributions', '확정할 귀속을 확인하세요', '수령인과 계약별 금액을 다시 확인하세요. 확정 이후의 정정은 별도 절차가 필요합니다.'),
      review('payment.confirm', '직접 지급을 확정하세요', '확정 버튼을 누르고 확인창의 대상과 경고를 읽으세요. 확정한 뒤 아래 버튼으로 결과를 확인합니다.'),
    ]
    case 'validation.execute':
      if (path === '/validation-runs') return [
        step('validation.month', `${target('validation.create')} input[type="month"]`, '검증월을 확인하세요', '지급과 동일한 정산월을 선택하세요.', true),
        step('validation.type', `${target('validation.create')} select`, '월간 검증을 선택하세요', '실행 유형은 월간을 고르세요.', true),
        review('validation.createSubmit', '새 실행을 생성하거나 기존 실행을 선택하세요', '실행 생성을 누르세요. 같은 월의 진행 중 실행이 있으면 목록에서 해당 실행을 여세요. 생성은 계산 완료가 아닙니다.'),
      ]
      return [
        step('validation.header', '[aria-labelledby="vrun-header-title"]', '검증 실행을 확인하세요', '검증월과 실행 유형·회차를 확인하세요. 정정 이전에 시작한 실행을 재사용하면 안 됩니다.'),
        review('validation.progress', '실행 상태를 확인하세요', '생성됨이면 실행 버튼을 누르세요. 실패한 실행은 신규 실행 절차를 따릅니다. 요청 수락은 계산 완료가 아닙니다.'),
        step('validation.targets', '[aria-labelledby="vrun-target-title"]', '검증 대상 계약을 확인하세요', '현재 계약이 선택된 대상인지 확인하세요. 대상에서 빠진 실행은 다음 업무에 연결하지 않습니다.'),
        review('validation.progress', '계산 완료를 기다리세요', '10단계 진행과 오류를 확인하세요. 계산 완료 후 아래 버튼으로 최신 결과를 조회합니다.'),
      ]
    case 'reconciliation.review': return [
      step('reconciliation.month', `${target('reconciliation.filters')} input[type="month"]`, '대사 정산월을 확인하세요', '지급·월 검증과 같은 정산월인지 확인하세요.', true),
      step('reconciliation.stage', '[data-guide-feature="features.reconciliation.reconciliation-page.select"]', '대사 지급단계를 확인하세요', '현재 업무의 지급단계와 동일하게 선택하세요.', true),
      step('reconciliation.insurer', '[data-guide-feature="features.reconciliation.reconciliation-page.select-2"]', '보험회사를 확인하세요', '계약의 보험회사를 선택하세요. 전체는 실행 이력 조회용입니다.'),
      review('reconciliation.execute', '대사를 실행하거나 기존 결과를 선택하세요', '필요한 경우 실제 대사 실행을 누르세요. 원래 건을 정정했다면 새 검증과 연결된 새 대사 결과를 사용하세요.'),
      review('reconciliation.history', '실행 이력을 선택하세요', '검증 실행·기준월·지급단계가 맞는 실행의 결과를 선택하세요. 완료 전에는 기다리고 실패하면 원인을 확인하세요.'),
      review('reconciliation.results', '일치·불일치를 확인하세요', '결과 건수와 불일치 유형을 확인하세요. 결과 확인 후 검증원장과 예외함 참조 안내로 이어갑니다.'),
    ]
    case 'ledger.review': return sharedSteps('LEDG-W01')
    case 'exception.resolve': return [step('exception.source', 'h1', '예외함에서 참조를 확인하세요', correctionPending
      ? '취소된 지급의 원인과 참조를 확인하세요. 필요한 처리와 정정은 담당자가 안내 종료 후 직접 진행합니다.'
      : '예외 목록에서 행을 눌러 참조와 계산근거를 확인하는 방법까지 안내합니다.')]

  }
}

/** The pipeline calls each page's guide, adding only workflow-specific checks and handoffs. */
export function workflowWalkthrough(stage: SettlementStep, path: string, selectedException: boolean, correctionPending = false, reuseContractDetail = true, batchPayment = false): WorkflowExplanation[] {
  if (path === '/exceptions' && ['reconciliation.review', 'ledger.review', 'exception.resolve'].includes(stage)) {
    if (selectedException) return [{
      id: 'exception.reference', selector: '[data-guide-target="exception.reference"]', scope: 'dialog',
      title: '참조 버튼에서 원인 확인', concise: true,
      instruction: '참조 또는 계산근거 열기 버튼으로 원자료와 검출 이유를 확인할 수 있습니다.',
      interaction: '필요한 처리와 정정은 담당자가 직접 진행합니다. 전체 업무 가이드는 여기서 마칩니다.',
      presentation: { fixedCard: 'center', includeTarget: '[data-guide-target="exception.basis"]' },
    }]
    return sharedSteps('EXCP-W01').map(entry => entry.id === 'exception.list' ? { ...entry, requireAction: true,
      disabledReason: '예외 행을 눌러 상세 모달을 열어 주세요.' } : entry)
  }
  if (reuseContractDetail && (stage === 'reconciliation.review' || stage === 'ledger.review')) {
    if (path === '/reconciliations') return sharedSteps('RECO-W01')
    if (path === '/journals') return sharedSteps('LEDG-W01')
    if (path === '/exceptions') return sharedSteps('EXCP-W01')
  }
  if (reuseContractDetail && stage === 'contract.create' && /^\/contracts\/\d+$/.test(path)) {
    return [...getScreenWalkthrough('CONT-W02').map(entry => ({
      ...entry, id: entry.targetId, selector: guideSelector(entry),
    })), menuHandoff('contract.openSchedules', 'sche-w01', '예상 스케줄', 'schedule-list')]
  }
  if (reuseContractDetail && stage === 'schedule.review' && path === '/schedules') {
    return [...sharedSteps('SCHE-W01'), menuHandoff('schedule.openPayments', 'tran-w01', '수수료 지급', 'payment-list')]
  }
  if (reuseContractDetail && stage === 'payment.draft' && path === '/transactions') {
    return [...sharedSteps('TRAN-W01').filter(entry => !entry.id.endsWith('context-link-2')), {
      id: 'payment.openRegistration', selector: '[data-guide-feature="features.transaction.transaction-list-page.context-link-2"]',
      title: '수수료 지급 등록으로 이어가기', concise: true,
      instruction: '신규 등록을 눌러 계약별 지급 건을 작성하세요.',
      interaction: '계약별 지급 등록 화면에서 안내를 이어갑니다.',
      destination: 'payment-form', presentation: { side: 'left' },
    }]
  }
  if (batchPayment && stage === 'payment.draft' && path === '/transactions/new') {
    return [...contractPaymentHelp.map(entry => ({ ...entry, id: entry.targetId, selector: guideSelector(entry) })),
      menuHandoff('payment.openValidation', 'vrun-w01', '월 통합검증', 'validation-list')]
  }
  if (reuseContractDetail && stage === 'schedule.review' && path.startsWith('/schedules/')) {
    return [...sharedSteps('SCHE-W02'), menuHandoff('schedule.openPayments', 'tran-w01', '수수료 지급', 'payment-list')]
  }
  if (reuseContractDetail && stage === 'validation.execute' && path === '/validation-runs') {
    return [...sharedSteps('VRUN-W01'), {
      id: 'validation.month', selector: `${target('validation.create')} input[type="month"]`,
      title: '검증월 선택', concise: true, input: true, openControl: true, advanceOnSelection: true,
      instruction: '이번 지급을 검증할 정산월을 선택하세요.',
      interaction: '월을 고르면 실행 생성 안내로 이어집니다.',
      presentation: { side: 'right', includeLabel: true },
    }, {
      id: 'validation.createSubmit', selector: target('validation.createSubmit'),
      title: '실행 생성', concise: true,
      instruction: '실행 생성을 눌러 월 통합검증 실행을 만드세요.',
      interaction: '같은 월의 진행 중 실행이 있다면 목록에서 열어 주세요.',
      presentation: { side: 'right' },
    }]
  }
  if (reuseContractDetail && stage === 'validation.execute' && /^\/validation-runs\/\d+$/.test(path)) {
    return [...sharedSteps('VRUN-W02').filter(entry => entry.id !== 'validation.openReconciliation'),
      menuHandoff('validation.openReconciliation', 'reco-w01', '대사 실행/결과', 'reconciliation')]
  }
  const instructions = workflowInstructions(stage, path, correctionPending)
  if (stage === 'contract.create') return instructions
  const screenId = path.startsWith('/contracts/') ? 'CONT-W02'
    : path.startsWith('/schedules/') ? 'SCHE-W02'
      : path === '/transactions/new' ? 'TRAN-W02' : path === '/transactions' ? 'TRAN-W01'
        : path === '/validation-runs' ? 'VRUN-W01' : path.startsWith('/validation-runs/') ? 'VRUN-W02'
          : path === '/reconciliations' ? 'RECO-W01' : path === '/exceptions' ? 'EXCP-W01' : ''
  const page = getScreenWalkthrough(screenId)
  const explanations = instructions.map(instruction => {
    const shared = page.find(entry => guideSelector(entry) === instruction.selector)
    return shared ? { ...shared, ...instruction, title: shared.title, instruction: shared.instruction,
      workflowInstruction: instruction.instruction } : instruction
  })
  return explanations
}

function sharedSteps(screen: string): WorkflowExplanation[] {
  return getScreenWalkthrough(screen).map(entry => ({ ...entry, id: entry.targetId, selector: guideSelector(entry) }))
}

function menuHandoff(id: string, screen: string, label: string, destination: WorkflowDestination): WorkflowExplanation {
  return {
    id, selector: '[data-guide-feature="navigation.menu.' + screen + '"]', scope: 'shell',
    title: label + '로 이어가기', concise: true, destination,
    instruction: '왼쪽 사이드바의 ' + label + ' 메뉴를 누르세요.',
    interaction: destination === 'validation-list' ? '선택한 지급 건을 모두 확정한 뒤 월 검증을 진행합니다.' : '이동한 화면의 도움말로 안내를 이어갑니다.',
    presentation: { side: 'right', preserveCardPosition: true, ...(destination === 'validation-list' ? { cardAlign: 'top' as const } : {}) },
    onEnter: () => {
      useSidebarStore.getState().setCollapsed(false, false)
      useSidebarStore.getState().setSection(screen === 'vrun-w01' ? 'compliance' : screen === 'reco-w01' ? 'ledger' : 'contracts', true)
    },
  }
}
