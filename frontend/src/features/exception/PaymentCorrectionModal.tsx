import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useEffect, useState } from 'react'
import { Button } from '../../components/Button'
import { Link } from 'react-router'
import { Field } from '../../components/Field'
import { DataTable } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { ReviewPanel } from './ReviewPanel'
import { DuplicateJournalModal } from './DuplicateJournalModal'
import { ReconciliationJournalModal } from './ReconciliationJournalModal'
import { RecoveryChecklistModal } from './RecoveryChecklistModal'
import { Modal } from '../../components/Modal'
import { apiClient, ApiError } from '../../lib/api/client'
import { errorText, rateText, won } from '../../lib/format'
import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import { toast } from '../../stores/toasts'
import { useAuth } from '../auth/useAuth'
import type { Payment, PaymentPayload, Precheck } from '../transaction/form-api'
import { applyActionToCache } from './api'
import type { ExceptionAction, ExceptionCase } from './api'
import type { components } from '../../lib/api/schema'

interface Issue { resultId?: number; resultType?: string; resultLabel?: string; expectedAmount?: number; actualAmount?: number; differenceAmount?: number; installmentNo?: number; expectedAgent?: string | null; actualAgent?: string | null; sourceLink?: string }
interface Context { payments: Payment[]; paymentJournalIds?: Record<string, number>; suggestedPayment?: PaymentPayload | null; capBasis?: components['schemas']['CapCheckBasisResponse'] | null; scheduledBasis?: boolean; scheduleLink?: string | null; policyLink?: string | null; correctionTarget?: 'PAYMENT' | 'SCHEDULE' | 'CONTRACT' | 'RECOVERY' | 'REVIEW'; paymentStage?: string; contractLink?: string | null; remainingIssues?: Issue[]; reconciliationResultType?: string | null; canRegisterPayment?: boolean; deferrableFrom?: string | null }
interface Receipt { payment: Payment; resolved: boolean; message: string; action?: ExceptionAction | null; deferredPayment?: Payment | null }
interface Recheck { capBasis?: components['schemas']['CapCheckBasisResponse'] | null; resolved: boolean; message: string; action?: ExceptionAction | null }
const TransactionForm = lazy(() => import('../transaction/TransactionFormPage').then(module => ({ default: module.TransactionForm })))
const ScheduleCorrectionPanel = lazy(() => import('./ScheduleCorrectionPanel').then(module => ({ default: module.ScheduleCorrectionPanel })))

function correctionError(error: unknown) {
  if (error instanceof ApiError && error.code === 'FGC-COMMON-002') {
    const reason = error.params.reason
    if (typeof reason === 'string') return new Error(`${reason}${error.requestId ? ` (요청번호: ${error.requestId})` : ''}`)
  }
  return error instanceof Error ? error : new Error('요청을 처리하지 못했습니다.')
}

