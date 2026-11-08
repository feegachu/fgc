import type { WorkflowDestination } from './catalog'
import { contractPaymentHelp } from '../../transaction/contract-payment-help'
import type { SettlementStep } from './state'
import type { ScreenHelpStep } from '../guide/ScreenHelp'
import { getScreenWalkthrough, guideSelector } from '../guide/page-guides'
import { useSidebarStore } from '../../../stores/sidebar'

export interface WorkflowExplanation extends Partial<Pick<ScreenHelpStep, 'follow' | 'presentation' | 'openControl' | 'advanceOnSelection' | 'advanceOnEvent' | 'requireAction' | 'onEnter' | 'interaction' | 'concise' | 'liveDescriptionSelector'>> {
  id: string
  selector: string
  title: string
  instruction: string
  input?: boolean
  requiresSelectedTab?: boolean
  workflowInstruction?: string
  nextLabel?: string
  scope?: 'shell'
  destination?: WorkflowDestination
  firstMatch?: boolean
}
const target = (id: string) => `[data-guide-target="${id}"]`
const step = (id: string, selector: string, title: string, instruction: string, input = false): WorkflowExplanation =>
  ({ id, selector, title, instruction, input })
const review = (id: string, title: string, instruction: string) => step(id, target(id), title, instruction)

/** Explanations never execute a business operation or assert server completion. */
function workflowInstructions(stage: SettlementStep, path: string, selectedException: boolean, correctionPending = false): WorkflowExplanation[] {
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
        step('schedule.versions', '[aria-labelledby="schedule-versions-title"]', '사용 중인 버전을 확인하세요', '버전 이력과 사용 여부를 확인하세요. 아래 확인 버튼은 최신 상태를 조회하며 스케줄을 확정하지 않습니다.'),
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
      review('reconciliation.results', '일치·불일치를 확인하세요', '결과 건수와 불일치 유형을 확인하세요. 아래 버튼은 결과에 따라 예외 처리 또는 최종 확정으로 안내합니다.'),
    ]
    case 'exception.resolve':
      if (correctionPending) return [step('exception.replacement', 'h1', '정정 후 지급을 연결하세요', '취소된 지급을 재사용하지 않습니다. 정정 후 확정된 지급 번호를 아래에 입력하고 새 월 검증과 새 대사 결과를 연결하세요.')]
      if (path !== '/exceptions') return [step('exception.source', 'h1', '원천 화면에서 직접 정정하세요', '기존 취소·정정 절차로 원천을 수정하세요. 정정 지급을 확정한 뒤 아래 정정 지급 연결과 새 월 검증 안내를 사용하세요.')]
      if (!selectedException) return [
        review('exception.filters', '처리할 예외를 찾으세요', '현재 계약·검증월·미해결 상태로 조회하고 예외 한 건을 선택하세요.'),
        review('exception.action', '선택한 예외를 확인하세요', '예외를 선택하면 근거와 허용 조치에 대한 세부 안내가 이어집니다.'),
      ]
      return [
        review('exception.detail', '검출 근거를 확인하세요', '어느 실행에서 어떤 불일치가 생겼는지 확인하세요. 원본 링크를 통해 원천 내용을 대조하세요.'),
        step('exception.type', `${target('exception.actionInput')} select`, '허용 조치를 선택하세요', '선택할 수 있는 검토, 정정, 해결 조치 중 맞는 항목을 고르세요.', true),
        step('exception.reason', `${target('exception.actionInput')} textarea`, '처리 사유를 입력하세요', '판단 근거와 실제 처리 내용을 입력하세요. 해결 표시만으로 원래 건이 정정되지는 않습니다.', true),
        step('exception.evidence', `${target('exception.actionInput')} input`, '증빙을 확인하세요', '필요한 문서번호나 링크를 입력하세요.'),
        review('exception.actionSave', '처리 내용을 직접 저장하세요', '실제 처리 저장을 누르세요. 처리 성공과 원래 건 정정·재검증 완료는 서로 다릅니다.'),
        review('exception.action', '원래 건 정정과 재검증을 연결하세요', '원래 건 정정 후 확정 지급 번호를 연결하고 새 월 검증·재대사 결과를 선택하세요. 아래 연결 버튼을 차례로 사용하세요.'),
      ]
    case 'validation.finalize': return [
      step('finalize.header', '[aria-labelledby="vrun-header-title"]', '최종 확정 대상을 확인하세요', '검증월과 실행 회차가 현재 업무의 결과인지 확인하세요.'),
      ...[
        ['계산 완료 상태', '검증 계산이 끝났는지 확인하세요. 실행 중 또는 실패는 확정할 수 없습니다.'],
        ['차변·대변 일치', '차대가 맞지 않는 분개가 0건인지 확인하세요. 원장 정정이 필요하면 먼저 처리합니다.'],
        ['긴급 미처리 예외', '긴급 미처리 예외가 0건인지 확인하세요. 미충족 바로가기에서 근거를 확인합니다.'],
        ['정책 누락·중복', '정책 없음과 중복이 0건인지 확인하세요. 정책을 정정했다면 재검증이 필요합니다.'],
        ['귀속합계', '귀속합계 오류가 0건인지 확인하세요. 지급 원천과 계약별 귀속을 대조합니다.'],
        ['상세·요약 합계', '계약별 상세 합계와 실행 요약 합계가 일치하는지 확인하세요.'],
        ['최신 실행', '같은 검증월의 가장 최근 실행인지 확인하세요. 오래된 실행을 확정하지 않습니다.'],
        ['중복 확정 방지', '같은 검증월에 이미 확정된 실행이 없는지 확인하세요.'],
      ].map(([title, instruction], index) => step(`finalize.condition.${index + 1}`, `[data-guide-condition="${index + 1}"]`, `${index + 1}/8 · ${title}`, instruction)),
      review('validation.finalize', '확정 권한과 잠금 안내를 확인하세요', 'GA관리자 또는 시스템관리자의 작업입니다. 확정 후 결과가 잠기며 실제 송금이나 회계 마감과는 다릅니다.'),
      review('validation.finalize', '최종 확정을 실행하세요', '화면의 최종 확정 버튼과 확인창을 직접 조작하세요. 상태가 확정(잠김)일 때만 안내가 끝납니다.'),
    ]
  }
}

