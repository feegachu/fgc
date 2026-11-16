import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import { errorText, won } from '../../lib/format'
import { CapPanel, GatePanel } from '../transaction/ConfirmationPanels'
import { buildPayload, draftRows, initialForm } from '../transaction/form-model'
import type { Payment, PaymentPayload, Precheck } from '../transaction/form-api'

export function DeferredPaymentForm({ payment, date, earliestDate, excess, contractNo, pending, canProcess, actionContainer, onPreview, onSave }: {
  payment: Payment; date: string; earliestDate?: string | null; excess: number; contractNo?: string
  pending: boolean; canProcess: boolean; actionContainer: HTMLElement | null
  onPreview: (body: PaymentPayload) => Promise<Precheck>
  onSave: (body: PaymentPayload, expectedUpdatedAt?: string) => Promise<void>
}) {
  const [result, setResult] = useState<Precheck | null>(null)
  const [error, setError] = useState('')
  const currentAmount = Number(payment.amount ?? 0) - excess
  const valid = canProcess && Boolean(payment.updatedAt && earliestDate && date && date >= earliestDate)
    && excess > 0 && currentAmount >= 0 && payment.attributions?.length === 1 && payment.cashflowType === 'PAYMENT'
  useEffect(() => { setResult(null); setError('') }, [date, excess, payment])
  function body() {
    const reduced = { ...payment, amount: currentAmount, attributions: payment.attributions!.map(row => ({ ...row, amount: currentAmount })) }
    const payload = buildPayload(initialForm(payment.settlementMonth?.slice(0, 7) ?? '', reduced), draftRows(reduced), false)
    payload.scheduledPaymentDate = payment.scheduledPaymentDate ?? payload.scheduledPaymentDate
    // 현재 지급분의 귀속은 보존한다. 상단 날짜는 별도로 생성할 초과분 지급과 귀속에만 적용한다.
    payload.attributions[0].inclusionDecisionReason = payment.attributions![0].inclusionDecisionReason ?? payload.attributions[0].inclusionDecisionReason
    return payload
  }
  const actions = <>
    <Button data-guide-feature="features.exception.deferred-payment.preview" variant="secondary" disabled={!valid || pending} onClick={async () => {
      setError(''); setResult(null)
      try { setResult(await onPreview(body())) } catch (caught) { setError(errorText(caught instanceof Error ? caught : undefined, '이연 사전검증에 실패했습니다.')) }
    }}>사전검증</Button>
    <Button data-guide-feature="features.exception.deferred-payment.save" disabled={!valid || pending || result?.confirmable !== true} onClick={async () => {
      setError('')
      try { await onSave(body(), payment.updatedAt) } catch (caught) { setResult(null); setError(errorText(caught instanceof Error ? caught : undefined, '이연 내용을 저장하지 못했습니다.')) }
    }}>이연 저장</Button>
  </>
  return <>
    <div className="exception-deferred-layout">
      <section data-guide-feature="features.exception.deferred-payment.summary" className="surface exception-deferred-summary">
        <h3>지급 및 귀속 자동 설정</h3>
        <dl>
          <div><dt>현재 지급액</dt><dd>{won(currentAmount)}</dd></div>
          <div><dt>초과분 이연액</dt><dd>{won(excess)}</dd></div>
          <div><dt>수수료 항목</dt><dd>{payment.commissionItemName ?? '-'}</dd></div>
        </dl>
        <DataTable caption="이연 지급 및 귀속 내역" rows={[{ date, month: date ? date.slice(0, 7) + '-01' : '' }]} rowKey={() => 'deferred'} columns={[
          { key: 'contract', label: '계약번호', render: () => contractNo ?? '-' },
          { key: 'scheduled', label: '지급 예정일', render: row => row.date || '-' },
          { key: 'date', label: '귀속일', render: row => row.date || '-' },
          { key: 'month', label: '귀속월 및 정산월', render: row => row.month || '-' },
          { key: 'amount', label: '귀속금액', align: 'number', render: () => won(excess) },
        ]} />
        <p>현재 지급액은 한도 내 금액으로 조정하고, 초과분은 선택한 예정일의 지급 초안으로 분리합니다.</p>
        {!valid && <p role="alert">계약 1년 뒤부터의 지급 예정일과 단일 귀속 지급 건을 확인하세요. 초과분을 모두 이연할 수 있는 지급 건이어야 합니다.</p>}
      </section>
      <div><CapPanel result={result} pending={pending} error={error} contracts={[]} /><GatePanel result={result} correction /></div>
    </div>
    {actionContainer ? createPortal(actions, actionContainer) : actions}
  </>
}
