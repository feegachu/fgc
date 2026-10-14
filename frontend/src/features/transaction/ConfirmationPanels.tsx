import { useRef } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { isNegative, usageRate, won } from '../../lib/format'
import { withMonth } from '../../stores/workspace'
import type { ApiError } from '../../lib/api/client'
import type { CapPreview, Precheck } from './form-api'
import { gateStates, hasRecordableBlocker, isRetryableConfirmation, RECORDABLE_CODES } from './form-model'
import type { FormValues } from './form-model'

export function GatePanel({ result }: { result: Precheck | null }) {
  return (
    <section className="surface transaction-gate-panel" aria-labelledby="transaction-gate-title">
      <header className="surface-header transaction-surface-header">
        <h2 className="surface-title" id="transaction-gate-title">
          ③ 확정 게이트 6단계
        </h2>
        <span className="transaction-panel-meta">운영정책서 제31조</span>
      </header>
      <div className="surface-body transaction-gate-list" id="gate-list" aria-live="polite">
        {gateStates(result).map((gate, index) => (
          <div key={gate.label} className={`transaction-gate-step transaction-gate-${gate.state}`}>
            <span className="material-symbols-rounded transaction-gate-icon" aria-hidden="true">
              {gate.state === 'pass' ? 'check' : gate.state === 'fail' ? 'close' : 'more_horiz'}
            </span>
            <div>
              <div className="transaction-gate-label">
                {index + 1}. {gate.label} · {gate.state === 'pass' ? '통과' : gate.state === 'fail' ? '차단' : '미확인'}
              </div>
              <div className="transaction-gate-detail">
                {gate.failures.length
                  ? gate.failures.map((blocker) => blocker.message || blocker.code).join(' / ')
                  : gate.state === 'pass'
                    ? '서버 사전검증 결과 통과했습니다.'
                    : result
                      ? '앞 단계가 막혀 아직 판정하지 않았습니다.'
                      : '사전검증 후 서버 판정이 표시됩니다.'}
              </div>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
function AmountLine({ label, value, emphasize = false }: { label: string; value: unknown; emphasize?: boolean }) {
  return (
    <div className={`transaction-cap-amount-line${emphasize ? ' is-emphasized' : ''}`}>
      <span>{label}</span>
      <span className={isNegative(value) ? 'is-negative-amount' : undefined}>{won(value)}</span>
    </div>
  )
}
function CapCard({ preview }: { preview: CapPreview }) {
  const status = preview.resultStatus ?? 'REVIEW_REQUIRED'
  const tone: Record<string, StatusTone> = {
    NORMAL: 'success',
    WARNING: 'warning',
    VIOLATION: 'error',
    REVIEW_REQUIRED: 'review',
  }
  const bar: Record<string, string> = { NORMAL: 'normal', WARNING: 'warning', VIOLATION: 'violation' }
  return (
    <div className="transaction-cap-card">
      <div className="transaction-cap-heading">
        <span>
          {preview.paymentStageLabel || preview.paymentStage} · {preview.contractNo}
        </span>
        <StatusBadge tone={tone[status]}>
          {preview.resultStatusLabel}
          {status === 'VIOLATION' ? '(100% 초과)' : ''}
        </StatusBadge>
      </div>
      <progress
        className={`transaction-cap-progress is-${bar[status] ?? 'review'}`}
        max={100}
        value={Math.min(Math.max(Number(preview.usagePct) || 0, 0), 100)}
        aria-label={`한도 사용률 ${usageRate(preview.usagePct)}%`}
      />
      <div className="transaction-cap-usage">
        <span>사용률 {usageRate(preview.usagePct)}%</span>
        <span>한도 {won(preview.limitAmount)}</span>
      </div>
      <AmountLine label="이번 건 전 산입 누계" value={preview.existingIncludedAmount} />
      <AmountLine label="이번 건 산입액" value={preview.candidateAmount} />
      <AmountLine label="합계" value={preview.includedAmount} emphasize />
      <AmountLine label="잔여 한도" value={preview.remainingAmount} emphasize={isNegative(preview.remainingAmount)} />
    </div>
  )
}
export function CapPanel({ result, pending, error }: { result: Precheck | null; pending: boolean; error: string }) {
  return (
    <section className="surface transaction-cap-panel" id="cap-panel" aria-labelledby="transaction-cap-title">
      <header className="surface-header transaction-surface-header">
        <h2 className="surface-title" id="transaction-cap-title">
          1,200% 사전검증
        </h2>
        <span className="transaction-panel-meta">저장하지 않고 계산만</span>
      </header>
      <div
        className="surface-body transaction-cap-body"
        id="cap-body"
        role="status"
        aria-live="polite"
        aria-busy={pending}
      >
        {pending ? (
          <p className="transaction-panel-placeholder">1,200% 한도를 계산하는 중입니다.</p>
        ) : error ? (
          <p className="field-error" role="alert">
            {error}
          </p>
        ) : result ? (
          result.capPreview?.length ? (
            result.capPreview.map((preview, index) => (
              <CapCard key={`${preview.contractId}-${index}`} preview={preview} />
            ))
          ) : (
            <p className="transaction-panel-placeholder">표시할 1,200% 검증 결과가 없습니다.</p>
          )
        ) : (
          <p className="transaction-panel-placeholder">
            [사전검증]을 누르면 한도 초과 여부를 미리 계산하며 아무것도 저장하지 않습니다.
          </p>
        )}
      </div>
    </section>
  )
}
function positiveId(value: unknown): string | null {
  const text = String(value ?? '')
  return /^[1-9]\d*$/.test(text) ? text : null
}
export function ConfirmationModal({
  open,
  result,
  form,
  paymentId,
  month,
  pending,
  failure,
  failureText,
  contractNo,
  onClose,
  onConfirm,
}: {
  open: boolean
  result: Precheck | null
  form: FormValues
  paymentId: string | null
  month: string
  pending: boolean
  failure: ApiError | null
  failureText: string
  contractNo?: string
  onClose: () => void
  onConfirm: () => void
}) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  const recorded = failure != null && !isRetryableConfirmation(failure) && RECORDABLE_CODES.includes(failure.code)
  const existingViolation =
    (failure != null && !isRetryableConfirmation(failure) && failure.code === 'FGC-CAP-003') ||
    (!failure && result?.blockers?.[0]?.code === 'FGC-CAP-003')
  const hasException = recorded || existingViolation
  const reviewRequired = failure?.code === 'FGC-CAP-002'
  const recordable = result != null && !result.confirmable && hasRecordableBlocker(result)
  // A transport error leaves the last server precheck intact, so an explicit retry
  // uses the identical idempotency key; a business rejection requires a fresh precheck.
  const retryable = isRetryableConfirmation(failure)
  const canSubmit = result != null && (result.confirmable === true || recordable) && !recorded && retryable
  const capCheckId = positiveId(failure?.params.capCheckId)
  const exceptionCaseId = positiveId(failure?.params.exceptionCaseId)
  const exceptionParams = new URLSearchParams({ type: reviewRequired ? 'CAP_REVIEW_REQUIRED' : 'CAP_VIOLATION' })
  if (contractNo) exceptionParams.set('contractNo', contractNo)
  if (exceptionCaseId) exceptionParams.set('selected', exceptionCaseId)
  return (
    <Modal
      open={open}
      title="지급 건 확정"
      className="transaction-confirm-modal"
      onClose={() => {
        if (!pending) onClose()
      }}
      closeOnBackdrop={!pending}
      closeOnEscape={!pending}
      initialFocusRef={cancelRef}
      footer={
        <>
          <button
            type="button"
            ref={cancelRef}
            className="button button-secondary"
            disabled={pending}
            onClick={onClose}
          >
            취소
          </button>
          {canSubmit && (
            <Button id="btn-confirm-submit" loading={pending} onClick={onConfirm}>
              {pending ? '확정 중' : recordable ? '확정 시도 · 예외 등록' : '확정 실행'}
            </Button>
          )}
        </>
      }
    >
      <div className="transaction-confirm-body">
        <div className="guidance guidance-warning">
          <span className="material-symbols-rounded guidance-icon" aria-hidden="true">
            warning
          </span>
          <div>
            <strong className="guidance-title">확정 후에는 이 화면에서 되돌릴 수 없습니다</strong>
            <p className="guidance-copy">
              취소가 필요하면 별도의 IF-API-26 절차로 처리해야 합니다. 현재 내용을 확인한 뒤 확정하세요.
            </p>
          </div>
        </div>
        <div id="transaction-confirm-summary" className="transaction-confirm-summary">
          {[
            ['업무키', form.sourceBusinessKey || '-'],
            ['지급액', won(form.amount)],
            ['흐름', form.cashflowType === 'PAYMENT' ? '지급' : '차감'],
            ['서버 판정', !failureText && result?.confirmable ? '확정 가능' : '확정 차단'],
          ].map(([label, value]) => (
            <div className="transaction-cap-amount-line" key={label}>
              <span>{label}</span>
              <strong>{value}</strong>
            </div>
          ))}
        </div>
        {Boolean(failureText || result?.blockers?.length) && (
          <div id="transaction-confirm-blockers" className="transaction-confirm-blockers" role="alert">
            {failureText ? (
              <p>
                {failure?.code && failure.status < 500 ? `${failure.code} · ` : ''}
                {failureText}
              </p>
            ) : (
              result?.blockers?.map((blocker, index) => (
                <p key={index}>
                  {blocker.code ? `${blocker.code} · ` : ''}
                  {blocker.message || '확정 차단 사유를 확인하세요.'}
                </p>
              ))
            )}
          </div>
        )}
        {(recordable || hasException || capCheckId) && (
          <div id="transaction-confirm-followup" className="transaction-confirm-followup">
            {recordable && !hasException && (
              <p className="transaction-confirm-notice">
                확정되지 않습니다. 서버가 이 판정을 기록하고 예외함에 등록한 뒤 확정을 거부합니다. 지급 건은 작성중으로
                남습니다.
              </p>
            )}
            <div className="transaction-confirm-followup-actions">
              {recorded && reviewRequired && (
                <Link
                  className="button button-secondary"
                  reloadDocument
                  to={withMonth(`/transactions/new?id=${encodeURIComponent(paymentId ?? '')}`, month)}
                >
                  귀속행 판정 다시 확인
                </Link>
              )}
              {hasException && (
                <Link className="button button-secondary" to={withMonth(`/exceptions?${exceptionParams}`, month)}>
                  예외함에서 처리하기
                </Link>
              )}
              {capCheckId && (
                <Link
                  className="button button-secondary"
                  to={withMonth(`/cap-checks?capCheckId=${encodeURIComponent(capCheckId)}`, month)}
                >
                  계산근거 확인
                </Link>
              )}
            </div>
          </div>
        )}
      </div>
    </Modal>
  )
}
