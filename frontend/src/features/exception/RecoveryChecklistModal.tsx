import { lazy, Suspense, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { apiClient } from '../../lib/api/client'
import { date, errorText, today, won } from '../../lib/format'
import type { components } from '../../lib/api/schema'
import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import { toast } from '../../stores/toasts'
import { useAuth } from '../auth/useAuth'
import { useCommissionItems } from '../base/api'
import { ClawbackCalculation } from '../clawback/ClawbackCalculation'
import { ClawbackDocuments } from '../clawback/ClawbackDocuments'
import { ClawbackActionPlacement } from '../clawback/actionPlacement'
import { candidateDetailQuery, candidateListQuery, defaultCriteria, noticeHistoryQuery } from '../clawback/api'
import { readPayment } from '../transaction/form-api'
import type { Payment } from '../transaction/form-api'
import { SOURCE_TYPE } from '../transaction/form-model'
import { applyActionToCache } from './api'
import type { ExceptionCase } from './api'
import '../clawback/clawback.css'

const TransactionForm = lazy(() => import('../transaction/TransactionFormPage').then(module => ({ default: module.TransactionForm })))
type PaymentPage = components['schemas']['PageResponseCommissionPaymentListResponse']

export function RecoveryChecklistModal({ item, payments, onClose }: { item: ExceptionCase; payments: Payment[]; onClose: () => void }) {
  const { user } = useAuth()
  const client = useQueryClient()
  const [candidateId, setCandidateId] = useState('')
  const [step, setStep] = useState<'calculation' | 'deduction' | null>(null)
  const [discard, setDiscard] = useState(false)
  const [confirmed, setConfirmed] = useState<Payment | null>(null)
  const [selectedPayment, setSelectedPayment] = useState('')
  const [pdfHost, setPdfHost] = useState<HTMLDivElement | null>(null)
  const payment = payments.find(p => String(p.commissionTransactionId) === selectedPayment) ?? payments[0]
  const candidates = useQuery(candidateListQuery({ ...defaultCriteria(''), contractNo: item.contractNo ?? '', triggerType: 'STATUS_EVENT' }))
  const rows = (candidates.data?.content ?? []).filter(row => row.contractId === item.contractId && row.triggerType === 'STATUS_EVENT')
  const selected = rows.find(row => String(row.candidateId) === candidateId) ?? (rows.length === 1 ? rows[0] : undefined)
  const detail = useQuery(candidateDetailQuery(selected?.candidateId))
  const calculation = detail.data?.latestCalculation
  const caseId = detail.data?.candidate?.clawbackCaseId ?? calculation?.clawbackCaseId
  const notices = useQuery({ ...noticeHistoryQuery(caseId ?? 0, 1), enabled: Boolean(caseId) })
  const current = Boolean(calculation && calculation.sourceVersion === detail.data?.candidate?.sourceVersion && detail.data?.candidate?.calculationStatus === 'CALCULATED')
  const notice = current ? notices.data?.content?.find(row => row.calculationVersion === calculation?.calculationVersion
    && row.originalPaymentId === payment?.commissionTransactionId) : undefined
  const attributionIds = new Set(detail.data?.paymentAttributions?.filter(row => row.commissionTransactionId === payment?.commissionTransactionId).map(row => row.transactionAttributionId))
  const lines = calculation?.lines?.filter(line => line.transactionAttributionId != null && attributionIds.has(line.transactionAttributionId)) ?? []
  const recoveryAmount = lines.reduce((sum, line) => sum + Number(line.clawbackAmount ?? 0), 0)
  const scopedDetail = detail.data && { ...detail.data, latestCalculation: calculation && { ...calculation, lines, amount: recoveryAmount,
    basis: { ...calculation.basis, excluded: Array.isArray(calculation.basis?.excluded) ? calculation.basis.excluded.filter(row => attributionIds.has(row.transactionAttributionId)) : [] } } }
  const businessKey = `CLAWBACK-RECOVERY:${payment?.commissionTransactionId}:${selected?.candidateId}`
  const legacyKey = `EXCP-RECOVERY:${item.exceptionCaseId}:${payment?.commissionTransactionId}:${selected?.candidateId}`
  const deduction = useQuery({
    queryKey: ['transactions', 'exception-recovery', businessKey], enabled: Boolean(selected && payment),
    queryFn: async ({ signal }) => {
      for (let page = 1; ; page++) {
        const query = new URLSearchParams({ contractNo: item.contractNo ?? '', page: String(page), size: '100' })
        const { data } = await apiClient.request<PaymentPage>(`/api/v1/transactions?${query}`, { signal })
        if (!Array.isArray(data?.content)) throw new Error('기존 환수 차감 건을 확인하지 못했습니다.')
        const row = data.content.find(p => [businessKey, legacyKey].includes(p.sourceBusinessKey ?? '') && p.status !== 'CANCELLED')
        if (row?.commissionTransactionId) return readPayment(String(row.commissionTransactionId), signal)
        if (page >= (data.totalPages ?? 0)) return null
      }
    }, meta: { errorToast: false },
  })
  const saved = confirmed?.sourceBusinessKey === businessKey ? confirmed : deduction.data
  const deductionDone = current && saved?.status === 'CONFIRMED' && saved.cashflowType === 'DEDUCTION' && saved.amount === recoveryAmount
  const recheck = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: async (deductionPaymentId: number) => {
      const { data } = await apiClient.request<components['schemas']['ExceptionSourceRecheckResponse']>(
        `/api/v1/exceptions/${item.exceptionCaseId}/payment-correction/reconcile-recovery`, { method: 'POST', body: {
          deductionPaymentId, candidateId: selected?.candidateId, originalPaymentId: payment?.commissionTransactionId,
        } })
      if (!data?.action) throw new Error('재대사 결과를 확인하지 못했습니다.')
      return data
    },
    meta: { errorToast: false },
    onSuccess: result => {
      applyActionToCache(client, item.exceptionCaseId!, result.action!)
      void client.invalidateQueries({ queryKey: ['reconciliations'], refetchType: 'none' })
      toast(result.message ?? '재대사 결과를 확인했습니다.', result.resolved ? 'success' : 'warning')
      if (result.resolved) onClose()
    },
  })
  const scope = `exception:${item.exceptionCaseId}:recovery:`
  const dirty = () => Object.keys(useUnsavedWorkStore.getState().owners).some(owner => owner.startsWith(scope))
  const closeStep = () => { if (dirty()) setDiscard(true); else setStep(null) }
  const due = today()
  const items = useCommissionItems(`${due.slice(0, 7)}-01`, current && recoveryAmount > 0)
  const clawbackItem = items.data?.find(entry => entry.itemCode === 'CLAWBACK' && entry.cashflowType === 'DEDUCTION')
  const calculationRef = `환수 계산 v${calculation?.calculationVersion} · 원지급 ${payment?.commissionTransactionId}`
  const draft: Payment | undefined = current && payment && recoveryAmount > 0 && clawbackItem?.commissionItemId ? {
    sourceType: SOURCE_TYPE, sourceBusinessKey: businessKey, contractId: item.contractId, agentId: payment.agentId,
    commissionItemId: clawbackItem.commissionItemId, commissionItemCode: clawbackItem.itemCode, commissionItemName: clawbackItem.itemName,
    amount: recoveryAmount, cashflowType: 'DEDUCTION', paymentStage: payment.paymentStage, scheduledPaymentDate: due,
    settlementMonth: `${due.slice(0, 7)}-01`, evidenceRef: calculationRef, note: `비유효 계약 지급분 환수 · 예외 ${item.exceptionCaseId} · 원 지급 ${payment.commissionTransactionId}`,
    attributions: [{ contractId: item.contractId, attributionDate: due, amount: recoveryAmount, attributionMethod: 'DIRECT',
      inclusionDecisionStatus: 'INCLUDED', inclusionDecisionReason: '비유효 계약 지급분 환수', allocationBasis: 'DIRECT', evidenceRef: calculationRef }],
  } : undefined
  return <>
    <Modal open title="비유효 계약 지급 · 환수 점검" onClose={onClose} closeOnBackdrop={false}
      footer={<><Button data-guide-feature="exception.recovery.recheck" disabled={!deductionDone || !user?.canProcess || recheck.isPending}
        loading={recheck.isPending} onClick={() => { if (saved?.commissionTransactionId) recheck.mutate(saved.commissionTransactionId) }}>재대사 · 해결</Button>
        <Button data-guide-feature="exception.recovery.close" variant="secondary" disabled={recheck.isPending} onClick={onClose}>닫기</Button></>}>
      <p>{item.contractNo} · {item.agentName}{payment ? ` · 원지급 ${won(payment.amount)}` : ''}</p>
      <p>계약 상태 변경 후 지급된 금액의 환수 근거를 확인하고 차감으로 등록하세요. 원지급 기록은 보존합니다.</p>
      {candidates.error && <p role="alert">{errorText(candidates.error)}</p>}
      {candidates.isPending && <p role="status">환수 대상을 불러오는 중입니다.</p>}
      {rows.length > 1 && <Field label="환수 기준 사건"><select data-guide-feature="exception.recovery.candidate" className="select-control" value={candidateId} onChange={event => setCandidateId(event.target.value)}>
        <option value="">계약 상태 변경 사건을 선택하세요</option>{rows.map(row => <option key={row.candidateId} value={row.candidateId}>{date(row.eventDate)} · {row.agentName} · 후보 {row.candidateId}</option>)}
      </select></Field>}
      {candidates.isSuccess && !rows.length && <p>이 계약의 상태 변경 환수 후보가 없습니다. 계약 상태이력과 환수 후보 생성 여부를 확인하세요.</p>}
      {payments.length > 1 && <Field label="환수할 원지급"><select data-guide-feature="exception.recovery.payment" className="select-control" value={String(payment?.commissionTransactionId ?? '')} onChange={event => setSelectedPayment(event.target.value)}>
        {payments.map(row => <option key={row.commissionTransactionId} value={row.commissionTransactionId}>지급 {row.commissionTransactionId} · {row.commissionItemName} · {won(row.amount)}</option>)}
      </select></Field>}
      <ol className="exception-recovery-checklist">
        <li><strong>{current ? '✓' : '1.'} 환수액 계산 · PDF 출력</strong><p>{notice ? `PDF 발행 완료 · ${notice.documentNo}` : current && recoveryAmount > 0 ? '환수액 계산 완료 · PDF는 필요할 때 출력하세요.' : '적용 정책으로 환수액을 계산하세요.'}</p>
          <Button data-guide-feature="exception.recovery.calculate" variant="secondary" disabled={!detail.data} onClick={() => setStep('calculation')}>환수액 계산 · PDF 열기</Button></li>
        <li><strong>{deductionDone ? '✓' : '2.'} 환수액만큼 차감 등록 · 확정</strong><p>{deductionDone ? `차감 ${saved?.commissionTransactionId}번 · ${won(saved?.amount)} 확정` : current && recoveryAmount > 0 ? `이 지급 건의 환수액 ${won(recoveryAmount)}` : '이 지급 건의 환수액을 먼저 계산하세요.'}</p>
          <Button data-guide-feature="exception.recovery.deduction" variant="secondary" disabled={!draft || deduction.isPending || deduction.isError || saved?.status === 'CONFIRMED' || !user?.canProcess}
            onClick={() => setStep('deduction')}>수수료 지급 차감 등록</Button></li>
      </ol>
      {detail.error && <p role="alert">{errorText(detail.error)}</p>}
      {notices.error && <p role="alert">{errorText(notices.error)}</p>}
      {deduction.error && <p role="alert">{errorText(deduction.error)}</p>}
      {current && !lines.length && <p>이 지급의 수수료 항목에 적용할 환수율이 없습니다. 환수 정책에서 해당 항목의 규칙을 확인하세요.</p>}
      {items.error && <p role="alert">{errorText(items.error)}</p>}
      {current && recoveryAmount > 0 && items.isSuccess && !clawbackItem && <p>선택한 정산월에 사용할 수 있는 환수 차감 항목이 없습니다.</p>}
      {recheck.error && <p role="alert">{errorText(recheck.error, '재대사를 완료하지 못했습니다. 차감 건과 환수 계산 결과를 확인하세요.')}</p>}
      {deductionDone && <p role="status">차감이 확정됐습니다. 재대사로 환수 근거를 확인하고 예외를 해결하세요.</p>}
      {saved?.status === 'CONFIRMED' && !deductionDone && <p role="alert">확정한 차감 금액이 현재 환수 계산과 다릅니다. 차감 건과 계산 결과를 확인하세요.</p>}
    </Modal>
    {step === 'calculation' && scopedDetail && <Modal open title="환수액 계산 · PDF 출력" className="modal-large" onClose={closeStep} closeOnBackdrop={false}
      footer={<><div ref={setPdfHost} /><Button data-guide-feature="exception.recovery.calculation-close" variant="secondary" onClick={closeStep}>점검목록으로</Button></>}>
      <p>{item.contractNo} · {item.agentName}{payment ? ` · 원지급 ${won(payment.amount)}` : ''}</p>
      <ClawbackActionPlacement.Provider value={{ revised: false, calculation: null, document: pdfHost, roleCode: user?.roleCode }}>
        <ClawbackCalculation detail={scopedDetail} itemName={id => payments.find(p => p.commissionItemId === id)?.commissionItemName ?? '수수료'} unsavedScope={scope} />
        <ClawbackDocuments detail={scopedDetail} roleCode={user?.roleCode} refreshing={detail.isFetching} onRefresh={() => detail.refetch()} compact originalPaymentId={payment?.commissionTransactionId} />
      </ClawbackActionPlacement.Provider>
    </Modal>}
    {step === 'deduction' && draft && <Modal open title="수수료 지급 · 환수 차감 등록" className="modal-large" onClose={closeStep} closeOnBackdrop={false}
      footer={<Button data-guide-feature="exception.recovery.deduction-close" variant="secondary" onClick={closeStep}>점검목록으로</Button>}>
      <Suspense fallback={<p role="status">차감 입력 화면을 불러오는 중입니다.</p>}><TransactionForm key={`${businessKey}:${saved?.commissionTransactionId ?? 'new'}`}
        entry={{ contractId: String(item.contractId), cashflowType: 'DEDUCTION' }} initialId={saved?.commissionTransactionId ? String(saved.commissionTransactionId) : null}
        draft={saved ?? draft} month={(saved ?? draft).settlementMonth?.slice(0, 7) ?? due.slice(0, 7)} role={user?.roleCode ?? ''} canProcess={Boolean(user?.canProcess)}
        returnTo="/exceptions" unsavedScope={scope} onReload={async () => { await deduction.refetch() }} onConfirmed={result => {
          setConfirmed(result); setStep(null); void client.invalidateQueries({ queryKey: ['transactions'] })
          if (result.commissionTransactionId && result.cashflowType === 'DEDUCTION' && result.amount === recoveryAmount) recheck.mutate(result.commissionTransactionId)
        }} /></Suspense>
    </Modal>}
    <Modal open={discard} title="작성 중인 환수 처리를 닫을까요?" onClose={() => setDiscard(false)} footer={<>
      <Button data-guide-feature="exception.recovery.continue" variant="secondary" onClick={() => setDiscard(false)}>계속 작성</Button>
      <Button data-guide-feature="exception.recovery.discard" onClick={() => { setDiscard(false); setStep(null) }}>입력 버리고 닫기</Button>
    </>}><p>저장하지 않은 입력을 버리고 점검목록으로 돌아갑니다.</p></Modal>
  </>
}
