import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { readPayment, type Payment } from './form-api'

export async function loadSchedulePayments(contractId: string, stage: string, month: string,
  lineIds: number[], signal?: AbortSignal): Promise<Record<number, Payment>> {
  const response = await apiClient.request<components['schemas']['ContractTransactionTabResponse']>(
    `/api/v1/contracts/${encodeURIComponent(contractId)}/transactions`, { signal })
  if (!Array.isArray(response.data?.transactions)) throw new Error('기존 지급 건을 확인하지 못했습니다. 다시 조회하세요.')
  // The demo loader also persists one payment per schedule line using its stable GA key.
  // Only exact keys qualify; suffixed split payments and unrelated manual entries stay separate.
  const keys = new Map(lineIds.flatMap(id => [
    [`SCHEDULE-LINE-${stage}-${id}`, id] as const,
    [`GA-${month.replace('-', '')}-L${id}`, id] as const,
  ]))
  const result: Record<number, Payment> = {}
  for (const row of response.data.transactions) {
    if (row.paymentStage !== stage || row.settlementMonth?.slice(0, 7) !== month || row.sourceType !== 'GA_MANUAL_PAYMENT') continue
    if (!row.commissionTransactionId) throw new Error('기존 지급 건의 번호를 확인하지 못했습니다.')
    const payment = await readPayment(String(row.commissionTransactionId), signal)
    const lineId = keys.get(payment.sourceBusinessKey ?? '')
    if (lineId == null) continue
    if (payment.contractId !== Number(contractId) || payment.paymentStage !== stage || payment.settlementMonth?.slice(0, 7) !== month)
      throw new Error('기존 지급 건의 계약·지급단계·정산월이 변경되었습니다. 목록에서 확인하세요.')
    if (result[lineId]) throw new Error('동일 스케줄의 지급 건이 중복되었습니다. 목록에서 확인하세요.')
    if (!['DRAFT', 'CONFIRMED'].includes(payment.status ?? ''))
      throw new Error('취소된 지급 건은 같은 스케줄로 재등록할 수 없습니다. 지급 목록에서 확인하세요.')
    if (!payment.updatedAt || payment.attributions?.length !== 1 || payment.attributions[0].contractId !== Number(contractId))
      throw new Error('기존 지급 건의 귀속 정보를 지급 목록에서 확인하세요.')
    result[lineId] = payment
  }
  return result
}
