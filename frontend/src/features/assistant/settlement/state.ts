import type { ContractDetail } from '../../contract/api'
import type { ScheduleDetail } from '../../schedule/api'
import type { Payment } from '../../transaction/form-api'
import type { ValidationRunDetail, FinalizeChecklist } from '../../validation/api'
import type { Run, Results } from '../../reconciliation/api'
import type { PaymentGuideState } from '../../transaction/payment-guide'

export const STEPS = [
  'contract.create',
  'schedule.review',
  'payment.draft',
  'payment.precheck',
  'payment.confirm',
  'validation.execute',
  'reconciliation.review',
  'ledger.review',
  'exception.resolve',
] as const
export type SettlementStep = (typeof STEPS)[number]
export interface Selection {
  month: string
  stage: 'GA_TO_FC' | 'INSURER_TO_GA'
  contractId?: number
  scheduleId?: number
  paymentId?: number
  validationRunId?: number
  reconciliationRunId?: number
  exceptionId?: number
  invalidatedValidationRunIds?: number[]
}
/** A corrected payment needs a newly selected validation; never reuse the previous run. */
export function replacePayment(selection: Selection, paymentId: number): Selection {
  return {
    ...selection,
    paymentId,
    invalidatedValidationRunIds: [
      ...new Set([
        ...(selection.invalidatedValidationRunIds ?? []),
        ...(selection.validationRunId ? [selection.validationRunId] : []),
      ]),
    ],
    validationRunId: undefined,
    reconciliationRunId: undefined,
    exceptionId: undefined,
  }
}
export function handoffFor(step: SettlementStep, role: string, canProcess: boolean): string | undefined {
  if (
    !canProcess &&
    ['contract.create', 'payment.draft', 'payment.precheck', 'payment.confirm', 'validation.execute'].includes(step)
  )
    return '현재 역할은 조회 안내만 가능합니다. 생성과 실행은 정산담당자나 시스템관리자에게 맡기세요.'
  if (step === 'exception.resolve' && !['SETTLEMENT', 'GA_ADMIN', 'SYSTEM_ADMIN'].includes(role))
    return '예외 조치와 원래 건 정정은 정산담당자, GA관리자, 시스템관리자에게 맡기세요. 현재 역할은 조회 안내만 가능합니다.'
}
export interface Evidence {
  contract?: ContractDetail
  schedule?: ScheduleDetail
  payment?: Payment
  paymentGuide?: PaymentGuideState
  validation?: ValidationRunDetail
  reconciliation?: Run
  results?: Results
  openExceptions?: number
  checklist?: FinalizeChecklist
  scheduleAcknowledged: boolean
}
export interface Decision {
  step: SettlementStep
  reason: string
  correctionPending?: boolean
}
export function decide(selection: Selection, evidence: Evidence, role: string): Decision {
  const at = (step: SettlementStep, reason: string): Decision => ({ step, reason })
  if (!evidence.contract) return at('contract.create', '기존 계약을 선택하거나 실제 계약 등록을 완료하세요.')
  if (evidence.contract.contractId !== selection.contractId) throw new Error('선택한 계약과 조회 결과가 다릅니다.')
  const header = evidence.schedule?.header
  if (!header || !evidence.schedule?.lines?.length)
    return at('schedule.review', '선택한 계약과 지급단계의 실제 스케줄을 조회하세요.')
  if (
    (selection.scheduleId != null && header.scheduleHeaderId !== selection.scheduleId) ||
    header.paymentStage !== selection.stage ||
    header.contractNo !== evidence.contract.contractNo
  )
    throw new Error('스케줄의 계약 또는 지급단계가 다릅니다.')
  if (header.scheduleRegime !== 'CURRENT' || header.schedulePurpose !== 'OPERATIONAL')
    return at('schedule.review', '4년/7년 예정 스케줄은 조회까지만 안내합니다. 현행 지급 흐름으로 이어갈 수 없습니다.')
  if (header.activeYn !== true || ['CANCELLED', 'HOLD'].includes(header.status ?? ''))
    return at(
      'schedule.review',
      '현재 사용 가능한 스케줄을 선택하세요. 중단되었거나 지난 버전은 지급 흐름에 연결하지 않습니다.',
    )
  if (!evidence.scheduleAcknowledged)
    return at('schedule.review', '회차, 지급단계, 금액을 확인한 뒤 확인 버튼을 누르세요.')
  const payment = evidence.payment
  if (!payment) return at('payment.draft', '직접 지급 내용을 입력하고 초안을 저장하세요.')
  if (
    payment.commissionTransactionId !== selection.paymentId ||
    payment.settlementMonth?.slice(0, 7) !== selection.month ||
    payment.paymentStage !== selection.stage ||
    !payment.attributions?.length ||
    payment.attributions.some((row) => row.contractId !== selection.contractId)
  )
    throw new Error('지급의 계약, 기준월, 지급단계가 현재 안내 대상과 다릅니다.')
  if (payment.status !== 'CONFIRMED') {
    if (payment.status === 'CANCELLED')
      return { ...at(
        'exception.resolve',
        '기존 지급이 취소되었습니다. 예외함의 참조에서 원인을 확인하세요. 필요한 처리와 정정은 안내 종료 후 담당자가 직접 진행합니다.',
      ), correctionPending: true }
    if (payment.status !== 'DRAFT') throw new Error('현재 지급 상태는 이 흐름에서 처리할 수 없습니다.')
    if (evidence.paymentGuide && !evidence.paymentGuide.saved)
      return at('payment.draft', '변경된 입력을 실제 저장한 뒤 이어가세요.')
    if (!evidence.paymentGuide?.prechecked)
      return at('payment.precheck', '현재 저장한 내용의 사전검증을 실행하세요. 이전에 한 검증은 다시 쓰지 않습니다.')
    return at('payment.confirm', '확정 버튼으로 지급을 확정하세요.')
  }
  if (selection.validationRunId && selection.invalidatedValidationRunIds?.includes(selection.validationRunId))
    throw new Error('정정 전 검증 실행은 재사용할 수 없습니다. 새 월 검증을 생성하고 실행한 뒤 선택하세요.')
  const run = evidence.validation?.header
  if (!run) return at('validation.execute', '기준월의 월 통합검증을 선택하거나 생성하고 실행하세요.')
  if (
    run.validationRunId !== selection.validationRunId ||
    run.validationMonth?.slice(0, 7) !== selection.month ||
    run.runType !== 'MONTHLY'
  )
    throw new Error('기준월의 월간 검증 실행만 연결할 수 있습니다.')
  if (
    ['COMPLETED', 'FINALIZED'].includes(run.status ?? '') &&
    !evidence.validation?.targets?.some(
      (target) => target.contractNo === evidence.contract?.contractNo && target.selectionStatus === 'SELECTED',
    )
  )
    return at(
      'validation.execute',
      '현재 계약이 이 실행의 대상인지 확인하지 못했습니다. 대상 범위를 확인하세요.',
    )
  // Session progress is intentionally ephemeral; server timestamps also protect resumed corrections.
  const paymentChangedAt = Date.parse(payment.updatedAt ?? payment.createdAt ?? '')
  const validationStartedAt = Date.parse(run.startedAt ?? '')
  if (
    ['COMPLETED', 'FINALIZED'].includes(run.status ?? '') &&
    (!Number.isFinite(paymentChangedAt) || !Number.isFinite(validationStartedAt))
  )
    throw new Error('지급 변경 또는 검증 시작 시각을 확인하지 못했습니다. 최신 결과를 다시 조회하세요.')
  if (
    Number.isFinite(paymentChangedAt) &&
    Number.isFinite(validationStartedAt) &&
    paymentChangedAt > validationStartedAt
  )
    throw new Error('지급 변경 이전에 시작한 검증은 재사용할 수 없습니다. 새 월 검증을 생성·실행한 뒤 선택하세요.')
  if (!['COMPLETED', 'FINALIZED'].includes(run.status ?? ''))
    return at(
      'validation.execute',
      run.status === 'FAILED'
        ? '월 통합검증이 실패했습니다. 실패 원인과 재시작 기능을 확인하세요.'
        : '월 통합검증 계산이 끝나지 않았습니다. 실행 전이면 실행을 누르고, 계산이 끝나면 다시 확인하세요.',
    )
  const recon = evidence.reconciliation
  if (!recon)
    return at(
      'reconciliation.review',
      '이 검증 실행의 기존 대사 결과를 선택하세요. 필요한 실행은 업무 화면에서 수행하세요.',
    )
  if (
    !evidence.contract.insurerId ||
    recon.insurerId !== evidence.contract.insurerId ||
    recon.validationRunId !== selection.validationRunId ||
    recon.paymentStage !== selection.stage ||
    recon.settlementMonth?.slice(0, 7) !== selection.month ||
    recon.reconciliationRunId !== selection.reconciliationRunId
  )
    throw new Error('대사 결과의 검증 실행, 기준월, 지급단계가 다릅니다.')
  if (!['COMPLETED', 'FINALIZED'].includes(recon.status ?? ''))
    return at('reconciliation.review', '대사 완료를 기다리거나 실패 원인을 확인하세요.')
  const summary = evidence.results?.summary
  if (
    !summary ||
    summary.resultCount == null ||
    summary.resultCount <= 0 ||
    summary.exceptionCount == null ||
    summary.matchedCount == null ||
    evidence.openExceptions == null ||
    ![summary.resultCount, summary.matchedCount, summary.exceptionCount, evidence.openExceptions].every(
      (count) => Number.isSafeInteger(count) && count >= 0,
    )
  )
    return at(
      'reconciliation.review',
      '대사 요약과 미해결 예외를 다시 조회하세요. 자료 누락은 0건으로 처리하지 않습니다.',
    )
  const reason = summary.exceptionCount > 0 || summary.matchedCount !== summary.resultCount || evidence.openExceptions > 0
    ? '불일치나 미해결 예외가 있습니다. 검증원장에서 분개와 연결 근거를 확인하고 예외함 참조 안내로 이어갑니다.'
    : '대사 결과를 확인했습니다. 검증원장의 분개와 예외함 참조 안내로 이어갑니다.'
  return at('ledger.review', role === 'COMPLIANCE' ? '조회 권한으로 ' + reason : reason)
}
