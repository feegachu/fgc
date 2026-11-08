import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { SaveGuide } from '../assistant/guide/save-guide'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useEffect, useRef, useSyncExternalStore } from 'react'
import { useUnsavedWork } from '../../stores/unsavedWork'
import type { FormEvent } from 'react'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { StatusBadge } from '../../components/StatusBadge'
import { date, dateTime, errorText, rateText } from '../../lib/format'
import { monthText } from '../../lib/screenText'
import { toast } from '../../stores/toasts'
import { usePermission } from '../auth/useAuth'
import { Link } from 'react-router'
import { applyActionToCache, postAction } from './api'
import type { ExceptionCase } from './api'
import { JournalCorrectionModal } from './JournalCorrectionModal'
import { availableActions, isActionable, JOURNAL_CORRECTION_TYPE, severityTone, statusTone, typeTone } from './rules'
import { sourceReference } from './labels'
import { decisionReasonText } from '../arbitrage/utils'

const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))

function Detail({ item }: { item: ExceptionCase }) {
  const reference = sourceReference(item.sourceEntityType, item.contractNo)
  const runText = (runId?: number | null) => {
    const run = item.occurrences?.find((occurrence) => occurrence.validationRunId === runId)
    return run ? `${monthText(run.validationMonth)} ${run.runNo}차 실행` : '-'
  }
  return (
    <dl className="exception-detail-grid">
      <div>
        <dt>예외 번호</dt>
        <dd className="tabular-nums">{item.exceptionCaseId}</dd>
      </div>
      <div>
        <dt>상태</dt>
        <dd>
          <StatusBadge tone={statusTone(item.status)}>{item.statusLabel ?? '-'}</StatusBadge>
        </dd>
      </div>
      <div>
        <dt>유형</dt>
        <dd>
          <StatusBadge tone={typeTone(item.type)}>{item.typeLabel ?? '-'}</StatusBadge>
        </dd>
      </div>
      <div>
        <dt>상세 원인</dt>
        <dd>{dash(item.reasonLabel)}</dd>
      </div>
      <div>
        <dt>심각도</dt>
        <dd>
          <StatusBadge tone={severityTone(item.severity)}>{item.severityLabel ?? '-'}</StatusBadge>
        </dd>
      </div>
      <div>
        <dt>계약번호</dt>
        <dd className="tabular-nums">{dash(item.contractNo)}</dd>
      </div>
      <div>
        <dt>담당자</dt>
        <dd>{item.assigneeLoginId ?? '미배정'}</dd>
      </div>
      <div>
        <dt>설계사</dt>
        <dd>{dash(item.agentName)}</dd>
      </div>
      <div>
        <dt>검증월</dt>
        <dd className="tabular-nums">{item.validationMonth ? date(item.validationMonth).slice(0, 7) : '-'}</dd>
      </div>
      <div>
        <dt>최초 검출 실행</dt>
        <dd className="tabular-nums">{runText(item.firstDetectedRunId)}</dd>
      </div>
      <div>
        <dt>최근 검출 실행</dt>
        <dd className="tabular-nums">{runText(item.lastDetectedRunId)}</dd>
      </div>
      <div>
        <dt>검출 횟수</dt>
        <dd className="tabular-nums">{item.detectionCount}회</dd>
      </div>
      <div>
        <dt>최초 검출</dt>
        <dd className="tabular-nums">{dateTime(item.firstDetectedAt)}</dd>
      </div>
      <div>
        <dt>최근 검출</dt>
        <dd className="tabular-nums">{dateTime(item.lastDetectedAt)}</dd>
      </div>
      <div className="exception-detail-wide">
        <dt>제목</dt>
        <dd>{rateText(dash(item.title))}</dd>
      </div>
      <div className="exception-detail-wide">
        <dt>설명</dt>
        {/* 차익거래 예외는 판정 사유 원문을 설명으로 저장한다. 개발 용어 구절만 쉬운 말로 바꾼다. */}
        <dd>{rateText(decisionReasonText(item.description))}</dd>
      </div>
      <div className="exception-detail-wide">
        <dt>참조</dt>
        <dd className="tabular-nums">
          {item.sourceLink ? (
            <Link data-guide-feature="features.exception.action-panel.link" className="button button-secondary" to={item.sourceLink}>
              <span className="material-symbols-rounded" aria-hidden="true">
                open_in_new
              </span>
              <span>{reference} 열기</span>
            </Link>
          ) : (
            reference
          )}
        </dd>
      </div>
      {item.capBasisLink && (
        <div className="exception-detail-wide">
          <dt>계산근거</dt>
          <dd>
            <Link data-guide-feature="features.exception.action-panel.link-2" className="button button-secondary" to={item.capBasisLink}>
              <span className="material-symbols-rounded" aria-hidden="true">
                calculate
              </span>
              <span>1,200% 계산근거 열기</span>
            </Link>
          </dd>
        </div>
      )}
    </dl>
  )
}

