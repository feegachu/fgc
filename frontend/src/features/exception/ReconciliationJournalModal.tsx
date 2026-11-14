import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { DisabledButtonHint } from '../../components/DisabledButtonHint'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { errorText, won } from '../../lib/format'
import { usePermission } from '../auth/useAuth'
import { JournalTable } from '../ledger/LedgerExplorer'
import { DetailSummary, JournalActionModal } from '../ledger/LedgerPage'
import { toLedgerEntry } from '../ledger/explorer-api'
import type { Payment } from '../transaction/form-api'
import { journalDetailQueryOptions } from './api'
import type { ExceptionCase } from './api'

export function ReconciliationJournalModal({ item, payments, paymentJournalIds, onClose }: {
  item: ExceptionCase
  payments: Payment[]
  paymentJournalIds: Record<string, number>
  onClose: () => void
}) {
  const [selectedId, setSelectedId] = useState('')
  const [requestOpen, setRequestOpen] = useState(false)
  const paymentId = selectedId || String(payments[0]?.commissionTransactionId ?? '')
  const journalId = paymentJournalIds[paymentId]
  const detail = useQuery({ ...journalDetailQueryOptions(String(journalId ?? '')), enabled: Boolean(journalId) })
  const canReverse = usePermission('canReverseJournal')
  const journal = detail.data
  const eligible = journal?.status === 'POSTED' && journal.journalType !== 'REVERSAL' && !journal.reversedByJournalHeaderId
  return <>
    <Modal open title="검증원장 지급 기록 정정" className="modal-large" onClose={onClose}
      closeOnBackdrop={false} footer={<>
        <DisabledButtonHint disabled={!canReverse || !eligible} reason={!canReverse ? '원장 정정 권한이 없습니다.' : '정정 가능한 기표 원장이 없습니다.'}>
          <Button data-guide-feature="features.exception.reconciliation-journal.correction" disabled={!canReverse || !eligible}
            onClick={() => setRequestOpen(true)}>역분개 + 재기표</Button>
        </DisabledButtonHint>
        <Button data-guide-feature="features.exception.reconciliation-journal.close" variant="secondary" onClick={onClose}>닫기</Button>
      </>}>
      <div className="ledger-react-page">
      <p>{item.contractNo}  {item.title}</p>
      <p className="guidance guidance-neutral">기록 오류는 기존 지급 원장을 역분개하고 올바른 금액으로 재기표하세요. 실제로 덜 주거나 더 줬다면 추가 송금 및 반환을 확인한 뒤 정정하세요.</p>
      {payments.length > 1 && <Field label="정정할 지급 건"><select className="select-control" value={paymentId}
        data-guide-feature="features.exception.reconciliation-journal.payment" onChange={event => setSelectedId(event.target.value)}>
        {payments.map(payment => <option key={payment.commissionTransactionId} value={payment.commissionTransactionId}>
          지급 {payment.commissionTransactionId}  {payment.commissionItemName ?? payment.commissionItemCode}  {won(payment.amount)}
        </option>)}
      </select></Field>}
      {!journalId && <p role="status">이 지급 건에 연결된 정정 가능한 기표 원장이 없습니다. 지급 상태와 원장 생성 여부를 확인하세요.</p>}
      {journalId && detail.isPending && <p role="status">지급 원장을 불러오는 중입니다.</p>}
      {detail.error && <p role="alert">{errorText(detail.error, '지급 원장을 불러오지 못했습니다.')}</p>}
      {journal && journalId != null && <>
        <JournalTable entries={[toLedgerEntry(journalId, journal)]} selected={String(journalId)} paginated={false} />
        <DetailSummary detail={journal} />
      </>}
      </div>
    </Modal>
    {requestOpen && journal && <JournalActionModal action="correction" detail={journal} allowed={canReverse && Boolean(eligible)}
      onClose={() => setRequestOpen(false)} onReposted={onClose} reconciliationSummary={item.title} />}
  </>
}
