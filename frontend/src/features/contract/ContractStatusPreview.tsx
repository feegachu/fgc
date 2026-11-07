import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/client'
import { date, dateTimeSeconds, errorText, int, internalErrorText, rate, won } from '../../lib/format'
import { usePermission } from '../auth/useAuth'
import { reasonText } from '../clawback/labels'
import { LINE_STATUS, PAYMENT_STAGE } from '../schedule/labels'
import { previewContractStatusEvent } from './api'
import type { ContractDetail, ContractStatusPreview as Preview, ContractStatusPreviewRequest } from './api'
import { previewEffectiveAtError, seoulNow, seoulOffsetDateTime } from './form'
import { contractStatusLabels, paymentStatusLabels, responseLabel } from './labels'

type PreviewLine = NonNullable<Preview['stoppedLines']>[number]
type PreviewPayment = NonNullable<Preview['payments']>[number]
type ClawbackLine = NonNullable<NonNullable<Preview['clawbackReview']>['lines']>[number]
type Fields = { eventType?: string; effectiveAt?: string }
type Failure = { message: string; conflict?: boolean; retry?: boolean }

const effectLabels: Record<string, string> = {
  ...LINE_STATUS,
  ...paymentStatusLabels,
  EXCLUDED: '바꾸지 않음',
  HELD: '보류',
  RELEASED: '보류 해제',
}
const effectLabel = (code?: string) => responseLabel(effectLabels, code)
const change = (row: { previousState?: string; nextState?: string }) =>
  `${effectLabel(row.previousState)} → ${effectLabel(row.nextState)}`
const stageLabel = (stage?: string) => responseLabel(PAYMENT_STAGE, stage)
/** null 은 자료가 없다는 뜻이다. 실제 0원과 구분한다. */
const amountText = (value?: number | null) => (value == null ? '자료 없음' : won(value))

// 근거는 이 화면의 핵심이라 표의 기본 말줄임을 쓰지 않는다.
const WRAP = 'contract-preview-wrap'
const lineColumns: TableColumn<PreviewLine>[] = [
  { key: 'installment', label: '회차', align: 'number', width: '3.5rem', render: (r) => int(r.installmentNo) },
  { key: 'stage', label: '지급단계', width: '6.5rem', render: (r) => stageLabel(r.paymentStage) },
  { key: 'item', label: '수수료 항목', width: '9rem', render: (r) => r.commissionItemName ?? '-' },
  {
    key: 'due',
    label: '지급예정일',
    width: '7rem',
    cellClassName: WRAP,
    render: (r) => (r.nextDueDate ? `${date(r.dueDate)} → ${date(r.nextDueDate)}` : date(r.dueDate)),
  },
  { key: 'amount', label: '금액', align: 'number', width: '6.5rem', render: (r) => amountText(r.amount) },
  { key: 'change', label: '상태 변경', width: '8rem', render: change },
  { key: 'reason', label: '근거', cellClassName: WRAP, render: (r) => r.reason ?? '-' },
]
const paymentColumns: TableColumn<PreviewPayment>[] = [
  { key: 'id', label: '지급 건', align: 'number', width: '5rem', render: (r) => int(r.commissionTransactionId) },
  { key: 'amount', label: '이 계약 귀속액', align: 'number', width: '7rem', render: (r) => amountText(r.amount) },
  { key: 'change', label: '상태 변경', width: '9rem', render: change },
  { key: 'reason', label: '근거', cellClassName: WRAP, render: (r) => r.reason ?? '-' },
]
const clawbackColumns: TableColumn<ClawbackLine>[] = [
  {
    key: 'item',
    label: '수수료 항목',
    render: (r) =>
      r.installmentNo == null ? (r.commissionItemName ?? '-') : `${r.commissionItemName ?? '-'}, ${int(r.installmentNo)}회차`,
  },
  { key: 'basis', label: '기지급 기준액', align: 'number', width: '7rem', render: (r) => amountText(r.basisAmount) },
  { key: 'rate', label: '환수율', align: 'number', width: '4.5rem', render: (r) => `${rate(r.appliedRatePct)}%` },
  { key: 'amount', label: '예상 환수액', align: 'number', width: '7rem', render: (r) => amountText(r.clawbackAmount) },
]

