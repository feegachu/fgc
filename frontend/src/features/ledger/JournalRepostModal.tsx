import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { ApiError } from '../../lib/api/client'
import { date, errorText, int, won } from '../../lib/format'
import { useUnsavedWork } from '../../stores/unsavedWork'
import { toast } from '../../stores/toasts'
import { journalAccountsQueryOptions } from '../exception/api'
import { validateCorrectionLines } from '../exception/correctionLines'
import { accountDisplayName } from './accountName'
import { repostNumberQuery, reverseAndRepostJournal } from './api'
import type { JournalDetail } from './api'
import { invalidateJournalCorrectionQueries } from './correction-cache'

export function JournalRepostModal({ detail, allowed, onClose, onComplete, reconciliationSummary }: {
  detail: JournalDetail; allowed: boolean; onClose: () => void; onComplete?: () => void; reconciliationSummary?: string
}) {
  const client = useQueryClient()
  const accounts = useQuery(journalAccountsQueryOptions())
  const numbers = useQuery(repostNumberQuery(detail.journalDate))
  const original = detail.lines ?? []
  const [lines, setLines] = useState(() => original.map(line => ({ originalLineNo: line.lineNo,
    accountCode: line.accountCode ?? '', debitAmount: String(line.debitAmount ?? 0), creditAmount: String(line.creditAmount ?? 0), lineDescription: line.memo ?? '' })))
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [discard, setDiscard] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const initial = JSON.stringify(original.map(line => ({ originalLineNo: line.lineNo, accountCode: line.accountCode ?? '',
    debitAmount: String(line.debitAmount ?? 0), creditAmount: String(line.creditAmount ?? 0), lineDescription: line.memo ?? '' })))
  const dirty = Boolean(reason || evidence || JSON.stringify(lines) !== initial)
  const clear = useUnsavedWork(dirty, JSON.stringify([lines, reason, evidence]))
  const invalid = validateCorrectionLines(lines)
  const debitTotal = original.reduce((sum, row) => sum + Number(row.creditAmount ?? 0), 0) + lines.reduce((sum, row) => sum + Number(row.debitAmount || 0), 0)
  const creditTotal = original.reduce((sum, row) => sum + Number(row.debitAmount ?? 0), 0) + lines.reduce((sum, row) => sum + Number(row.creditAmount || 0), 0)
  const mutation = useMutation({
    mutationKey: ['journals', 'repost', detail.journalHeaderId],
    mutationFn: () => reverseAndRepostJournal(detail.journalHeaderId!, { reason: reason.trim(), evidenceRef: evidence.trim() || undefined,
      journalDate: detail.journalDate!, description: detail.description?.trim() || `${detail.journalNo} 지급 기록 정정`,
      lines: lines.map(line => ({ ...line, debitAmount: Number(line.debitAmount), creditAmount: Number(line.creditAmount) })) }),
    meta: { errorToast: false },
    onSuccess: () => { clear(); toast('원장을 역분개·재기표하고 지급 기록에 반영했습니다.', 'success'); void invalidateJournalCorrectionQueries(client); onClose(); onComplete?.() },
    onError: error => {
      if (!(error instanceof ApiError) || error.status >= 500 || error.status === 409) {
        setUncertain(true); void invalidateJournalCorrectionQueries(client)
      }
    },
  })
  function close() { if (mutation.isPending) return; if (dirty && !uncertain) setDiscard(true); else onClose() }
  function patch(index: number, key: 'accountCode' | 'debitAmount' | 'creditAmount', value: string) {
    setLines(previous => previous.map((line, at) => at === index ? { ...line, [key]: value } : line))
  }
  return <>
    <Modal open title="역분개 + 재기표" className="modal-large journal-repost-modal" onClose={close} closeOnBackdrop={false} closeOnEscape={!mutation.isPending}
      footer={<><Button data-guide-feature="ledger.repost.close" variant="secondary" disabled={mutation.isPending} onClick={close}>닫기</Button>
        <Button data-guide-feature="ledger.repost.submit" disabled={!allowed || !detail.journalHeaderId || !detail.journalDate || Boolean(invalid) || !reason.trim() || !lines.length || lines.some(line => !line.accountCode) || !accounts.isSuccess || uncertain}
          loading={mutation.isPending} onClick={() => mutation.mutate()}>등록</Button></>}>
      <p>원분개 {detail.journalNo} · {detail.contractNo} · {won(detail.debitTotal)}</p>
      {reconciliationSummary && <p className="guidance guidance-neutral">{reconciliationSummary}</p>}
      <div className="data-table-viewport"><table className="data-table ledger-entry-table journal-repost-table" data-guide-feature="ledger.repost.table">
        <caption className="visually-hidden">원분개 · 역분개 · 신규 재기표</caption>
        <thead><tr><th>분개일 · 분개번호</th><th>계정과목</th><th>적요</th><th className="text-right">차변</th><th className="text-right">대변</th></tr></thead>
        {[{ label: '원분개', journalNo: detail.journalNo, reversal: false },
          { label: '역분개', journalNo: numbers.data?.reversalJournalNo, reversal: true }].map(group =>
          <tbody key={group.label}>{original.map((line, index) => {
            const debit = group.reversal ? line.creditAmount : line.debitAmount
            const credit = group.reversal ? line.debitAmount : line.creditAmount
            return <tr key={line.lineNo}>
              {index === 0 && <td rowSpan={original.length} className="ledger-journal-cell"><span>{date(detail.journalDate)}</span>
                <strong>{group.journalNo}</strong><div><span className="ledger-type-label">{group.label}</span><span className={detail.balanced ? 'is-balanced' : 'is-unbalanced'}>{detail.balanced ? '✓ 균형' : '불일치'}</span></div></td>}
              <td>{accountDisplayName(line.accountCode, line.accountName)}</td><td>{line.memo || detail.description || '—'}</td>
              <td className="text-right">{debit ? int(debit) : '—'}</td>
              <td className="text-right">{credit ? int(credit) : '—'}</td>
            </tr>
          })}</tbody>)}
        <tbody>{lines.map((line, index) => <tr key={index}>
          {index === 0 && <td rowSpan={lines.length} className="ledger-journal-cell"><span>{date(detail.journalDate)}</span>
            <strong>{numbers.data?.repostedJournalNo}</strong><div><span className="ledger-type-label">재기표</span>
            <span className={invalid ? 'is-unbalanced' : 'is-balanced'}>{invalid ? '불일치' : '✓ 균형'}</span></div></td>}
          <td><select data-guide-feature="ledger.repost.account" className="select-control" aria-label={`${index + 1}행 계정과목`} value={line.accountCode} disabled={mutation.isPending || uncertain}
            onChange={event => patch(index, 'accountCode', event.target.value)}>
            <option value="">계정과목 선택</option>{accounts.data?.map(account => <option key={account.accountCode} value={account.accountCode}>{accountDisplayName(account.accountCode, account.accountName)}</option>)}
          </select></td>
          <td>{line.lineDescription || detail.description || '—'}</td>
          <td><input data-guide-feature="ledger.repost.debit" className="field-control" aria-label={`${index + 1}행 차변`} type="number" min="0" step="1" value={line.debitAmount}
            disabled={mutation.isPending || uncertain} onChange={event => patch(index, 'debitAmount', event.target.value)} /></td>
          <td><input data-guide-feature="ledger.repost.credit" className="field-control" aria-label={`${index + 1}행 대변`} type="number" min="0" step="1" value={line.creditAmount}
            disabled={mutation.isPending || uncertain} onChange={event => patch(index, 'creditAmount', event.target.value)} /></td>
        </tr>)}</tbody>
        <tfoot><tr><th colSpan={3}>등록 합계</th><td>{won(debitTotal)}</td><td>{won(creditTotal)}</td></tr></tfoot>
      </table></div>
      {invalid && <p className="field-error">{invalid}</p>}
      {accounts.error && <p role="alert">{errorText(accounts.error)}</p>}
      <Field label="사유" required><textarea data-guide-feature="ledger.repost.reason" className="textarea-control" rows={3} value={reason} maxLength={1000}
        disabled={mutation.isPending || uncertain} onChange={event => setReason(event.target.value)} /></Field>
      <Field label="증빙 참조"><input data-guide-feature="ledger.repost.evidence" className="input-control" value={evidence} maxLength={500}
        disabled={mutation.isPending || uncertain} onChange={event => setEvidence(event.target.value)} /></Field>
      {mutation.error && <p role="alert">{mutation.error instanceof ApiError && typeof mutation.error.params.reason === 'string' ? mutation.error.params.reason : errorText(mutation.error)}{uncertain && ' 원장 이력을 확인한 뒤 다시 열어주세요.'}</p>}
    </Modal>
    <Modal open={discard} title="작성 중인 재기표를 닫을까요?" onClose={() => setDiscard(false)} footer={<>
      <Button data-guide-feature="ledger.repost.continue" variant="secondary" onClick={() => setDiscard(false)}>계속 작성</Button>
      <Button data-guide-feature="ledger.repost.discard" onClick={() => { clear(); onClose() }}>입력 버리고 닫기</Button>
    </>}><p>저장하지 않은 금액과 사유를 버립니다.</p></Modal>
  </>
}