export function PaymentCorrectionModal({ item, onClose }: { item: ExceptionCase; onClose: () => void }) {
  const client = useQueryClient()
  const { user } = useAuth()
  const [selectedId, setSelectedId] = useState('')
  const [discard, setDiscard] = useState(false)
  const [message, setMessage] = useState('')
  const [correctionMode, setCorrectionMode] = useState('')
  const [settlementEvidence, setSettlementEvidence] = useState('')
  const [plannedAction, setPlannedAction] = useState('')
  const [deferredDate, setDeferredDate] = useState('')
  const [receiptConfirmed, setReceiptConfirmed] = useState(false)
  const [actionContainer, setActionContainer] = useState<HTMLDivElement | null>(null)
  const [scheduleActions, setScheduleActions] = useState<HTMLDivElement | null>(null)
  const scope = `exception:${item.exceptionCaseId}:payment:`
  useEffect(() => () => useUnsavedWorkStore.getState().mark(scope + 'workflow', false), [scope])
  const path = `/api/v1/exceptions/${item.exceptionCaseId}/payment-correction`
  const context = useQuery({
    queryKey: ['exceptions', 'payment-correction', item.exceptionCaseId],
    queryFn: async ({ signal }) => {
      const { data } = await apiClient.request<Context>(path, { signal })
      if (!data) throw new Error('지급 원천을 확인하지 못했습니다.')
      return data
    },
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
  })
  const id = selectedId || String(context.data?.payments[0]?.commissionTransactionId ?? 'new')
  const reconciliationType = context.data?.reconciliationResultType ?? item.reconciliationResultType
  const missingActual = reconciliationType === 'ACTUAL_MISSING'
  const canRegister = missingActual && context.data?.canRegisterPayment !== false
  const amountDifference = reconciliationType === 'AMOUNT_DIFFERENCE'
  const expectedMissing = reconciliationType === 'EXPECTED_MISSING'
  const duplicate = reconciliationType === 'DUPLICATE'
  const workflowValid = (!amountDifference || Boolean(correctionMode && (correctionMode !== 'SETTLEMENT_COMPLETED' || settlementEvidence.trim())))
    && (plannedAction !== 'DEFER' || Boolean(deferredDate)) && (!missingActual || context.data?.paymentStage !== 'INSURER_TO_GA' || receiptConfirmed)
  const payment = context.data?.payments.find(p => String(p.commissionTransactionId) === id)
  const draft = payment ?? (id === 'new' ? context.data?.suggestedPayment as Payment | undefined : undefined)
  const correctionMetadata = (body: PaymentPayload) => ({
    ...(amountDifference ? { correctionMode, settlementEvidence: settlementEvidence.trim() || null } : {}),
    ...(plannedAction ? { plannedAction, ...(plannedAction === 'DEFER' ? {
      deferredAmount: Number(payment?.amount ?? 0) - Number(body.amount ?? 0), deferredPaymentDate: deferredDate,
    } : {}) } : {}),
  })
  const mutation = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: async ({ payment: body, expectedUpdatedAt }: { payment: PaymentPayload; expectedUpdatedAt?: string }) => {
      const { data } = await apiClient.request<Receipt>(path, { method: 'POST', body: {
        paymentId: payment?.commissionTransactionId ?? null, expectedUpdatedAt, payment: body,
        ...correctionMetadata(body),
      } })
      if (!data?.payment?.commissionTransactionId) throw new Error('저장 결과를 확인하지 못했습니다. 다시 저장하기 전에 지급 건을 확인하세요.')
      return data
    },
    meta: { errorToast: false },
    onSuccess: async result => {
      Object.keys(useUnsavedWorkStore.getState().owners).filter(owner => owner.startsWith(scope))
        .forEach(owner => useUnsavedWorkStore.getState().mark(owner, false))
      if (result.action) applyActionToCache(client, item.exceptionCaseId!, result.action)
      void client.invalidateQueries({ queryKey: ['transactions'], refetchType: 'none' })
      void client.invalidateQueries({ queryKey: ['reconciliations'], refetchType: 'none' })
      void client.invalidateQueries({ queryKey: ['cap-checks'], refetchType: 'none' })
      setMessage(result.message)
      toast(result.message, result.resolved ? 'success' : 'warning')
      if (result.deferredPayment) toast(`초과분 ${won(result.deferredPayment.amount)}을 ${result.deferredPayment.scheduledPaymentDate} 지급 초안 ${result.deferredPayment.commissionTransactionId}번으로 만들었습니다.`, 'success')
      if (result.resolved) onClose()
      else {
        setSelectedId(String(result.payment.commissionTransactionId))
        await context.refetch()
      }
    },
  })
  const recheck = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: async () => {
      const { data } = await apiClient.request<Recheck>(path + '/recheck', { method: 'POST' })
      if (!data) throw new Error('재검증 결과를 확인하지 못했습니다.')
      return data
    },
    meta: { errorToast: false },
    onSuccess: result => {
      if (result.action) applyActionToCache(client, item.exceptionCaseId!, result.action)
      client.setQueryData<Context>(['exceptions', 'payment-correction', item.exceptionCaseId], old => old ? { ...old, capBasis: result.capBasis } : old)
      void client.invalidateQueries({ queryKey: ['cap-checks'], refetchType: 'none' })
      void client.invalidateQueries({ queryKey: ['reconciliations'], refetchType: 'none' })
      setMessage(result.message)
      toast(result.message, result.resolved ? 'success' : 'warning')
      if (result.resolved) onClose()
    },
    onError: error => toast(errorText(error, '재검증하지 못했습니다.'), 'error'),
  })
  const pending = useIsMutating({ mutationKey: ['exception', 'write', item.exceptionCaseId] }) > 0
  const sourceCorrection = context.data?.scheduledBasis || context.data?.correctionTarget === 'SCHEDULE' || context.data?.correctionTarget === 'CONTRACT'
  const invalidContract = context.data?.correctionTarget === 'CONTRACT'
  const insurer = context.data?.paymentStage === 'INSURER_TO_GA'
  const externalInsurer = context.data?.scheduledBasis && insurer
  const manualReview = context.data?.correctionTarget === 'REVIEW'
  const basisGroups = Object.entries((context.data?.capBasis?.details ?? []).filter(d => d.classificationSnapshot === 'INCLUDED').reduce<Record<string, { count: number; amount: number }>>((groups, d) => {
    const key = d.commissionItemName ?? '기타'
    groups[key] = { count: (groups[key]?.count ?? 0) + 1, amount: (groups[key]?.amount ?? 0) + Number(d.amount ?? 0) }
    return groups
  }, {}))
  const cap = context.data?.capBasis?.capCheck
  const excess = cap?.includedAmount != null && cap.limitAmount != null ? Math.max(0, Number(cap.includedAmount) - Number(cap.limitAmount)) : 0
  const dirty = () => Object.keys(useUnsavedWorkStore.getState().owners).some(owner => owner.startsWith(`exception:${item.exceptionCaseId}:`))
  function close() { if (!pending) { if (dirty()) setDiscard(true); else onClose() } }
  function choose(value: string) {
    if (dirty()) { toast('입력한 내용을 저장하거나 창을 닫아 버린 뒤 다른 지급 건을 선택하세요.', 'warning'); return }
    setSelectedId(value)
    setMessage('')
  }
  if (amountDifference && context.data) return <ReconciliationJournalModal item={item} payments={context.data.payments}
    paymentJournalIds={context.data.paymentJournalIds ?? {}} onClose={onClose} />
  if (duplicate && context.data) return <DuplicateJournalModal item={item} payments={context.data.payments}
    paymentJournalIds={context.data.paymentJournalIds ?? {}} onChanged={async () => { await context.refetch() }} onClose={onClose} />
  if (context.data?.correctionTarget === 'RECOVERY') return <RecoveryChecklistModal item={item} payments={context.data.payments} onClose={onClose} />
  return <>
    <Modal open title={manualReview ? '스케줄 밖 지급 근거 검토' : externalInsurer ? '보험사 지급기준·한도 검토' : sourceCorrection ? expectedMissing ? `계약 상세 · ${item.contractNo ?? '-'}` : invalidContract ? '계약 상태·예상 스케줄 확인' : '예상 스케줄 확인·재생성' : '수수료 지급 건 등록·수정'} className="modal-large exception-payment-modal"
      onClose={close} closeOnBackdrop={false} closeOnEscape={!pending}
      footer={<>
        {expectedMissing && sourceCorrection && <div ref={setScheduleActions} />}
        {!missingActual && (sourceCorrection || item.type === 'RECONCILIATION_MISMATCH') && <Button data-guide-feature="features.exception.payment-correction.recheck" loading={recheck.isPending} disabled={pending || dirty()} onClick={() => recheck.mutate()}>{context.data?.scheduledBasis ? '스케줄 재검증' : '재대사'}</Button>}
        <div className="exception-payment-footer-actions" ref={setActionContainer} />
        <Button data-guide-feature="features.exception.payment-correction.close" variant="secondary" disabled={pending} onClick={close}>닫기</Button>
      </>}>
      {context.isPending && <p role="status">지급 원천을 불러오는 중입니다.</p>}
      {context.error && <p role="alert" className="field-error">{errorText(context.error)}</p>}
      {context.data && <>
        {manualReview ? <ReviewPanel item={item} onClosed={onClose} initialEvidence={item.contractNo} /> : sourceCorrection ? <section data-guide-feature="features.exception.schedule-correction" className="exception-schedule-correction">
          <p className="exception-correction-source">{insurer ? '보험회사→GA' : 'GA→설계사'} · {context.data.scheduledBasis ? '예상 스케줄 기준 한도 검증' : '예상·실제 대사'}</p>
          {context.data.scheduledBasis && <><div className="exception-schedule-amounts">
            <div><span>예상 지급계획</span><strong>{won(context.data.capBasis?.capCheck?.includedAmount)}</strong></div>
            <div><span>1,200% 한도</span><strong>{won(context.data.capBasis?.capCheck?.limitAmount)}</strong></div>
            <div><span>초과 금액</span><strong>{won(Math.max(0, Number(context.data.capBasis?.capCheck?.includedAmount ?? 0) - Number(context.data.capBasis?.capCheck?.limitAmount ?? 0)))}</strong></div>
          </div>
          <ul className="exception-schedule-breakdown">{basisGroups.map(([name, group]) => <li key={name}><span>{name} · {group.count}회</span><strong>{won(group.amount)}</strong></li>)}</ul></>}
          <p>{invalidContract ? '지급일 기준 비유효 계약으로 검출됐습니다. 계약 상태와 지급 근거를 먼저 확인하세요. 예상 0원만으로 스케줄 누락이라고 판단할 수 없습니다.'
            : externalInsurer ? '보험사 지급기준과 계산근거를 대조하세요. 기존 정책으로 스케줄을 재생성해도 한도 초과가 해소되지 않습니다. 보험사 시정 요청 또는 FGC 원천 입력 정정을 검토하고 GA관리자에게 배정하세요.'
            : context.data.scheduledBasis ? '수수료 규칙과 예상 스케줄을 확인하세요. 원천 오류가 확인되면 정정 후 스케줄을 재생성하고 재검증하세요.'
            : '예상 금액이 없습니다. 계약의 스케줄 생성 대상 여부와 적용 정책을 확인하고 예상 스케줄을 생성·재생성한 뒤 재대사하세요.'}</p>
          <div className="exception-schedule-links">
            {invalidContract && context.data.contractLink && <Link data-guide-feature="features.exception.schedule-correction.contract" className="button" to={context.data.contractLink}>계약 상태 확인</Link>}
            {context.data.policyLink && <Link data-guide-feature="features.exception.schedule-correction.policy" className="button" to={context.data.policyLink}>적용 수수료 규칙 확인</Link>}
            {context.data.scheduleLink && (context.data.scheduledBasis || !item.contractId) && <Link data-guide-feature="features.exception.payment-correction.schedule" className="button button-secondary" to={context.data.scheduleLink}>예상 스케줄 확인</Link>}
          </div>
          {message && <p role="status" className="exception-correction-result">{message}</p>}
          {item.contractId && <Suspense fallback={<p role="status">계약·스케줄을 불러오는 중입니다.</p>}><ScheduleCorrectionPanel
            contractId={item.contractId} exceptionId={item.exceptionCaseId!}
            readOnly={Boolean(externalInsurer)}
            actionContainer={expectedMissing ? scheduleActions : undefined}
            onRegenerated={async () => {
              if (expectedMissing) setMessage('스케줄을 재생성했습니다. 재대사로 예상 지급과 실제 지급을 비교하세요.')
              else await recheck.mutateAsync()
            }}
          /></Suspense>}
          {externalInsurer && <ReviewPanel item={item} onClosed={onClose} />}
        </section> : <>
        {item.type === 'CAP_VIOLATION' && <section data-guide-feature="features.exception.payment-correction.cap-overage" className="guidance guidance-neutral">
          <strong className="guidance-title">{excess > 0 ? `한도 초과 · 최소 ${won(excess)} 줄여야 합니다.` : '검출 당시 한도 초과 내역'}</strong>
          {item.description && <p className="guidance-copy">{rateText(item.description)}</p>}
          <p className="guidance-copy">지급액과 귀속금액을 함께 조정한 뒤 사전검증을 다시 실행하세요.</p>
        </section>}
        {missingActual && insurer && <label className="checkbox-label"><input data-guide-feature="features.exception.payment-correction.receipt-confirmed" type="checkbox" checked={receiptConfirmed}
          onChange={event => { setReceiptConfirmed(event.target.checked); useUnsavedWorkStore.getState().mark(scope + 'workflow', event.target.checked) }} />보험사 명세·입금 확인 완료</label>}
        {!context.data.scheduledBasis && ['CAP_WARNING', 'CAP_VIOLATION', 'CAP_REVIEW_REQUIRED'].includes(item.type ?? '') && payment && <section data-guide-feature="features.exception.payment-correction.defer-guide" className="exception-correction-workflow">
          <Field label="감액·이연 구분"><select data-guide-feature="features.exception.payment-correction.planned-action" className="select-control" value={plannedAction} disabled={pending} onChange={event => { setPlannedAction(event.target.value); useUnsavedWorkStore.getState().mark(scope + 'workflow', true) }}>
            <option value="">변경 내용으로 자동 판단</option><option value="REDUCE">초과분 감액</option><option value="DEFER">초과분을 2차년도 이후로 이연</option>
          </select></Field>
          {plannedAction === 'DEFER' && <Field label="초과분 지급 예정일" required helper={`현재 지급액을 잔여 한도 이내로 줄이세요. 차액은 ${context.data.deferrableFrom ?? '계약 2차년도'} 이후 지급 초안으로 분리하며 지금 확정하지 않습니다.`}>
            <input data-guide-feature="features.exception.payment-correction.deferred-date" type="date" min={context.data.deferrableFrom ?? undefined} value={deferredDate} disabled={pending} onChange={event => { setDeferredDate(event.target.value); useUnsavedWorkStore.getState().mark(scope + 'workflow', true) }} />
          </Field>}
        </section>}
        {amountDifference && <section data-guide-feature="features.exception.payment-correction.amount-guide" className="exception-correction-workflow">
          <h3>금액 부족·초과 정정</h3>
          <p>기존 지급 건의 전체 금액을 정정합니다. 저장 시 연결된 원장을 역분개·재기표하고 재대사합니다. 차액을 별도 지급 건으로 추가하면 중복 지급으로 검출될 수 있습니다.</p>
          <Field label="처리 구분" required><select data-guide-feature="features.exception.payment-correction.mode" className="select-control" value={correctionMode} disabled={pending}
            onChange={event => { setCorrectionMode(event.target.value); useUnsavedWorkStore.getState().mark(scope + 'workflow', true) }}>
            <option value="">처리 구분을 선택하세요</option>
            <option value="RECORD_ONLY">기록만 잘못된 경우 · 기록 오류 정정</option>
            <option value="SETTLEMENT_COMPLETED">실제 지급액이 다른 경우 · 추가 송금 또는 반환 완료</option>
          </select></Field>
          {correctionMode === 'SETTLEMENT_COMPLETED' && <Field label="추가 송금·반환 증빙" required helper="실제 부족분의 추가 송금 또는 초과분의 반환을 먼저 처리한 뒤 증빙 번호나 링크를 입력하세요.">
            <input data-guide-feature="features.exception.payment-correction.settlement-evidence" value={settlementEvidence} maxLength={500} disabled={pending}
              onChange={event => { setSettlementEvidence(event.target.value); useUnsavedWorkStore.getState().mark(scope + 'workflow', true) }} />
          </Field>}
          {payment?.journalHeaderId && <Link data-guide-feature="features.exception.payment-correction.journal" to={`/journals?selected=${payment.journalHeaderId}`}>연결된 원장 확인</Link>}
        </section>}
        {context.data.capBasis && <details className="exception-payment-basis">
          <summary data-guide-feature="features.exception.payment-correction.basis">검출 당시 실제 산입액 · {won(context.data.capBasis.capCheck?.includedAmount)}</summary>
          <ul>{basisGroups.map(([name, group]) => <li key={name}>{name} {group.count}건 · {won(group.amount)}</li>)}</ul>
        </details>}
        <Field label="수정할 지급 건">
          <select data-guide-feature="features.exception.payment-correction.payment" className="select-control" value={id} disabled={mutation.isPending} onChange={event => choose(event.target.value)}>
            {context.data.payments.map(p => <option key={p.commissionTransactionId} value={p.commissionTransactionId}>
              지급 {p.commissionTransactionId} · {p.commissionItemName ?? p.commissionItemCode} · {won(p.amount)} · {p.settlementMonth?.slice(0, 7)}
            </option>)}
            {canRegister && <option value="new">누락 지급 신규 등록</option>}
          </select>
        </Field>
        {message && <p role="status" className="exception-correction-result">{message}</p>}
        {context.data.remainingIssues && context.data.remainingIssues.length > 0 && <section data-guide-feature="features.exception.payment-correction.remaining" className="exception-remaining-issues" aria-label="현재 대사 차이">
          <h3>현재 대사 차이</h3>
          {context.data.remainingIssues.map(issue => <div key={issue.resultId} className="exception-reconciliation-item">
            <DataTable caption="현재 대사 금액 비교" resizable={false} className="exception-reconciliation-table" rows={[issue]} rowKey={() => String(issue.resultId)} columns={[
              { key: 'expected', label: '예상 금액', align: 'number', render: row => won(row.expectedAmount) },
              { key: 'actual', label: '실제 금액', align: 'number', render: row => won(row.actualAmount) },
              { key: 'difference', label: '차액', align: 'number', render: row => <span className={Number(row.differenceAmount ?? 0) !== 0 ? 'exception-difference-amount' : ''}>{won(row.differenceAmount)}</span> },
              { key: 'result', label: '결과 유형', align: 'center', render: row => <StatusBadge kind="category" optionCount={11} tone={row.resultType === 'REVIEW_REQUIRED' ? 'review' : 'error'}>{row.resultLabel}</StatusBadge> },
            ]} />
            <div className="exception-reconciliation-meta">
              {issue.installmentNo != null && <span>{issue.installmentNo}회차</span>}
              <span>예상 수령인 <strong>{issue.expectedAgent ?? '-'}</strong></span>
              <span>실제 수령인 <strong>{issue.actualAgent ?? '-'}</strong></span>
              {issue.sourceLink && <Link data-guide-feature="features.exception.payment-correction.result" to={issue.sourceLink}>대사 결과 확인</Link>}
            </div>
          </div>)}
        </section>}
        {payment || id === 'new' && canRegister && draft ? <Suspense fallback={<p role="status">지급 입력 화면을 불러오는 중입니다.</p>}><TransactionForm
          key={`${id}:${context.dataUpdatedAt}`} entry={{ contractId: item.contractId ? String(item.contractId) : undefined }}
          initialId={payment ? id : null} draft={draft ?? undefined}
          month={draft?.settlementMonth?.slice(0, 7) ?? item.validationMonth?.slice(0, 7) ?? ''}
          canProcess={user?.canProcess === true} correctionReady={workflowValid} role={user?.roleCode ?? ''} returnTo="/exceptions"
          onReload={async () => { await context.refetch() }} unsavedScope={scope}
          actionContainer={actionContainer}
          onCorrect={async (body, expectedUpdatedAt) => {
            try { await mutation.mutateAsync({ payment: body, expectedUpdatedAt }) } catch (error) { throw correctionError(error) }
          }}
          onPreview={async body => {
            const { data } = await apiClient.request<Precheck>(path + '/precheck', { method: 'POST', body: {
              paymentId: payment?.commissionTransactionId ?? null, payment: body,
              ...correctionMetadata(body),
            } })
            if (!data) throw new Error('검증 결과를 확인하지 못했습니다.')
            return data
          }}
        /></Suspense> : <p className="empty-state">이 예외와 연결된 수정 가능한 지급 건이 없습니다.</p>}
        </>}
      </>}
    </Modal>
    <Modal open={discard} title="작성 중인 지급 수정을 닫을까요?" onClose={() => setDiscard(false)} footer={<>
      <Button data-guide-feature="features.exception.payment-correction.continue" variant="secondary" onClick={() => setDiscard(false)}>계속 작성</Button>
      <Button data-guide-feature="features.exception.payment-correction.discard" onClick={onClose}>입력 버리고 닫기</Button>
    </>}><p>아직 저장하지 않은 지급 건 수정 내용이 있습니다.</p></Modal>
  </>
}