function failureOf(error: unknown): { failure?: Failure; fields?: Fields } {
  if (!(error instanceof ApiError))
    return { failure: { message: '연결이 끊겨 미리보기 결과를 받지 못했습니다. 저장된 것은 없습니다. 다시 시도하세요.', retry: true } }
  if (error.status >= 500 || error.code === 'FGC-COMMON-500')
    return { failure: { message: internalErrorText(error.requestId), retry: true } }
  switch (error.code) {
    case 'FGC-IMPL-TRANSITION_DENIED':
      return {
        failure: {
          message: `이 계약은 지금 ${String(error.params.from ?? '현재 상태')}에서 ${String(error.params.to ?? '선택한 상태')}(으)로 바꿀 수 없습니다. 최신 계약을 다시 조회해 변경 종류를 고르세요.`,
          conflict: true,
        },
      }
    case 'FGC-IMPL-EVENT_ORDER':
      return { fields: { effectiveAt: '효력일시는 최신 상태 변경의 효력일시 이후이고 현재 시각을 넘을 수 없습니다.' } }
    case 'FGC-IMPL-POLICY_MISSING':
      return { failure: { message: '계약일에 적용할 상태별 지급조건 정책을 확인할 수 없어 계산하지 않았습니다. 금액을 0원으로 보지 마세요.' } }
    case 'FGC-COMMON-004':
      return { failure: { message: '계약을 찾을 수 없습니다. 목록에서 다시 선택하세요.' } }
    case 'FGC-COMMON-002':
      if (error.field === 'eventType' || error.field === 'effectiveAt') return { fields: { [error.field]: error.message } }
  }
  if (error.status === 403) return { failure: { message: '영향 미리보기 권한이 없습니다.' } }
  return { failure: { message: errorText(error, '미리보기를 불러오지 못했습니다.'), retry: true } }
}

