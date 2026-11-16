import type { Detail } from './api'
import { amountText } from './amounts'

export function reconciliationExplanation(detail: Detail) {
  const invalid = detail.resultType === 'INVALID_CONTRACT_PAYMENT' || detail.primaryReasonCode === 'INVALID_CONTRACT_PAYMENT'
    || detail.secondaryReasons?.some(reason => reason.code === 'INVALID_CONTRACT_PAYMENT')
  // 계약 상태와 금액 카드로 표시하는 불일치는 같은 설명 문장을 반복하지 않는다.
  if ((invalid && detail.resultType !== 'MATCHED') || detail.resultType === 'AMOUNT_DIFFERENCE') return ''
  if (detail.resultType === 'MATCHED') {
    const snapshot = detail.detailSnapshot
    if (snapshot && typeof snapshot === 'object' && 'recoveryDeductionAmount' in snapshot && Number(snapshot.recoveryDeductionAmount) > 0) {
      const original = 'originalActualAmount' in snapshot ? snapshot.originalActualAmount : null
      return `원지급 ${amountText(typeof original === 'number' || typeof original === 'string' ? original : null)}에서 환수 차감 ${amountText(String(snapshot.recoveryDeductionAmount))}이 반영되었습니다.\n차감 후 지급액 ${amountText(detail.actualTotalAmount)}과 예상 지급액 ${amountText(detail.expectedTotalAmount)}이 일치합니다.`
    }
    return '예상 지급액과 실제 지급 자료가 일치합니다.'
  }
  if (detail.resultType === 'EXPECTED_MISSING') return '비교할 예상 지급 내역이 없어 예상 지급액이 0원으로 집계되었습니다.\n계약 상태뿐 아니라 지급 스케줄과 적용 정책도 확인해야 합니다.'
  if (detail.resultType === 'ACTUAL_MISSING') return '예상 지급 내역은 있지만 이에 대응하는 실제 지급 자료가 없습니다.'
  if (detail.resultType === 'DUPLICATE') return '같은 계약·수수료 항목·회차에 지급 자료가 중복으로 기록되어 있습니다.'
  if (detail.resultType === 'AGENT_MISMATCH') return '예상 지급 대상 설계사와 실제 지급 자료의 설계사가 다릅니다.'
  if (detail.resultType === 'INSTALLMENT_MISMATCH') return '예상 지급 회차와 실제 지급 자료의 회차가 다릅니다.'
  return `${detail.primaryReason?.label || detail.resultTypeLabel || '지급 자료 확인 필요'} 사유로 추가 확인이 필요합니다.`
}
