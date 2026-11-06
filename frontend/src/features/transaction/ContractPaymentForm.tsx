import { useRef, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { SearchSelect } from '../../components/SearchSelect'
import { apiClient } from '../../lib/api/client'
import { int } from '../../lib/format'
import { useUnsavedWork } from '../../stores/unsavedWork'
import { useEntityChoices } from '../base/selection'
import { contractSchedulesQuery } from '../contract/api'
import { scheduleDetailQuery, type ScheduleLine } from '../schedule/api'
import { formError, type Payment, type PaymentPayload } from './form-api'
import { ScreenWalkthrough } from '../assistant/guide/ScreenWalkthrough'
import type { ScreenHelpStep } from '../assistant/guide/ScreenHelp'

const helpSteps: ScreenHelpStep[] = [
  { targetId: 'payment.batch.scope', title: '계약과 회차 선택', instruction: '계약의 사용 중인 예상 스케줄입니다.', interaction: '지급단계·정산월·회차를 선택하세요.' },
  { targetId: 'payment.batch.lines', title: '여러 설계사의 지급 확인', instruction: '스케줄 한 행이 지급 한 건이 됩니다.', interaction: '수령인과 금액을 확인해 선택하세요.' },
  { targetId: 'payment.batch.save', title: '지급 초안 일괄 등록', instruction: '선택한 지급 초안을 한 번에 저장합니다.', interaction: '등록 후 건별 사전검증·확정을 진행하세요.' },
].map(step => ({ ...step, group: '계약별 지급 등록', details: [], concise: true }))

function payload(line: ScheduleLine, contractId: number, stage: 'GA_TO_FC' | 'INSURER_TO_GA', month: string): PaymentPayload {
  if (!line.scheduleLineId || line.contractId !== contractId || !line.commissionItemId || !line.attributionDate || !line.dueDate
    || !Number.isSafeInteger(line.expectedAmount) || line.expectedAmount! < 0
    || !['PAYMENT', 'DEDUCTION'].includes(line.cashflowType ?? '') || stage === 'GA_TO_FC' && !line.beneficiaryAgentId) {
    throw new Error('스케줄의 설계사·항목·금액·귀속일·예정일을 확인해 주세요.')
  }
  return {
    sourceType: 'GA_MANUAL_PAYMENT', sourceBusinessKey: `SCHEDULE-LINE-${stage}-${line.scheduleLineId}`,
    contractId, agentId: stage === 'GA_TO_FC' ? line.beneficiaryAgentId : null,
    commissionItemId: line.commissionItemId, amount: line.expectedAmount!, settlementMonth: `${month}-01`,
    scheduledPaymentDate: line.dueDate, paymentStage: stage, cashflowType: line.cashflowType!,
    note: `예상 스케줄 ${line.scheduleLineId} / ${line.installmentNo}회차`,
    attributions: [{ contractId, attributionDate: line.attributionDate, amount: line.expectedAmount!,
      inclusionDecisionStatus: 'INCLUDED', inclusionDecisionReason: '계약 예상 스케줄 기준 직접 귀속',
      allocationBasis: `스케줄 행 ${line.scheduleLineId}`, attributionMethod: 'DIRECT' }],
  }
}

export function ContractPaymentForm({ month, canProcess, returnTo, comparison, initialContractId = '' }: {
  month: string; canProcess: boolean; returnTo: string; comparison: (busy: boolean) => ReactNode; initialContractId?: string
}) {
  const client = useQueryClient()
  const [contractId, setContractId] = useState(initialContractId)
  const [stage, setStage] = useState<'GA_TO_FC' | 'INSURER_TO_GA'>('GA_TO_FC')
  const [settlementMonth, setMonth] = useState(month)
  const [installment, setInstallment] = useState('')
  const [excluded, setExcluded] = useState<number[]>([])
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const [error, setError] = useState('')
  const [created, setCreated] = useState<Payment[]>([])
  const contracts = useEntityChoices('contracts', {}, [contractId])
  const schedules = useQuery({ ...contractSchedulesQuery(contractId, stage), enabled: !!contractId, placeholderData: undefined })
  const active = schedules.data?.headers?.filter(header => header.activeYn && header.paymentStage === stage
    && header.scheduleRegime === 'CURRENT' && header.schedulePurpose === 'OPERATIONAL'
    && !['HOLD', 'CANCELLED'].includes(header.status ?? '')) ?? []
  const header = active.length === 1 ? active[0] : undefined
  const detail = useQuery({ ...scheduleDetailQuery(String(header?.scheduleHeaderId ?? '')), enabled: !!header?.scheduleHeaderId })
  const lines = detail.data?.lines ?? []
  const installments = [...new Set(lines.map(line => line.installmentNo).filter((n): n is number => n != null))].sort((a, b) => a - b)
  const chosen = installment || String(lines.find(line => line.dueDate?.startsWith(settlementMonth))?.installmentNo ?? installments[0] ?? '')
  const visible = lines.filter(line => String(line.installmentNo) === chosen)
  const eligible = (line: ScheduleLine) => ['PLANNED', 'CONFIRMED', 'RESTARTED'].includes(line.lineStatus ?? '') && !!line.scheduleLineId
  const selected = visible.filter(line => eligible(line) && !excluded.includes(line.scheduleLineId!))
  useUnsavedWork(!!contractId && !created.length)
  const reset = () => { setInstallment(''); setExcluded([]); setCreated([]); setError('') }
  async function register() {
    if (lock.current || !canProcess || !header || !selected.length || created.length) return
    lock.current = true; setBusy(true); setError('')
    try {
      // Re-read both the active version and rows immediately before creating drafts.
      const current = await client.fetchQuery({ ...contractSchedulesQuery(contractId, stage), staleTime: 0 })
      const latest = current.headers?.filter(row => row.activeYn && row.paymentStage === stage && row.schedulePurpose === 'OPERATIONAL' && row.scheduleRegime === 'CURRENT')
      if (latest?.length !== 1 || latest[0].scheduleHeaderId !== header.scheduleHeaderId || ['HOLD', 'CANCELLED'].includes(latest[0].status ?? '')) throw new Error('사용 중인 스케줄이 바뀌었습니다. 새 내용을 확인해 주세요.')
      const fresh = await client.fetchQuery({ ...scheduleDetailQuery(String(header.scheduleHeaderId)), staleTime: 0 })
      for (const line of selected) {
        const now = fresh.lines?.find(row => row.scheduleLineId === line.scheduleLineId)
        if (!now || JSON.stringify(now) !== JSON.stringify(line)) throw new Error('스케줄 내용이 바뀌었습니다. 새 금액과 대상을 확인한 뒤 다시 등록하세요.')
      }
      const payments = selected.map(line => payload(line, Number(contractId), stage, settlementMonth))
      const response = await apiClient.request<Payment[]>('/api/v1/transactions/batch', { method: 'POST', body: { payments } })
      if (!response.data?.length) throw new Error('등록 결과를 확인하지 못했습니다. 지급 목록에서 확인해 주세요.')
      setCreated(response.data)
      void client.invalidateQueries({ queryKey: ['transactions'] })
    } catch (failure) { setError(formError(failure, '지급 초안을 등록하지 못했습니다. 목록에서 기존 등록 여부를 확인해 주세요.')) }
    finally { lock.current = false; setBusy(false) }
  }
  return <div className="publishing-page transaction-page">
    <header className="page-header transaction-page-header">
      <div className="page-header-copy"><h1 className="page-title">지급 건 등록, 귀속, 확정</h1></div>
      <div className="page-header-actions">{comparison(busy)}<ScreenWalkthrough title="계약별 지급 등록" steps={helpSteps} /><Link className="button button-secondary" to={returnTo}>목록으로</Link></div>
    </header>
    <section className="surface" style={{ padding: 24 }}>
      <h2 className="surface-title">계약의 예상 스케줄로 지급 등록</h2>
      <p>계약과 회차를 선택하면 설계사·수수료 항목별 지급 초안을 한 번에 등록합니다.</p>
      <fieldset data-guide-target="payment.batch.scope" disabled={busy || created.length > 0} style={{ border: 0, padding: 0, display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
        <Field label="계약" required><SearchSelect value={contractId} source={contracts.source} selected={contracts.data?.find(row => String(row.contractId) === contractId)} placeholder="계약번호 검색"
          onSelect={(value, row) => { contracts.remember(row); setContractId(value); reset() }} /></Field>
        <Field label="지급단계"><select value={stage} onChange={event => { setStage(event.target.value as typeof stage); reset() }}><option value="GA_TO_FC">GA → 설계사</option><option value="INSURER_TO_GA">보험사 → GA</option></select></Field>
        <Field label="정산월" required><input type="month" value={settlementMonth} onChange={event => { setMonth(event.target.value); setInstallment(''); setExcluded([]) }} /></Field>
        <Field label="회차"><select value={chosen} disabled={!installments.length} onChange={event => { setInstallment(event.target.value); setExcluded([]) }}>{installments.length ? installments.map(value => <option key={value} value={value}>{value}회차</option>) : <option value="">스케줄을 선택하세요</option>}</select></Field>
      </fieldset>
      {(schedules.isFetching || detail.isFetching) && <p role="status">예상 스케줄을 불러오고 있습니다.</p>}
      {(schedules.error || detail.error || contracts.error) && <p role="alert">{formError(schedules.error ?? detail.error ?? contracts.error, '자료를 불러오지 못했습니다.')}</p>}
      {contractId && !schedules.isFetching && !header && <p>사용 중인 운영 스케줄이 한 개일 때 등록할 수 있습니다. 계약의 예상 스케줄을 확인하세요.</p>}
      {header && <p>스케줄 버전 {header.scheduleVersionNo} · {chosen}회차 · 선택 {selected.length}건 · 합계 {int(selected.reduce((sum, line) => sum + (line.expectedAmount ?? 0), 0))}원</p>}
      <div data-guide-target="payment.batch.lines" className="data-table-viewport"><table className="data-table"><caption className="visually-hidden">등록할 지급 초안</caption><thead><tr><th>선택</th><th>수령인</th><th>수수료 항목</th><th>귀속일</th><th>지급 예정일</th><th>금액</th><th>상태</th></tr></thead><tbody>
        {visible.map(line => <tr key={line.scheduleLineId}><td><input type="checkbox" aria-label={`${line.recipientName} ${line.commissionItemName} 선택`} checked={eligible(line) && !excluded.includes(line.scheduleLineId!)} disabled={busy || !!created.length || !eligible(line)} onChange={event => setExcluded(event.target.checked ? excluded.filter(id => id !== line.scheduleLineId) : [...excluded, line.scheduleLineId!])} /></td><td>{line.recipientName ?? (stage === 'INSURER_TO_GA' ? 'GA' : '대상 확인 필요')}</td><td>{line.commissionItemName}</td><td>{line.attributionDate}</td><td>{line.dueDate}</td><td>{int(line.expectedAmount)}원</td><td>{eligible(line) ? '등록 대상' : '등록 제외'}</td></tr>)}
      </tbody></table></div>
      {error && <p role="alert">{error}</p>}
      {created.length ? <div role="status"><p>지급 초안 {created.length}건을 등록했습니다. 각 지급 건에서 사전검증 후 확정하세요.</p><Link className="button button-primary" to={returnTo}>지급 목록에서 확인</Link></div>
        : <div className="page-header-actions" style={{ marginTop: 20 }}><span>선택한 스케줄 한 행당 지급 초안 한 건이 생성됩니다.</span><Button data-guide-target="payment.batch.save" loading={busy} disabled={!canProcess || !selected.length || selected.length > 200 || !settlementMonth || schedules.isFetching || detail.isFetching || !!schedules.error || !!detail.error} onClick={() => void register()}>지급 초안 {selected.length}건 등록</Button></div>}
    </section>
  </div>
}
