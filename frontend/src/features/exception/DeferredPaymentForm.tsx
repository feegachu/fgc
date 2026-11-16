import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { StatusBadge } from '../../components/StatusBadge'
import { ResizableSplit } from '../../components/ResizableSplit'
import { errorText, won } from '../../lib/format'
import { CapPanel, GatePanel } from '../transaction/ConfirmationPanels'
import { buildPayload, draftRows, initialForm } from '../transaction/form-model'
import type { Contract, Payment, PaymentPayload, Precheck } from '../transaction/form-api'
import { AttributionEditor } from '../transaction/AttributionEditor'
import { entityLabel, useEntityChoices } from '../base/selection'
import { paymentEvidenceText } from '../../lib/screenText'
import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import '../transaction/transaction.css'
import '../transaction/form.css'

type DeferredPaymentFormProps = {
  payment: Payment; date: string; earliestDate?: string | null; excess: number; contractNo?: string
  pending: boolean; canProcess: boolean; actionContainer: HTMLElement | null
  unsavedScope: string
  onPreview: (body: PaymentPayload) => Promise<Precheck>
  onSave: (body: PaymentPayload, expectedUpdatedAt?: string) => Promise<void>
}

export function DeferredPaymentForm(props: DeferredPaymentFormProps) {
  const [amount, setAmount] = useState('')
  const { payment, date, earliestDate, excess, unsavedScope } = props
  useEffect(() => () => useUnsavedWorkStore.getState().mark(unsavedScope + 'deferred', false), [unsavedScope])
  return <DeferredPaymentEditor key={JSON.stringify([payment, date, earliestDate, excess])} {...props} amount={amount} onAmountChange={setAmount} />
}

