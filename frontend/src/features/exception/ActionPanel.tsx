import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { SaveGuide } from '../assistant/guide/save-guide'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, useEffect, useRef, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { useUnsavedWork } from '../../stores/unsavedWork'
import type { FormEvent } from 'react'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { StatusBadge } from '../../components/StatusBadge'
import { date, errorText, rateText } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { useAuth, usePermission } from '../auth/useAuth'
import { Link } from 'react-router'
import { applyActionToCache, exceptionOptionsQueryOptions, postAction } from './api'
import type { ActionInput, ExceptionCase } from './api'
import { availableActions, isActionable, severityTone, statusTone, typeTone } from './rules'
import { sourceReference } from './labels'
import { JournalCorrectionModal } from './JournalCorrectionModal'
import { decisionReasonText } from '../arbitrage/utils'

const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))

export function ExceptionDetail({ item }: { item: ExceptionCase }) {
  const reference = sourceReference(item.sourceEntityType, item.contractNo)
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
      <div className="exception-detail-wide">
        <dt>제목</dt>
        <dd>{rateText(dash(item.title))}</dd>
      </div>
      <div className="exception-detail-wide">
        <dt>설명</dt>
        {/* 차익거래 예외는 판정 사유 원문을 설명으로 저장한다. 개발 용어 구절만 쉬운 말로 바꾼다. */}
        <dd>{rateText(decisionReasonText(item.description))}</dd>
      </div>
      <div className="exception-detail-wide" data-guide-target="exception.reference">
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
        <div className="exception-detail-wide" data-guide-target="exception.basis">
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

function ActionForm({ item, helpContainer }: { item: ExceptionCase; helpContainer: HTMLElement | null }) {
  const client = useQueryClient()
  const { user } = useAuth()
  const options = useQuery(exceptionOptionsQueryOptions())
  const [actionType, setActionType] = useState('')
  const [assigneeId, setAssigneeId] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [error, setError] = useState('')
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const [saveGuide] = useState(() => new SaveGuide())
  const saved = useSyncExternalStore(saveGuide.subscribe, saveGuide.getSnapshot).savedId !== null
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const actions = availableActions(item.status, item.type).filter(action => action.type === 'ASSIGN' ||
    ((action.type === 'START_REVIEW' || action.type === 'REOPEN') &&
      (!item.assigneeLoginId || item.assigneeLoginId === user?.loginId) &&
      (item.status !== 'IN_REVIEW' || !item.assigneeLoginId)))
  const assignableUsers = (options.data?.assignableUsers ?? []).filter(assignee => assignee.loginId !== user?.loginId)
  const selectedAssignee = assignableUsers.find(assignee => String(assignee.userId) === assigneeId)
  const valid = actions.some(action => action.type === actionType) && Boolean(reason.trim()) &&
    (actionType !== 'ASSIGN' || Boolean(selectedAssignee))
  const context = JSON.stringify([item.exceptionCaseId, item.status, actionType, assigneeId, reason, evidenceRef])
  useEffect(() => { saveGuide.update(context, valid) }, [saveGuide, context, valid])
  const clearUnsaved = useUnsavedWork(Boolean(actionType || reason || evidenceRef), JSON.stringify([actionType, assigneeId, reason, evidenceRef]), `exception:${item.exceptionCaseId}:`)
  const mutation = useMutation({
    mutationKey: ['exception', 'write', item.exceptionCaseId],
    mutationFn: (input: ActionInput & { assigneeLoginId?: string }) => { saveGuide.begin(); return postAction(item.exceptionCaseId!, input) },
    meta: { errorToast: false },
    onSuccess: async (action, input) => {
      clearUnsaved()
      try {
        if (action.actionType !== input.actionType || action.fromStatus !== item.status || !['NEW', 'IN_REVIEW', 'RESOLVED', 'REJECTED'].includes(action.toStatus ?? '') || !Number.isSafeInteger(action.actionSeq) || Number(action.actionSeq) <= 0) throw new Error('처리 결과를 확인하지 못했습니다. 처리 이력을 확인하세요.')
        if (mounted.current) await saveGuide.saved(Number(action.actionSeq))
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
      applyActionToCache(client, item.exceptionCaseId!, action, action.actionType === 'ASSIGN' ? {
        assignedTo: input.assignedTo,
        assigneeLoginId: input.assigneeLoginId,
      } : {})
      if (!mounted.current) return
      setActionType('')
      setAssigneeId('')
      setReason('')
      setEvidenceRef('')
      toast(action.actionType === 'ASSIGN' ? '선택한 담당자의 검토중으로 배정했습니다.' : '검토를 시작했습니다. 아래 예외 건 처리에서 원인을 수정하세요.', 'success')
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
    if (valid) mutation.mutate({ actionType, reason, evidenceRef, assignedTo: selectedAssignee?.userId, assigneeLoginId: selectedAssignee?.loginId })
  }
  return (
    <form data-guide-feature="features.exception.action-panel.form" data-guide-scope="exception-action" className="exception-action-form" onSubmit={submit} aria-label="예외 처리">
      {helpContainer && createPortal(<ScreenHelp screenId="EXCP-W01" variant="action" saveGuide={saveGuide} outcome="processing" />, helpContainer)}
      <fieldset data-guide-target="exception.actionInput" disabled={mutation.isPending || needsRefresh} style={{ border: 0, padding: 0, margin: 0, display: 'grid', gap: 'var(--space-3)' }}>
      <Field label="조치" required>
        <select data-guide-feature="features.exception.action-panel.select" className="select-control" value={actionType} onChange={(event) => {
          setActionType(event.target.value)
          setAssigneeId('')
        }}>
          <option value="">조치를 선택하세요</option>
          {actions.map((action) => (
            <option key={action.type} value={action.type}>
              {action.label}
            </option>
          ))}
        </select>
      </Field>
      {actionType === 'ASSIGN' && <Field label="배정 담당자" required
        error={options.isError ? '담당자를 불러오지 못했습니다. 화면을 다시 열어 주세요.' : undefined}
        helper={options.isPending ? '담당자를 불러오는 중입니다.' : undefined}>
        <select data-guide-feature="features.exception.action-panel.assignee" className="select-control" value={assigneeId} onChange={event => setAssigneeId(event.target.value)}>
          <option value="">담당자를 선택하세요</option>
          {assignableUsers.map(assignee => <option key={assignee.userId} value={assignee.userId}>
            {assignee.loginId}
          </option>)}
        </select>
      </Field>}
      {actionType && <Field label="처리 사유" required>
        <textarea data-guide-feature="features.exception.action-panel.reason" className="textarea-control" rows={2}
          maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} />
      </Field>}
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

export function ActionPanel({ item, helpContainer }: { item: ExceptionCase; helpContainer: HTMLElement | null }) {
  const canHandle = usePermission('canHandleException')
  const [historyOpen, setHistoryOpen] = useState(false)
  const deletedRejectedPayment = item.status === 'REJECTED' && item.sourceDeleted === true
  const actionable = isActionable(item.status) && !deletedRejectedPayment
  return (
    <>
      {actionable && canHandle && <ActionForm item={item} helpContainer={helpContainer} />}
      {(!actionable || !canHandle) && helpContainer && createPortal(<ScreenHelp screenId="EXCP-W01" variant={canHandle ? 'action' : 'reader'} />, helpContainer)}
      {actionable && !canHandle && <p className="text-secondary exception-action-notice">조회 전용 권한입니다.</p>}
      {!actionable && <p className="text-secondary exception-action-notice">종결된 예외입니다.</p>}
      {canHandle && item.type === 'JOURNAL_CORRECTION_REQUIRED' && item.status === 'RESOLVED' && <>
        <Button data-guide-feature="features.exception.action-panel.journal-history" variant="secondary" onClick={() => setHistoryOpen(true)}>원장 정정 확인</Button>
        <JournalCorrectionModal item={item} open={historyOpen} onClose={() => setHistoryOpen(false)} />
      </>}
    </>
  )
}
