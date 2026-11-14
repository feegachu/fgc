import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { errorText, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { usePermission } from '../auth/useAuth'
import { JournalTable } from '../ledger/LedgerExplorer'
import { toLedgerEntry } from '../ledger/explorer-api'
import type { Payment } from '../transaction/form-api'
import { applyActionToCache, journalDetailQueryOptions, type ExceptionCase } from './api'
import { ReviewPanel } from './ReviewPanel'

export function DuplicateJournalModal({ item, payments, paymentJournalIds, onChanged, onClose }: {
  item: ExceptionCase
  payments: Payment[]
  paymentJournalIds: Record<string, number>
  onChanged: () => Promise<void>
  onClose: () => void
}) {
  const client = useQueryClient()
  const canProcess = usePermission('canProcess')
  const [selectedId, setSelectedId] = useState<number>()
  const [actualDuplicate, setActualDuplicate] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const [message, setMessage] = useState('')
  const confirmed = payments.filter(payment => payment.status === 'CONFIRMED')
  const candidates = confirmed.flatMap(payment => {
    const journalId = paymentJournalIds[String(payment.commissionTransactionId)]
    return journalId == null ? [] : [{ journalId, payment }]
  })
  const journals = useQueries({ queries: candidates.map(({ journalId }) => journalDetailQueryOptions(String(journalId))) })
  const entries = candidates.flatMap(({ journalId }, index) => {
    const detail = journals[index].data
    return detail ? [toLedgerEntry(journalId, detail)] : []
  })
  const selectedIndex = candidates.findIndex(({ journalId }) => journalId === selectedId)
  const payment = candidates[selectedIndex]?.payment
  const journal = journals[selectedIndex]?.data
  const eligible = canProcess && !actualDuplicate && confirmed.length > 1 && Boolean(payment?.updatedAt)
    && journal?.status === 'POSTED' && journal.journalType !== 'REVERSAL' && !journal.reversedByJournalHeaderId
  const reversal = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: async (target: Payment) => {
      const { data } = await apiClient.request<components['schemas']['ExceptionPaymentCorrectionResponse']>(
        `/api/v1/exceptions/${item.exceptionCaseId}/payment-correction/cancel-duplicate`, { method: 'POST', body: {
          paymentId: target.commissionTransactionId, expectedUpdatedAt: target.updatedAt, evidenceRef: target.evidenceRef ?? null,
        } })
      if (!data?.message) throw new Error('역분개 결과를 확인하지 못했습니다.')
      return data
    },
    meta: { errorToast: false },
    onSuccess: async result => {
      if (result.action) applyActionToCache(client, item.exceptionCaseId!, result.action)
      void client.invalidateQueries({ queryKey: ['journals'] })
      void client.invalidateQueries({ queryKey: ['transactions'], refetchType: 'none' })
      void client.invalidateQueries({ queryKey: ['reconciliations'], refetchType: 'none' })
      toast(result.message!, result.resolved ? 'success' : 'warning')
      setConfirm(false)
      setSelectedId(undefined)
      if (result.resolved) onClose()
      else { setMessage(result.message!); await onChanged() }
    },
  })
  const loading = journals.some(query => query.isPending)
  const failed = journals.some(query => query.isError)

  return <>
    <Modal open title="검증원장 중복 지급" className="modal-large" onClose={onClose} closeOnBackdrop={false} closeOnEscape={!reversal.isPending}
      footer={<>
        {!actualDuplicate && <Button data-guide-feature="features.exception.payment-correction.cancel-duplicate" disabled={!eligible || loading || failed || reversal.isPending}
          onClick={() => { reversal.reset(); setConfirm(true) }}>역분개 후 재대사</Button>}
        <Button data-guide-feature="features.exception.duplicate-journal.close" variant="secondary" disabled={reversal.isPending} onClick={onClose}>닫기</Button>
      </>}>
      <div className="ledger-react-page">
        <p>{item.contractNo}  {item.title}</p>
        <section data-guide-feature="features.exception.payment-correction.duplicate-guide" className="exception-correction-workflow">
          <h3>중복 지급 확인</h3>
          <p>기록만 중복이면 아래 표에서 취소할 분개번호를 선택하세요. 실제로 두 번 송금했다면 환수 및 차감 근거를 남겨 반려하세요.</p>
          <Field label="중복 유형"><select data-guide-feature="features.exception.payment-correction.duplicate-mode" className="select-control"
            value={actualDuplicate ? 'ACTUAL_DUPLICATE' : 'RECORD_ONLY'} disabled={reversal.isPending}
            onChange={event => { setActualDuplicate(event.target.value === 'ACTUAL_DUPLICATE'); setSelectedId(undefined) }}>
            <option value="RECORD_ONLY">기록만 중복</option><option value="ACTUAL_DUPLICATE">실제로 중복 송금</option>
          </select></Field>
        </section>
        {loading && <p role="status">중복 지급 분개를 불러오는 중입니다.</p>}
        {failed && <p role="alert" className="field-error">중복 지급 분개를 모두 불러오지 못했습니다. 모달을 다시 열어 확인하세요.</p>}
        {entries.length > 0 && <JournalTable entries={entries} selected={String(selectedId ?? '')} paginated={false}
          onSelect={actualDuplicate || reversal.isPending ? undefined : setSelectedId} />}
        {!loading && !failed && confirmed.length > candidates.length && <p>일부 지급 건에는 역분개할 수 있는 원장 분개가 없습니다.</p>}
        {!loading && !failed && !message && confirmed.length < 2 && <p>역분개할 중복 지급이 남아 있지 않습니다.</p>}
        {payment && journal && <p role="status">선택한 분개: <strong>{journal.journalNo}</strong>  지급 {payment.sourceBusinessKey ?? payment.commissionTransactionId}  {won(payment.amount)}</p>}
        {message && <p role="status" className="exception-correction-result">{message}</p>}
        {actualDuplicate && <ReviewPanel item={item} onClosed={onClose} />}
      </div>
    </Modal>
    {confirm && payment && journal && <Modal open title="선택한 지급 분개를 역분개할까요?" onClose={() => { if (!reversal.isPending) setConfirm(false) }}
      closeOnBackdrop={false} closeOnEscape={!reversal.isPending} footer={<>
        <Button data-guide-feature="features.exception.duplicate-journal.back" variant="secondary" disabled={reversal.isPending} onClick={() => setConfirm(false)}>취소</Button>
        <Button data-guide-feature="features.exception.duplicate-journal.confirm" loading={reversal.isPending} disabled={!eligible || reversal.isPending}
          onClick={() => reversal.mutate(payment)}>역분개하고 재대사</Button>
      </>}>
      <p><strong>{journal.journalNo}</strong>  지급 {payment.sourceBusinessKey ?? payment.commissionTransactionId}  {won(payment.amount)}</p>
      <p>실제 송금은 한 번만 이루어졌는지 확인하세요. 선택한 지급 기록만 취소하고 재대사합니다.</p>
      {reversal.error && <p role="alert" className="field-error">{errorText(reversal.error, '역분개하지 못했습니다.')}</p>}
    </Modal>}
  </>
}