/** The pipeline calls each page's guide, adding only workflow-specific checks and handoffs. */
export function workflowWalkthrough(stage: SettlementStep, path: string, selectedException: boolean, correctionPending = false, reuseContractDetail = true, batchPayment = false): WorkflowExplanation[] {
  if (reuseContractDetail && stage === 'reconciliation.review') {
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
      interaction: '수정 후 등록 화면에서 안내를 이어갑니다.',
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
  const instructions = workflowInstructions(stage, path, selectedException, correctionPending)
  if (stage === 'contract.create') return instructions
  const screenId = path.startsWith('/contracts/') ? 'CONT-W02'
    : path.startsWith('/schedules/') ? 'SCHE-W02'
      : path === '/transactions/new' ? 'TRAN-W02' : path === '/transactions' ? 'TRAN-W01'
        : path === '/validation-runs' ? 'VRUN-W01' : path.startsWith('/validation-runs/') ? 'VRUN-W02'
          : path === '/reconciliations' ? 'RECO-W01' : path === '/exceptions' ? 'EXCP-W01' : ''
  const page = getScreenWalkthrough(screenId)
  const explanations = instructions.map(instruction => {
    if (reuseContractDetail && stage === 'schedule.review' && instruction.id === 'schedule.versions') {
      return { ...instruction, selector: '#schedule-versions-tab', title: '버전 비교', concise: true,
        instruction: '버전비교 탭에서 생성 이력과 사용 중인 버전을 확인하세요.',
        interaction: '확인한 뒤 수수료 지급으로 이어갑니다.', requiresSelectedTab: true }
    }
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
    presentation: { side: 'right' },
    onEnter: () => {
      useSidebarStore.getState().setCollapsed(false, false)
      useSidebarStore.getState().setSection(screen === 'vrun-w01' ? 'compliance' : screen === 'reco-w01' ? 'ledger' : 'contracts', true)
    },
  }
}
