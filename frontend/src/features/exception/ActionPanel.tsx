import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { StatusBadge } from '../../components/StatusBadge'
import { date, dateTime, errorText } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { usePermission } from '../auth/useAuth'
import { Link } from 'react-router'
import { applyActionToCache, postAction } from './api'
import type { ExceptionCase } from './api'
import { JournalCorrectionModal } from './JournalCorrectionModal'
import { availableActions, isActionable, JOURNAL_CORRECTION_TYPE, severityTone, statusTone, typeTone } from './rules'

const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))

function Detail({ item }: { item: ExceptionCase }) {
  const reference = `${item.sourceEntityType ?? ''}:${item.sourceEntityId ?? ''}`
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
        <dd className="tabular-nums">{item.validationMonth ? date(item.validationMonth).slice(0, 7) : '—'}</dd>
      </div>
      <div>
        <dt>최초 검출 실행</dt>
        <dd className="tabular-nums">{item.firstDetectedRunId ?? '—'}</dd>
      </div>
      <div>
        <dt>최근 검출 실행</dt>
        <dd className="tabular-nums">{item.lastDetectedRunId ?? '—'}</dd>
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
        <dd>{dash(item.title)}</dd>
      </div>
      <div className="exception-detail-wide">
        <dt>설명</dt>
        <dd>{dash(item.description)}</dd>
      </div>
      <div className="exception-detail-wide">
        <dt>참조</dt>
        <dd className="tabular-nums">
          {item.sourceLink ? (
            <Link className="button button-secondary" to={item.sourceLink}>
              <span className="material-symbols-rounded" aria-hidden="true">
                open_in_new
              </span>
              <span>{reference}</span>
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
            <Link className="button button-secondary" to={item.capBasisLink}>
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
  const actions = availableActions(item.status, item.type)
  const mutation = useMutation({
    mutationFn: () => postAction(item.exceptionCaseId!, { actionType, reason, evidenceRef }),
    meta: { errorToast: false },
    onSuccess: (action) => {
      applyActionToCache(client, item.exceptionCaseId!, action)
      setActionType('')
      setReason('')
      setEvidenceRef('')
      toast('처리 내용이 저장되었습니다.', 'success')
      // 원장 정정 예외는 검토 시작(재검토)에 성공한 직후 정정 모달을 바로 연다.
      if (
        item.type === JOURNAL_CORRECTION_TYPE &&
        (action.actionType === 'START_REVIEW' || action.actionType === 'REOPEN') &&
        action.toStatus === 'IN_REVIEW'
      ) {
        onStarted()
      }
    },
    onError: (caught) => {
      const message = errorText(caught as Error, '처리 내용을 저장하지 못했습니다.')
      setError(message)
      toast(message, 'error')
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    mutation.mutate()
  }
  return (
    <form className="exception-action-form" onSubmit={submit} aria-label="예외 처리">
      <Field label="조치" required>
        <select className="select-control" value={actionType} onChange={(event) => setActionType(event.target.value)}>
          <option value="">조치를 선택하세요</option>
          {actions.map((action) => (
            <option key={action.type} value={action.type}>
              {action.label}
            </option>
          ))}
        </select>
      </Field>
      <Field label="처리 사유" required>
        <textarea
          className="textarea-control"
          maxLength={1000}
          placeholder="판단 근거와 처리 내용을 입력하세요."
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      </Field>
      <Field label="증빙">
        <input
          type="text"
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
        disabled={!actionType || !reason.trim()}
      >
        처리 저장
      </Button>
    </form>
  )
}

export function ActionPanel({ item }: { item: ExceptionCase }) {
  const canHandle = usePermission('canHandleException')
  const [correctionOpen, setCorrectionOpen] = useState(false)
  const actionable = isActionable(item.status)
  const isCorrection = item.type === JOURNAL_CORRECTION_TYPE
  return (
    <>
      <Detail item={item} />
      {actionable && canHandle && <ActionForm item={item} onStarted={() => setCorrectionOpen(true)} />}
      {isCorrection && canHandle && item.status !== 'NEW' && (
        <Button variant="secondary" className="journal-correction-open" onClick={() => setCorrectionOpen(true)}>
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