/** IF-API-64 영향 미리보기. 저장하지 않으며 실제 저장은 계약 수정(IF-API-19)에서 최신 상태로 다시 검증한다. */
export function ContractStatusPreviewModal({
  id,
  contract,
  open,
  editPath,
  onClose,
  onReload,
}: {
  id: string
  contract: ContractDetail
  open: boolean
  editPath: string
  onClose: () => void
  onReload: () => Promise<unknown>
}) {
  const navigate = useNavigate()
  const canEdit = usePermission('canProcess')
  const [eventType, setEventType] = useState('')
  const [effectiveAt, setEffectiveAt] = useState('')
  const [fields, setFields] = useState<Fields>({})
  const [failure, setFailure] = useState<Failure | null>(null)
  const [loading, setLoading] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [result, setResult] = useState<{ data: Preview; key: string } | null>(null)
  const inFlight = useRef<AbortController | null>(null)
  const typeInput = useRef<HTMLSelectElement>(null)
  const timeInput = useRef<HTMLInputElement>(null)
  const allowed = contract.allowedNextStatuses ?? []
  // 다시 조회한 뒤 고를 수 없게 된 사건은 선택에서 뺀다. 효력일시는 그대로 둔다.
  const selected = allowed.find((status) => status === eventType)
  // 결과가 어느 입력과 계약 판에서 나왔는지 기억해 입력이나 대상이 바뀌면 낡음으로 표시한다.
  const inputKey = JSON.stringify([selected, effectiveAt, contract.updatedAt])
  const stale = result != null && result.key !== inputKey

  useEffect(() => () => inFlight.current?.abort(), [])

  /** 진행 중인 요청을 버린다. 늦게 온 응답은 지금 입력의 결과가 아니므로 보이지 않는다. */
  function cancel() {
    inFlight.current?.abort()
    inFlight.current = null
    setLoading(false)
  }

  function changeInput(apply: () => void, field: keyof Fields) {
    cancel()
    apply()
    setFailure(null)
    setFields((current) => ({ ...current, [field]: undefined }))
  }

  function reset() {
    cancel()
    setEventType('')
    setEffectiveAt('')
    setFields({})
    setFailure(null)
    setResult(null)
  }

  function close() {
    cancel()
    onClose()
  }

  async function run(event?: FormEvent) {
    event?.preventDefault()
    if (inFlight.current) return
    const validation: Fields = {
      eventType: selected ? undefined : '변경 종류를 선택하세요.',
      effectiveAt: previewEffectiveAtError(effectiveAt, seoulNow()),
    }
    setFields(validation)
    setFailure(null)
    if (!selected || validation.effectiveAt) {
      ;(selected ? timeInput : typeInput).current?.focus()
      return
    }
    const controller = new AbortController()
    inFlight.current = controller
    setLoading(true)
    const body: ContractStatusPreviewRequest = { eventType: selected, effectiveAt: seoulOffsetDateTime(effectiveAt) }
    try {
      const data = await previewContractStatusEvent(id, body, controller.signal)
      if (controller.signal.aborted) return
      setResult({ data, key: inputKey })
    } catch (error) {
      if (controller.signal.aborted) return
      // 실패한 결과를 이전 값이나 0원으로 남기지 않는다.
      setResult(null)
      const failed = failureOf(error)
      setFailure(failed.failure ?? null)
      if (failed.fields) {
        setFields(failed.fields)
        timeInput.current?.focus()
      }
    } finally {
      if (inFlight.current === controller) {
        inFlight.current = null
        setLoading(false)
      }
    }
  }

  async function reloadContract() {
    setReloading(true)
    try {
      await onReload()
      setFailure(null)
    } catch (error) {
      setFailure({ message: errorText(error instanceof Error ? error : null, '최신 계약을 불러오지 못했습니다.'), conflict: true })
    } finally {
      setReloading(false)
    }
  }

  return (
    <Modal
      open={open}
      title="상태 변경 영향 미리보기"
      className="modal-large contract-status-preview-modal"
      onClose={close}
      footer={
        <>
          {!canEdit && <p className="field-helper">계약 수정은 정산담당자와 시스템관리자만 할 수 있습니다.</p>}
          <Button data-guide-feature="features.contract.contract-status-preview.close" variant="secondary" onClick={close}>
            닫기
          </Button>
          {canEdit && (
            <Button
              data-guide-feature="features.contract.contract-status-preview.edit"
              variant="secondary"
              disabled={loading || !selected || Boolean(previewEffectiveAtError(effectiveAt, seoulNow()))}
              onClick={() => void navigate(editPath, { state: { statusDraft: { contractStatus: selected, effectiveAt } } })}
            >
              계약 수정으로 이동
            </Button>
          )}
        </>
      }
    >
      <p className="contract-preview-note">
        입력한 상태 변경이 일어났을 때의 조건부 비교입니다. 계약 유지 확률이 아니며 아무것도 저장하지 않습니다. 실제 저장은 계약 수정에서 저장 시점의 최신
        상태로 다시 검증합니다.
      </p>
      <form
        data-guide-feature="features.contract.contract-status-preview.form"
        className="contract-preview-form"
        aria-label="미리볼 상태 변경"
        noValidate
        onSubmit={(event) => void run(event)}
      >
        <Field
          label="변경 종류"
          required
          error={fields.eventType}
          helper={allowed.length ? undefined : '끝 상태 계약이라 바꿀 수 있는 상태가 없습니다.'}
        >
          <select
            data-guide-feature="features.contract.contract-status-preview.event-type"
            ref={typeInput}
            className="select-control"
            value={selected ?? ''}
            disabled={!allowed.length}
            onChange={(event) => changeInput(() => setEventType(event.target.value), 'eventType')}
          >
            <option value="">변경 종류를 선택하세요</option>
            {allowed.map((status) => (
              <option key={status} value={status}>
                {contractStatusLabels[status]}
              </option>
            ))}
          </select>
        </Field>
        <Field
          label="효력일시"
          required
          error={fields.effectiveAt}
          helper="실제 효력 시각(한국 시간). 최신 상태 변경의 효력일시 이후, 현재 시각 이전이어야 합니다."
        >
          <input
            data-guide-feature="features.contract.contract-status-preview.effective-at"
            ref={timeInput}
            type="datetime-local"
            value={effectiveAt}
            onChange={(event) => changeInput(() => setEffectiveAt(event.target.value), 'effectiveAt')}
          />
        </Field>
        <div className="contract-preview-actions">
          <Button
            data-guide-feature="features.contract.contract-status-preview.submit"
            type="submit"
            loading={loading}
            disabled={!allowed.length}
          >
            영향 미리보기
          </Button>
          <Button data-guide-feature="features.contract.contract-status-preview.reset" variant="secondary" onClick={reset}>
            입력 초기화
          </Button>
        </div>
      </form>
      {loading && (
        <p role="status" className="contract-preview-status">
          {result ? '결과를 갱신하는 중입니다. 아래는 이전 결과입니다.' : '영향을 계산하는 중입니다.'}
        </p>
      )}
      {failure && (
        <div role="alert" className="contract-preview-failure">
          <p>{failure.message}</p>
          {failure.conflict && (
            <Button
              data-guide-feature="features.contract.contract-status-preview.reload"
              variant="secondary"
              loading={reloading}
              onClick={() => void reloadContract()}
            >
              다시 조회
            </Button>
          )}
          {failure.retry && (
            <Button
              data-guide-feature="features.contract.contract-status-preview.retry"
              variant="secondary"
              onClick={() => void run()}
            >
              다시 시도
            </Button>
          )}
        </div>
      )}
      {result && <PreviewResult preview={result.data} stale={stale} updating={loading} />}
    </Modal>
  )
}

