import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { errorText, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import {
  applyActionToCache,
  journalAccountsQueryOptions,
  journalDetailQueryOptions,
  postJournalCorrection,
} from './api'
import type { ExceptionAction, ExceptionCase, JournalAccount, JournalCorrectionResult, JournalDetail } from './api'

interface DraftLine {
  key: number
  originalLineNo: number | null
  accountCode: string
  debitAmount: string
  creditAmount: string
  lineDescription: string
}

let lineKey = 0
const newLine = (partial: Partial<DraftLine> = {}): DraftLine => ({
  key: ++lineKey,
  originalLineNo: null,
  accountCode: '',
  debitAmount: '0',
  creditAmount: '0',
  lineDescription: '',
  ...partial,
})

function OriginalJournal({ journal }: { journal: JournalDetail }) {
  return (
    <>
      <div className="journal-correction-original">
        <strong>
          {journal.journalNo} · {journal.journalTypeLabel}
        </strong>
        <span className="text-secondary">분개일 {journal.journalDate}</span>
        <span className="text-secondary">
          차변 {won(journal.debitTotal)} · 대변 {won(journal.creditTotal)}
        </span>
        <span className="text-secondary">원장상태 {journal.statusLabel}</span>
        <strong className={`journal-correction-validation ${journal.balanced ? 'is-valid' : 'is-invalid'}`}>
          {journal.balanced ? '차변·대변 검증 통과' : '차변·대변 검증 필요'}
        </strong>
        {journal.correctionGroupKey && <span className="text-secondary">정정그룹 {journal.correctionGroupKey}</span>}
        {journal.reversedByJournalHeaderId && (
          <Link className="button button-ghost" to={`/journals?selected=${journal.reversedByJournalHeaderId}`}>
            역분개 #{journal.reversedByJournalHeaderId}
          </Link>
        )}
        {journal.repostedJournalHeaderId && (
          <Link className="button button-ghost" to={`/journals?selected=${journal.repostedJournalHeaderId}`}>
            재기표 #{journal.repostedJournalHeaderId}
          </Link>
        )}
      </div>
      <div className="journal-correction-original-lines" role="table" aria-label="원분개 라인">
        {['라인', '계정과목', '차변', '대변', '설명'].map((heading) => (
          <strong key={heading} className="journal-correction-original-line is-heading" role="columnheader">
            {heading}
          </strong>
        ))}
        {(journal.lines ?? []).flatMap((line) => [
          <span key={`${line.lineNo}-no`} className="journal-correction-original-line">
            {line.lineNo}
          </span>,
          <span key={`${line.lineNo}-account`} className="journal-correction-original-line">
            {line.accountCode} · {line.accountName ?? '-'}
          </span>,
          <span key={`${line.lineNo}-debit`} className="journal-correction-original-line">
            {won(line.debitAmount)}
          </span>,
          <span key={`${line.lineNo}-credit`} className="journal-correction-original-line">
            {won(line.creditAmount)}
          </span>,
          <span key={`${line.lineNo}-memo`} className="journal-correction-original-line">
            {line.memo || '-'}
          </span>,
        ])}
      </div>
    </>
  )
}

function toAction(item: ExceptionCase, result: JournalCorrectionResult): ExceptionAction {
  return {
    actionSeq: result.actionSeq,
    fromStatus: result.fromStatus,
    toStatus: result.toStatus,
    actionType: result.actionType,
    actionTypeLabel: '정정',
    fromStatusLabel: item.statusLabel,
    toStatusLabel: '해결',
    reason: result.reason,
    evidenceRef: result.evidenceRef,
    actionBy: result.actionBy,
    actionByLoginId: result.actionByLoginId,
    actionAt: result.actionAt,
  }
}

export function JournalCorrectionModal({
  item,
  open,
  onClose,
}: {
  item: ExceptionCase
  open: boolean
  onClose: () => void
}) {
  const editable = item.status === 'IN_REVIEW'
  const journalQuery = useQuery({
    ...journalDetailQueryOptions(item.sourceEntityId ?? ''),
    enabled: open && Boolean(item.sourceEntityId),
  })
  const accountsQuery = useQuery({ ...journalAccountsQueryOptions(), enabled: open && editable })
  const journal = journalQuery.data
  return (
    <Modal
      open={open}
      title="원분개 역분개 · 신규 재기표"
      onClose={onClose}
      closeOnBackdrop
      className="modal-large journal-correction-modal"
      footer={
        <>
          <p className="text-secondary">정정 실행 전 원분개와 신규 분개의 차변·대변을 확인하세요.</p>
          <Button variant="secondary" onClick={onClose}>
            닫기
          </Button>
        </>
      }
    >
      {journal ? (
        <CorrectionBody item={item} journal={journal} accounts={accountsQuery.data ?? []} />
      ) : (
        <div className="journal-correction-panel-body">
          {journalQuery.error ? (
            <p className="field-error" role="alert">
              {errorText(journalQuery.error as Error, '원분개를 불러오지 못했습니다.')}
            </p>
          ) : (
            <p className="text-secondary">원분개를 불러오는 중입니다.</p>
          )}
        </div>
      )}
    </Modal>
  )
}

// 원분개가 도착한 뒤에만 마운트되므로 신규 입력의 초기값을 원분개에서 바로 채운다.
function CorrectionBody({
  item,
  journal,
  accounts,
}: {
  item: ExceptionCase
  journal: JournalDetail
  accounts: JournalAccount[]
}) {
  const client = useQueryClient()
  const editable = item.status === 'IN_REVIEW'
  const [journalDate, setJournalDate] = useState(journal.journalDate ?? '')
  const [description, setDescription] = useState(journal.description ?? '')
  const [lines, setLines] = useState<DraftLine[]>(() =>
    (journal.lines ?? []).map((line) =>
      newLine({
        originalLineNo: line.lineNo ?? null,
        accountCode: line.accountCode ?? '',
        debitAmount: String(line.debitAmount ?? 0),
        creditAmount: String(line.creditAmount ?? 0),
        lineDescription: line.memo ?? '',
      }),
    ),
  )
  const [reason, setReason] = useState('')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [error, setError] = useState('')
  const [result, setResult] = useState<JournalCorrectionResult | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      postJournalCorrection(item.exceptionCaseId!, {
        reason: reason.trim(),
        evidenceRef: evidenceRef.trim() || undefined,
        journalDate,
        description: description.trim(),
        lines: lines.map((line) => ({
          originalLineNo: line.originalLineNo ?? undefined,
          accountCode: line.accountCode,
          debitAmount: Number(line.debitAmount),
          creditAmount: Number(line.creditAmount),
          lineDescription: line.lineDescription.trim() || undefined,
        })),
      }),
    meta: { errorToast: false },
    onSuccess: (done) => {
      applyActionToCache(client, item.exceptionCaseId!, toAction(item, done))
      void client.invalidateQueries({ queryKey: ['journals'] })
      setResult(done)
      toast('원장 정정을 완료했습니다.', 'success')
    },
    onError: (caught) => {
      const message = errorText(caught as Error, '원장 정정을 완료하지 못했습니다.')
      setError(message)
      toast(message, 'error')
    },
  })

  function patchLine(key: number, patch: Partial<DraftLine>) {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)))
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    mutation.mutate()
  }

  const readOnly = !editable || result !== null
  return (
    <form className="journal-correction-form journal-correction-modal-form" onSubmit={submit}>
      <div className="journal-correction-guidance">
        원분개는 직접 수정하지 않습니다. 아래 신규 분개가 검증을 통과하면 역분개와 재기표를 한 번에 처리합니다.
      </div>
      <div className="journal-correction-compare-grid">
        <section className="surface journal-correction-panel" aria-label="원분개 읽기 전용">
          <header className="surface-header journal-correction-panel-header">
            <h3 className="surface-title journal-correction-section-title">원분개 (읽기 전용)</h3>
          </header>
          <div className="journal-correction-panel-body">
            <OriginalJournal journal={journal} />
          </div>
        </section>
        {!readOnly && (
          <section className="surface journal-correction-panel" aria-label="신규 재기표 입력">
            <header className="surface-header journal-correction-panel-header">
              <h3 className="surface-title journal-correction-section-title">신규 재기표 입력</h3>
            </header>
            <div className="journal-correction-panel-body journal-correction-inputs">
              <Field label="새 분개일" required>
                <input type="date" value={journalDate} onChange={(event) => setJournalDate(event.target.value)} />
              </Field>
              <Field label="새 분개 설명" required>
                <input
                  maxLength={1000}
                  placeholder="올바른 분개 내용을 입력하세요."
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </Field>
              <div className="journal-correction-lines">
                {lines.map((line, index) => (
                  <div
                    className="journal-correction-line"
                    key={line.key}
                    role="group"
                    aria-label={`${index + 1}번 라인`}
                  >
                    <strong className="journal-correction-line__title">{index + 1}번 라인</strong>
                    <Button
                      variant="ghost"
                      className="journal-correction-line__remove"
                      onClick={() => setLines((current) => current.filter((candidate) => candidate.key !== line.key))}
                    >
                      라인 삭제
                    </Button>
                    <Field label="계정과목" required>
                      <select
                        className="select-control"
                        value={line.accountCode}
                        onChange={(event) => patchLine(line.key, { accountCode: event.target.value })}
                      >
                        <option value="">계정과목 선택</option>
                        {accounts.map((account) => (
                          <option key={account.accountCode} value={account.accountCode}>
                            {account.accountCode} · {account.accountName}
                          </option>
                        ))}
                      </select>
                    </Field>
                    <Field label="차변" required>
                      <input
                        type="number"
                        min="0"
                        max="9999999999999"
                        step="1"
                        value={line.debitAmount}
                        onChange={(event) => patchLine(line.key, { debitAmount: event.target.value })}
                      />
                    </Field>
                    <Field label="대변" required>
                      <input
                        type="number"
                        min="0"
                        max="9999999999999"
                        step="1"
                        value={line.creditAmount}
                        onChange={(event) => patchLine(line.key, { creditAmount: event.target.value })}
                      />
                    </Field>
                    <Field label="라인 설명">
                      <input
                        maxLength={500}
                        value={line.lineDescription}
                        onChange={(event) => patchLine(line.key, { lineDescription: event.target.value })}
                      />
                    </Field>
                  </div>
                ))}
              </div>
              <Button
                variant="secondary"
                className="journal-correction-add-line"
                onClick={() => setLines((current) => [...current, newLine()])}
              >
                라인 추가
              </Button>
            </div>
          </section>
        )}
        {readOnly && (
          <section
            className="surface journal-correction-panel journal-correction-read-only"
            aria-label="원장 정정 처리 결과"
          >
            <header className="surface-header journal-correction-panel-header">
              <h3 className="surface-title journal-correction-section-title">처리 결과</h3>
            </header>
            <div className="journal-correction-panel-body">
              {result ? (
                <div className="journal-correction-result" role="status">
                  <span>역분개와 재기표를 완료했습니다.</span>
                  <span className="text-secondary">정정그룹 {result.correctionGroupKey}</span>
                  <Link className="button button-ghost" to={`/journals?selected=${result.originalJournalHeaderId}`}>
                    원분개 #{result.originalJournalHeaderId}
                  </Link>
                  <Link className="button button-ghost" to={`/journals?selected=${result.reversalJournalHeaderId}`}>
                    역분개 #{result.reversalJournalHeaderId}
                  </Link>
                  <Link className="button button-ghost" to={`/journals?selected=${result.repostedJournalHeaderId}`}>
                    재기표 #{result.repostedJournalHeaderId}
                  </Link>
                </div>
              ) : item.status === 'REJECTED' ? (
                <p>오탐·반려로 종결되어 역분개와 신규 재기표는 실행되지 않았습니다.</p>
              ) : item.status === 'RESOLVED' ? (
                <p>정정이 완료된 예외입니다. 원분개의 정정그룹과 연결 분개를 확인하세요.</p>
              ) : (
                <p>검토를 시작하면 신규 분개를 입력할 수 있습니다.</p>
              )}
            </div>
          </section>
        )}
      </div>
      {!readOnly && (
        <div className="surface journal-correction-action-panel">
          <Field label="정정 사유" required>
            <textarea
              className="textarea-control"
              maxLength={1000}
              placeholder="판단 근거와 실제 정정 내용을 입력하세요."
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
          <Field label="증빙">
            <input
              type="text"
              maxLength={500}
              placeholder="문서번호 또는 링크"
              value={evidenceRef}
              onChange={(event) => setEvidenceRef(event.target.value)}
            />
          </Field>
          {error && (
            <p className="field-error exception-action-error" role="alert">
              {error}
            </p>
          )}
          <Button
            type="submit"
            className="exception-action-submit"
            loading={mutation.isPending}
            disabled={!lines.length || !journalDate || !description.trim() || !reason.trim()}
          >
            정정 실행
          </Button>
        </div>
      )}
    </form>
  )
}