function ActionForm({ item, onStarted }: { item: ExceptionCase; onStarted: () => void }) {
  const client = useQueryClient()
  const [actionType, setActionType] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [error, setError] = useState('')
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const [saveGuide] = useState(() => new SaveGuide())
  const saved = useSyncExternalStore(saveGuide.subscribe, saveGuide.getSnapshot).savedId !== null
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const actions = availableActions(item.status, item.type)
  const valid = actions.some(action => action.type === actionType) && Boolean(reason.trim()) && reason.trim().length <= 1000
  const context = JSON.stringify([item.exceptionCaseId, item.status, actionType, reason, evidenceRef])
  useEffect(() => { saveGuide.update(context, valid) }, [saveGuide, context, valid])
  const clearUnsaved = useUnsavedWork(Boolean(actionType || reason || evidenceRef), JSON.stringify([actionType, reason, evidenceRef]))
  const mutation = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: () => { saveGuide.begin(); return postAction(item.exceptionCaseId!, { actionType, reason, evidenceRef }) },
    meta: { errorToast: false },
    onSuccess: async (action) => {
      clearUnsaved()
      let continueGuide: boolean
      try {
        if (action.actionType !== actionType || action.fromStatus !== item.status || !['NEW', 'IN_REVIEW', 'RESOLVED', 'REJECTED'].includes(action.toStatus ?? '') || !Number.isSafeInteger(action.actionSeq) || Number(action.actionSeq) <= 0) throw new Error('처리 결과를 확인하지 못했습니다. 처리 이력을 확인하세요.')
        continueGuide = mounted.current ? await saveGuide.saved(Number(action.actionSeq)) : false
      } catch {
        // The POST already succeeded: never expose a retry for an unverified receipt.
        const message = '저장은 되었지만 결과를 표시하지 못했습니다. 다시 저장하지 말고 처리 이력을 확인한 뒤 화면을 다시 여세요.'
        if (mounted.current) {
          setNeedsRefresh(true)
          saveGuide.fail(message)
          setError(message)
          toast(message, 'warning')
        }
        void client.invalidateQueries({ queryKey: ['exceptions', 'search'] })
        return
      }
      applyActionToCache(client, item.exceptionCaseId!, action)
      if (!mounted.current) return
      setActionType('')
      setReason('')
      setEvidenceRef('')
      toast('처리 내용이 저장되었습니다.', 'success')
      // 원장 정정 예외는 검토 시작(재검토)에 성공한 직후 정정 모달을 바로 연다.
      if (
        continueGuide && item.type === JOURNAL_CORRECTION_TYPE &&
        (action.actionType === 'START_REVIEW' || action.actionType === 'REOPEN') &&
        action.toStatus === 'IN_REVIEW'
      ) {
        onStarted()
      }
    },
    onError: (caught) => {
      const message = errorText(caught as Error, '처리 내용을 저장하지 못했습니다.')
      saveGuide.fail(message)
      setError(message)
      toast(message, 'error')
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    if (needsRefresh || mutation.isPending) return
    setError('')
    if (valid) mutation.mutate()
  }
  return (
    <form data-guide-feature="features.exception.action-panel.form" data-guide-scope="exception-action" className="exception-action-form" onSubmit={submit} aria-label="예외 처리">
      <ScreenHelp screenId="EXCP-W01" variant="action" saveGuide={saveGuide} outcome="processing" />
      <fieldset data-guide-target="exception.actionInput" disabled={mutation.isPending || needsRefresh} style={{ border: 0, padding: 0, margin: 0, display: 'grid', gap: 'var(--space-3)' }}>
      <Field label="조치" required>
        <select data-guide-feature="features.exception.action-panel.select" className="select-control" value={actionType} onChange={(event) => setActionType(event.target.value)}>
          <option value="">조치를 선택하세요</option>
          {actions.map((action) => (
            <option key={action.type} value={action.type}>
              {action.label}
            </option>
          ))}
        </select>
      </Field>
      <Field label="처리 사유" required>
        <textarea data-guide-feature="features.exception.action-panel.textarea"
          className="textarea-control"
          maxLength={1000}
          placeholder="판단 근거와 처리 내용을 입력하세요."
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      </Field>
      <Field label="증빙">
        <input data-guide-feature="features.exception.action-panel.input"
          type="text"
          placeholder="문서번호 또는 링크"
          value={evidenceRef}
          onChange={(event) => setEvidenceRef(event.target.value)}
        />
      </Field>
      </fieldset>
      {error && (
        <p className="field-error exception-action-error" role="alert">
          {error}
        </p>
      )}
      <Button data-guide-feature="features.exception.action-panel.button"
        data-guide-target="exception.actionSave"
        type="submit"
        className="exception-action-submit"
        loading={mutation.isPending && !saved}
        disabled={!valid || mutation.isPending || needsRefresh}
      >
        처리 저장
      </Button>
    </form>
  )
}

export function ActionPanel({ item }: { item: ExceptionCase }) {
  const canHandle = usePermission('canHandleException')
  const [correctionOpen, setCorrectionOpen] = useState(false)
  const deletedRejectedPayment = item.status === 'REJECTED' && item.sourceDeleted === true
  const actionable = isActionable(item.status) && !deletedRejectedPayment
  const isCorrection = item.type === JOURNAL_CORRECTION_TYPE
  return (
    <>
      <Detail item={item} />
      {actionable && canHandle && <ActionForm item={item} onStarted={() => setCorrectionOpen(true)} />}
      {isCorrection && canHandle && item.status !== 'NEW' && (
        <Button data-guide-feature="features.exception.action-panel.button-2" variant="secondary" className="journal-correction-open" onClick={() => setCorrectionOpen(true)}>
          <span className="material-symbols-rounded" aria-hidden="true">
            receipt_long
          </span>
          <span>{item.status === 'IN_REVIEW' ? '원장 정정 계속' : '원장 정정 확인'}</span>
        </Button>
      )}
      {isCorrection && canHandle && (
        <JournalCorrectionModal item={item} open={correctionOpen} onClose={() => setCorrectionOpen(false)} />
      )}
      {actionable && !canHandle && <p className="text-secondary exception-action-notice">조회 전용 권한입니다.</p>}
      {!actionable && <p className="text-secondary exception-action-notice">종결된 예외입니다.</p>}
    </>
  )
}