function DeferredPaymentEditor({ payment, date, earliestDate, excess, contractNo, pending, canProcess, actionContainer, unsavedScope, amount, onAmountChange, onPreview, onSave }: DeferredPaymentFormProps & { amount: string; onAmountChange: (value: string) => void }) {
  const [checked, setChecked] = useState<{ amount: string; result: Precheck } | null>(null)
  const [error, setError] = useState('')
  const result = checked?.amount === amount ? checked.result : null
  const deferredAmount = amount === '' ? 0 : Number(amount)
  const currentAmount = Number(payment.amount ?? 0) - deferredAmount
  const insurer = payment.paymentStage === 'INSURER_TO_GA'
  const agents = useEntityChoices('agents', { asOf: payment.settlementMonth ?? '' }, payment.agentId ? [String(payment.agentId)] : [], !insurer)
  const agent = agents.data.find(row => row.agentId === payment.agentId)
  const agentLabel = agent ? entityLabel('agents', agent) : '-'
  const contracts: Contract[] = [{ contractId: payment.attributions?.[0]?.contractId ?? payment.contractId, contractNo }]
  const contractSource = { key: ['deferred-contract', payment.commissionTransactionId],
    load: async () => ({ content: contracts, page: 1, totalPages: 1 }),
    id: (row: Contract) => String(row.contractId), label: (row: Contract) => row.contractNo ?? '-' }
  const originalRow = draftRows(payment)[0]
  const settlementSupport = ['SETTLEMENT_SUPPORT_MONTHLY', 'FIRST_CONTRACT_CARRY_FORWARD', 'DEFERRED_CONTRACT'].includes(originalRow?.attributionMethod ?? '')
  const rows = originalRow ? [
    { ...originalRow, key: 1, amount: String(currentAmount) },
    { ...originalRow, key: 2, amount: String(deferredAmount), attributionDate: date,
      attributionMethod: settlementSupport ? 'DEFERRED_CONTRACT' as const : originalRow.attributionMethod,
      decision: settlementSupport ? 'REVIEW_REQUIRED' : originalRow.decision },
  ] : []
  const valid = canProcess && Boolean(payment.updatedAt && earliestDate && date && date >= earliestDate)
    && Number.isSafeInteger(deferredAmount) && deferredAmount > excess && currentAmount >= 0 && payment.attributions?.length === 1 && payment.cashflowType === 'PAYMENT'
  function body() {
    const reduced = { ...payment, amount: currentAmount, attributions: payment.attributions!.map(row => ({ ...row, amount: currentAmount })) }
    const payload = buildPayload(initialForm(payment.settlementMonth?.slice(0, 7) ?? '', reduced), draftRows(reduced), false)
    payload.scheduledPaymentDate = payment.scheduledPaymentDate ?? payload.scheduledPaymentDate
    payload.allocationPolicyVersion = payment.allocationPolicyVersion ?? null
    // 현재 지급분의 귀속은 보존한다. 상단 날짜는 별도로 생성할 이연 지급과 귀속에만 적용한다.
    payload.attributions[0].inclusionDecisionReason = payment.attributions![0].inclusionDecisionReason ?? payload.attributions[0].inclusionDecisionReason
    return payload
  }
  const actions = <>
    <Button data-guide-feature="features.exception.deferred-payment.preview" variant="secondary" disabled={!valid || pending} onClick={async () => {
      setError(''); setChecked(null)
      try { setChecked({ amount, result: await onPreview(body()) }) } catch (caught) { setError(errorText(caught instanceof Error ? caught : undefined, '이연 사전검증에 실패했습니다.')) }
    }}>사전검증</Button>
    <Button data-guide-feature="features.exception.deferred-payment.save" disabled={!valid || pending || result?.confirmable !== true} onClick={async () => {
      setError('')
      try { await onSave(body(), payment.updatedAt) } catch (caught) { setChecked(null); setError(errorText(caught instanceof Error ? caught : undefined, '이연 내용을 저장하지 못했습니다.')) }
    }}>이연 저장</Button>
  </>
  return <>
    <div className="transaction-form-page">
      <ResizableSplit className="transaction-form-layout" storageKey="transaction-form-workspace" label="지급 건, 귀속 내역과 확정 점검 카드 너비" defaultRatio={61}>
        <div className="transaction-form-column">
          <section data-guide-feature="features.exception.deferred-payment.summary" className="surface transaction-form-panel">
            <header className="surface-header transaction-surface-header"><h2 className="surface-title">지급 건</h2><StatusBadge tone="neutral">작성중</StatusBadge></header>
            <div className="surface-body transaction-form-grid">
              <Field label="지급단계" required><select data-guide-feature="features.exception.deferred-payment.stage" value={payment.paymentStage} disabled>
                <option value="GA_TO_FC">GA→설계사</option><option value="INSURER_TO_GA">보험회사→GA</option>
              </select></Field>
              <Field label="자료 구분" required><select data-guide-feature="features.exception.deferred-payment.source" value={payment.sourceType} disabled><option value={payment.sourceType}>GA 수기지급</option></select></Field>
              <Field label="지급 관리번호" required helper="같은 자료 구분 안에서 중복될 수 없습니다."><input data-guide-feature="features.exception.deferred-payment.business-key" className="transaction-mono" value={payment.sourceBusinessKey ?? ''} readOnly /></Field>
              <Field label="정산월" required helper="이연 지급분의 정산월입니다. 기존 귀속월은 유지됩니다."><input data-guide-feature="features.exception.deferred-payment.month" type="month" value={date.slice(0, 7)} readOnly /></Field>
              <Field label="수령 설계사" required helper="GA→설계사 단계에서는 반드시 있어야 합니다."><input data-guide-feature="features.exception.deferred-payment.agent" value={agentLabel} readOnly /></Field>
              <Field label="수수료 항목" required><input data-guide-feature="features.exception.deferred-payment.item" value={payment.commissionItemName ?? payment.commissionItemCode ?? '-'} readOnly /></Field>
              <Field label="이연 금액 (원)" required helper={`${won(excess)}보다 크고 ${won(payment.amount)} 이하로 입력하세요.`}
                error={amount !== '' && (!Number.isSafeInteger(deferredAmount) || deferredAmount <= excess || currentAmount < 0) ? '이연 금액은 한도 초과액보다 크고 원 지급액 이하여야 합니다.' : undefined}>
                <input data-guide-feature="features.exception.deferred-payment.amount" className="transaction-number" type="number" min={Math.floor(excess) + 1} max={Number(payment.amount ?? 0)} step="1"
                  value={amount} disabled={pending} onChange={event => {
                    setChecked(null); setError('')
                    onAmountChange(event.target.value)
                    useUnsavedWorkStore.getState().mark(unsavedScope + 'deferred', event.target.value !== '')
                  }} />
              </Field>
              <Field label="지급/차감" required><select data-guide-feature="features.exception.deferred-payment.cashflow" value={payment.cashflowType} disabled><option value="PAYMENT">지급</option><option value="DEDUCTION">차감</option></select></Field>
              <Field label="증빙" helper="귀속 내역에서 제외를 고르면 증빙이 반드시 필요합니다."><input data-guide-feature="features.exception.deferred-payment.evidence" value={paymentEvidenceText(payment.evidenceRef ?? '')} readOnly /></Field>
            </div>
          </section>
          <AttributionEditor rows={rows} contracts={contracts} contractSource={contractSource} onContractSelected={() => {}}
            agentLabel={agentLabel} amount={String(payment.amount ?? 0)} insurer={insurer} newcomer={false} disabled
            rowLabels={{ 1: '기존 귀속', 2: '이연 귀속' }} caption="이연 지급 및 귀속 내역" onChange={() => {}} onAdd={() => {}} />
          <p className="field-helper">기존 귀속에서 이연 금액을 자동 차감합니다. 이연 귀속은 계약 1년 뒤부터의 날짜로 설정되어 현재 초년도 한도 산입에서 제외됩니다.</p>
          {(!earliestDate || !date || date < earliestDate) && <p role="alert">계약 1년 뒤부터의 지급 예정일을 선택하세요.</p>}
        </div>
        <div className="transaction-form-column"><CapPanel result={result} pending={pending} error={error} contracts={contracts} /><GatePanel result={result} correction /></div>
      </ResizableSplit>
    </div>
    {actionContainer ? createPortal(actions, actionContainer) : actions}
  </>
}
