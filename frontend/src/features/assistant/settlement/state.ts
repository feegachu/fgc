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
  'exception.resolve',
  'validation.finalize',
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
  complete?: boolean
}
export function decide(selection: Selection, evidence: Evidence, role: string): Decision {
  const at = (step: SettlementStep, reason: string): Decision => ({ step, reason })
  if (!evidence.contract) return at('contract.create', '기존 계약을 선택하거나 실제 계약 등록을 완료하세요.')
  if (evidence.contract.contractId !== selection.contractId) throw new Error('선택한 계약과 조회 결과가 다릅니다.')
  const header = evidence.schedule?.header
  if (!header || !evidence.schedule?.lines?.length)
    return at('schedule.review', '선택한 계약·지급단계의 실제 스케줄을 조회하세요.')
  if (
    (selection.scheduleId != null && header.scheduleHeaderId !== selection.scheduleId) ||
    header.paymentStage !== selection.stage ||
    header.contractNo !== evidence.contract.contractNo
  )
    throw new Error('스케줄의 계약 또는 지급단계가 다릅니다.')
  if (header.scheduleRegime !== 'CURRENT' || header.schedulePurpose !== 'OPERATIONAL')
    return at('schedule.review', '4년·7년 예정 스케줄은 조회까지만 안내합니다. 현행 지급 흐름으로 이어갈 수 없습니다.')
  if (header.activeYn !== true || ['CANCELLED', 'HOLD'].includes(header.status ?? ''))
    return at(
      'schedule.review',
      '현재 사용 가능한 스케줄을 선택하세요. 중단·지난 버전은 지급 흐름에 연결하지 않습니다.',
    )
  if (!evidence.scheduleAcknowledged)
    return at('schedule.review', '회차와 지급단계·금액을 확인한 뒤 확인 버튼을 누르세요.')
  const payment = evidence.payment
  if (!payment) return at('payment.draft', '직접 지급 내용을 입력하고 초안을 저장하세요.')
  if (
    payment.commissionTransactionId !== selection.paymentId ||
    payment.settlementMonth?.slice(0, 7) !== selection.month ||
    payment.paymentStage !== selection.stage ||
    !payment.attributions?.length ||
    payment.attributions.some((row) => row.contractId !== selection.contractId)
  )
    throw new Error('지급의 계약·기준월·지급단계가 현재 안내 대상과 다릅니다.')
  if (payment.status !== 'CONFIRMED') {
    if (payment.status === 'CANCELLED')
      return at(
        'exception.resolve',
        '기존 지급이 취소되었습니다. 원천 정정 결과의 확정된 지급 번호를 선택한 뒤 새 대사 결과를 확인하세요.',
      )
    if (payment.status !== 'DRAFT') throw new Error('현재 지급 상태는 이 흐름에서 처리할 수 없습니다.')
    if (evidence.paymentGuide && !evidence.paymentGuide.saved)
      return at('payment.draft', '변경된 입력을 실제 저장한 뒤 이어가세요.')
    if (!evidence.paymentGuide?.prechecked)
      return at('payment.precheck', '현재 저장본의 사전검증을 직접 실행하세요. 이전 세션의 검증은 재사용하지 않습니다.')
    return at('payment.confirm', '실제 확정 버튼으로 지급을 확정하세요.')
  }
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
      '현재 계약이 이 실행의 선정 대상인지 서버 결과에서 확인하지 못했습니다. 대상 범위를 확인하세요.',
    )
  if (run.status === 'FINALIZED')
    return { ...at('validation.finalize', '서버에서 최종 확정된 결과를 확인했습니다.'), complete: true }
  if (run.status !== 'COMPLETED')
    return at(
      'validation.execute',
      run.status === 'FAILED'
        ? '월 통합검증이 실패했습니다. 실패 원인과 기존 재시작 기능을 확인하세요.'
        : '202 수락은 완료가 아닙니다. 서버 계산 완료 후 다시 확인하세요.',
    )
  const recon = evidence.reconciliation
  if (!recon)
    return at(
      'reconciliation.review',
      '이 검증 실행의 기존 대사 결과를 선택하세요. 필요한 실행은 업무 화면에서 직접 수행하세요.',
    )
  if (
    !evidence.contract.insurerId ||
    recon.insurerId !== evidence.contract.insurerId ||
    recon.validationRunId !== selection.validationRunId ||
    recon.paymentStage !== selection.stage ||
    recon.settlementMonth?.slice(0, 7) !== selection.month ||
    recon.reconciliationRunId !== selection.reconciliationRunId
  )
    throw new Error('대사 결과의 검증 실행·기준월·지급단계가 다릅니다.')
  if (!['COMPLETED', 'FINALIZED'].includes(recon.status ?? ''))
    return at('reconciliation.review', '대사 완료를 기다리거나 실패 원인을 확인하세요.')
  const summary = evidence.results?.summary
  if (
    !summary ||
    summary.resultCount == null ||
    summary.resultCount <= 0 ||
    summary.exceptionCount == null ||
    summary.matchedCount == null ||
    evidence.openExceptions == null
  )
    return at(
      'reconciliation.review',
      '대사 요약과 미해결 예외를 다시 조회하세요. 자료 누락은 0건으로 처리하지 않습니다.',
    )
  if (summary.exceptionCount > 0 || summary.matchedCount !== summary.resultCount || evidence.openExceptions > 0)
    return at(
      'exception.resolve',
      '불일치·미해결 예외가 있습니다. 원천을 정정하고 필요한 재검증·재대사를 직접 수행한 뒤 새 결과를 선택하세요.',
    )
  if (!['GA_ADMIN', 'SYSTEM_ADMIN'].includes(role))
    return at(
      'validation.finalize',
      '최종 확정은 GA_ADMIN 또는 SYSTEM_ADMIN에게 인계하세요. 역할을 자동 전환하지 않습니다.',
    )
  const checklist = evidence.checklist
  if (
    !checklist ||
    checklist.validationRunId !== selection.validationRunId ||
    checklist.conditions?.length !== 8 ||
    !checklist.passed ||
    checklist.conditions.some((condition) => condition.passed !== true)
  )
    return at('validation.finalize', '최종 확정의 8개 서버 조건을 확인하고 차단 사유를 처리하세요.')
  return at('validation.finalize', '8개 조건을 통과했습니다. 기존 최종 확정 버튼으로 직접 확정하세요.')
}