function PreviewResult({ preview, stale, updating }: { preview: Preview; stale: boolean; updating: boolean }) {
  const stopped = preview.stoppedLines ?? []
  const resumed = preview.resumedLines ?? []
  const excluded = preview.excludedLines ?? []
  const payments = preview.payments ?? []
  const review = preview.clawbackReview
  return (
    <div className="contract-preview-result" aria-busy={updating}>
      {stale && (
        <p role="status" className="contract-preview-stale">
          <StatusBadge tone="warning">최신 아님</StatusBadge> 입력이나 계약이 바뀌었습니다. 이 결과는 지금 조건의 결과가 아닙니다. 다시 미리보세요.
        </p>
      )}
      <p className="contract-preview-basis">
        {responseLabel(contractStatusLabels, preview.currentStatus)} → {responseLabel(contractStatusLabels, preview.eventType)},
        효력일시 {dateTimeSeconds(preview.effectiveAt)}, 계산 시각 {dateTimeSeconds(preview.snapshotAt)} 기준
      </p>
      {(preview.pendingFollowUps ?? 0) > 0 && (
        <p role="status" className="contract-preview-stale">
          앞선 상태 변경의 후속 처리 {int(preview.pendingFollowUps)}건이 끝나지 않았습니다. 실제 결과가 이 비교와 달라질 수 있습니다.
        </p>
      )}
      <dl className="contract-preview-summary">
        <div>
          <dt>보류 금액</dt>
          <dd>
            변경 전 {amountText(preview.heldAmount?.before)}, 변경 후 {amountText(preview.heldAmount?.after)}
          </dd>
        </div>
        <div>
          <dt>기지급액 (변경 전후 같음)</dt>
          <dd>
            {(preview.paidAmount ?? []).map((paid) => (
              <span key={paid.paymentStage}>
                {stageLabel(paid.paymentStage)} {amountText(paid.amount)}
              </span>
            ))}
          </dd>
        </div>
        <div>
          <dt>환수 검토</dt>
          <dd>
            <ClawbackSummary review={review} />
          </dd>
        </div>
      </dl>
      <PreviewTable
        title="예상 환수액 계산 근거"
        rows={review?.lines ?? []}
        rowKey={(r) => String(r.transactionAttributionId)}
        columns={clawbackColumns}
      />
      {stopped.length === 0 && resumed.length === 0 && <p className="contract-preview-empty">변경되는 예정 회차가 없습니다</p>}
      <PreviewTable title="멈추는 회차" rows={stopped} rowKey={lineKey} columns={lineColumns} />
      <PreviewTable title="재개되는 회차" rows={resumed} rowKey={lineKey} columns={lineColumns} />
      <PreviewTable title="바꾸지 않는 회차" rows={excluded} rowKey={lineKey} columns={lineColumns} />
      <PreviewTable
        title="미확정 지급 건 영향"
        rows={payments}
        rowKey={(r) => String(r.commissionTransactionId)}
        columns={paymentColumns}
      />
    </div>
  )
}

const lineKey = (row: PreviewLine) => String(row.scheduleLineId)

/** 표 캡션은 보조 기술용이라 눈에 보이는 제목을 따로 둔다. 행이 없으면 표를 그리지 않는다. */
function PreviewTable<T>({
  title,
  rows,
  rowKey,
  columns,
}: {
  title: string
  rows: T[]
  rowKey: (row: T) => string
  columns: TableColumn<T>[]
}) {
  if (!rows.length) return null
  return (
    <div>
      <h3 className="contract-preview-table-title">
        {title} {int(rows.length)}건
      </h3>
      <DataTable caption={title} resizable={false} rows={rows} rowKey={rowKey} columns={columns} />
    </div>
  )
}

function ClawbackSummary({ review }: { review: Preview['clawbackReview'] }) {
  if (!review?.candidate) return <>해당 없음. {review?.reason}</>
  const codes = review.reasonCodes ?? []
  return (
    <>
      {/* 예상액을 확정액처럼 보이지 않게 하고, 미계산을 0원으로 바꾸지 않는다. */}
      <strong>{review.amount == null ? '미계산' : `예상 ${won(review.amount)}`}</strong> {review.reason}
      {review.elapsedMonths != null && ` 경과 ${int(review.elapsedMonths)}개월.`}
      {review.policyVersionNo != null && ` 적용 정책 v${int(review.policyVersionNo)}.`}
      {codes.length > 0 && <span className="contract-preview-reasons">{codes.map(reasonText).join(', ')}</span>}
    </>
  )
}
