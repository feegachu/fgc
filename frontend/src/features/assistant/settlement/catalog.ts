import type { GuideDefinition } from '../guide/types'
import { STEPS } from './state'
import type { Selection, SettlementStep } from './state'
export const titles = [
  '계약 등록',
  '예상 스케줄 확인',
  '지급 초안 저장',
  '사전검증',
  '지급 확정',
  '월 통합검증',
  '대사 확인',
  '예외 처리',
  '최종 확정',
]
export const FLOW_VERSION = '3.0.1'
export function destinations(selection: Selection) {
  const query = (path: string, extra: Record<string, string> = {}) =>
    `${path}?${new URLSearchParams({ month: selection.month, ...extra })}`
  return {
    'contract.create': { screen: 'CONT-W03', target: 'contract.form', path: query('/contracts/new') },
    'schedule.review': selection.scheduleId
      ? { screen: 'SCHE-W02', target: 'schedule.lines', path: query(`/schedules/${selection.scheduleId}`) }
      : {
          screen: 'CONT-W02',
          target: 'contract.schedules',
          path: query(`/contracts/${selection.contractId}`, { tab: 'schedules' }),
        },
    'payment.draft': {
      screen: 'TRAN-W02',
      target: 'payment.form',
      path: query('/transactions/new', selection.paymentId ? { id: String(selection.paymentId) } : {}),
    },
    'payment.precheck': {
      screen: 'TRAN-W02',
      target: 'payment.precheck',
      path: query('/transactions/new', { id: String(selection.paymentId) }),
    },
    'payment.confirm': {
      screen: 'TRAN-W02',
      target: 'payment.confirm',
      path: query('/transactions/new', { id: String(selection.paymentId) }),
    },
    'validation.execute': selection.validationRunId
      ? {
          screen: 'VRUN-W02',
          target: 'validation.progress',
          path: query(`/validation-runs/${selection.validationRunId}`),
        }
      : { screen: 'VRUN-W01', target: 'validation.create', path: query('/validation-runs') },
    'reconciliation.review': {
      screen: 'RECO-W01',
      target: selection.reconciliationRunId ? 'reconciliation.results' : 'reconciliation.history',
      path: query('/reconciliations', {
        stage: selection.stage,
        ...(selection.reconciliationRunId
          ? { runId: String(selection.reconciliationRunId), runMonth: selection.month }
          : {}),
      }),
    },
    'exception.resolve': {
      screen: 'EXCP-W01',
      target: 'exception.action',
      path: query('/exceptions', {
        validationMonth: `${selection.month}-01`,
        status: 'OPEN',
        ...(selection.exceptionId ? { selected: String(selection.exceptionId) } : {}),
      }),
    },
    'validation.finalize': {
      screen: 'VRUN-W02',
      target: 'validation.finalize',
      path: query(`/validation-runs/${selection.validationRunId}`),
    },
  } satisfies Record<SettlementStep, { screen: string; target: string; path: string }>
}
export function settlementCatalog(selection: Selection): GuideDefinition {
  const routes = destinations(selection)
  return {
    workflowId: 'settlement.end_to_end',
    guideVersion: FLOW_VERSION,
    title: '전체 업무 가이드',
    keywords: ['계약', '정산', '전체 흐름'],
    exampleQuestions: ['계약부터 최종 확정까지 안내해 주세요.'],
    roles: ['SYSTEM_ADMIN', 'GA_ADMIN', 'SETTLEMENT', 'COMPLIANCE'],
    requiredEntities: [],
    steps: STEPS.map((stepId, index) => ({
      stepId,
      screenId: routes[stepId].screen,
      targetId: routes[stepId].target,
      instruction: titles[index],
      entryConditionId: 'AVAILABLE',
      completionConditionId: stepId === 'validation.finalize' ? 'FINALIZED' : 'NOT_AUTOMATIC',
      next: [],
    })),
  }
}
