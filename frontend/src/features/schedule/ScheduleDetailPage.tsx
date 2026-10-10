import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { Modal } from '../../components/Modal'
import { ApiError } from '../../lib/api/client'
import { exportCsv } from '../../lib/exportCsv'
import { date, dateTime, errorText, int, isNegative, rate, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { usePermission } from '../auth/useAuth'
import {
  confirmSchedule,
  detailCsvPath,
  findActiveSchedule,
  REASON_MAX,
  regenerateSchedule,
  scheduleDetailQuery,
  scheduleVersionsQuery,
} from './api'
import type { ScheduleHeader, ScheduleLine } from './api'
import { ActiveBadge, ClassificationBadge, RegimeEvidence, ScheduleStatusBadge } from './badges'
import {
  CALCULATION_TYPE,
  EMPTY,
  LINE_STATUS,
  PAYMENT_STAGE,
  responseLabel,
  SCHEDULE_PURPOSE,
  SCHEDULE_REGIME,
  SCHEDULE_STATUS,
} from './labels'
import './schedule.css'

/*
 * FGC-UI-SCHE-W02 예상 스케줄 상세 (FUN-036·039·040 / REG-01·REG-19).
 * 1차 schedule/detail.html · schedule-detail.js 와 같은 문구·동작을 쓴다.
 * [막을 것] 확정 스케줄의 금액 수정·확정 되돌리기·기존 버전 삭제 — 화면에 그런 동작을 만들지 않는다.
 * 확정·재생성 버튼 비활성은 안내일 뿐이고 최종 판정은 서버 @PreAuthorize(Roles.CAN_PROCESS)다.
 */
const value = (content: unknown) => (content == null || content === '' ? EMPTY : String(content))
const versionText = (versionNo?: number) => (versionNo == null ? EMPTY : `v${versionNo}`)
const join = (...parts: unknown[]) =>
  parts.filter((part) => part != null && part !== '' && part !== EMPTY).join(' · ') || EMPTY
const Money = ({ amount }: { amount: unknown }) => (
  <span className={isNegative(amount) ? 'is-negative-amount' : undefined}>{won(amount)}</span>
)
const Text = ({ content }: { content: unknown }) => <CellDisclosure singleLine preview={value(content)} />

function InlineState({ message, error, onRetry }: { message: string; error?: boolean; onRetry?: () => void }) {
  return (
    <p className={`schedule-inline-state ${error ? 'is-error' : 'is-loading'}`} role={error ? 'alert' : 'status'}>
      {message}
      {onRetry && (
        <Button variant="secondary" onClick={onRetry}>
          다시 시도
        </Button>
      )}
    </p>
  )
}

export default function ScheduleDetailPage() {
  const { id = '' } = useParams()
  // 다른 버전·지급단계로 이동하면 모달·입력 상태를 새 스케줄 기준으로 다시 시작한다.
  return <ScheduleDetailView key={id} id={id} />
}

function ScheduleDetailView({ id }: { id: string }) {
  const validId = /^\d+$/.test(id)
  const client = useQueryClient()
  const navigate = useNavigate()
  const canProcess = usePermission('canProcess')
  const detail = useQuery({ ...scheduleDetailQuery(id), enabled: validId })
  // 1차처럼 헤더를 받은 뒤에 버전 이력을 부른다.
  const versions = useQuery({ ...scheduleVersionsQuery(id), enabled: detail.isSuccess })
  const header = detail.data?.header
  const lines = detail.data?.lines ?? []
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [regenerateOpen, setRegenerateOpen] = useState(false)

  const moving = useMutation({
    mutationFn: (stage: string) => findActiveSchedule(id, stage),
    meta: { errorToast: false },
    onSuccess: (targetId) => void navigate(`/schedules/${encodeURIComponent(String(targetId))}`),
    onError: (error) => toast(errorText(error, '선택한 지급단계의 스케줄을 찾을 수 없습니다.'), 'warning', 4500),
  })
  const exporting = useMutation({
    mutationFn: () => exportCsv(detailCsvPath(id), { filename: `schedule-${id}.csv` }),
    meta: { errorToast: false },
    onSuccess: () => toast('회차 표 CSV를 내려받았습니다.', 'success', 3500),
  })

  const confirmDisabled = !canProcess || header?.status !== 'PLANNED' || header.activeYn !== true
  const regenerateDisabled = !canProcess || header?.activeYn !== true || header.status === 'CANCELLED'
  const reasons: string[] = []
  if (header) {
    if (!canProcess) reasons.push('확정·재생성은 정산 담당자만 할 수 있습니다.')
    if (header.activeYn !== true) reasons.push('사용 중인 버전이 아닙니다.')
    if (header.status === 'CONFIRMED') reasons.push('이미 확정된 스케줄입니다.')
    else if (header.status !== 'PLANNED') reasons.push('예정 상태에서만 확정할 수 있습니다.')
  }
  const showNote = header && (confirmDisabled || regenerateDisabled) && reasons.length > 0
  const retry = () => void detail.refetch()
  const loadError = !validId
    ? '스케줄 ID를 확인할 수 없습니다.'
    : detail.isError
      ? errorText(detail.error, '예상 스케줄을 불러오지 못했습니다.')
      : null

  return (
    <div className="schedule-detail-page">
      <header className="page-header schedule-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">예상 스케줄 상세</h1>
        </div>
        <div className="page-header-actions">
          <Button
            variant="secondary"
            loading={exporting.isPending}
            disabled={!validId}
            onClick={() => exporting.mutate()}
          >
            <span className="material-symbols-rounded" aria-hidden="true">
              download
            </span>
            CSV 내보내기
          </Button>
          <Button
            variant="secondary"
            disabled={regenerateDisabled}
            aria-describedby={showNote ? 'schedule-action-note' : undefined}
            onClick={() => setRegenerateOpen(true)}
          >
            <span className="material-symbols-rounded" aria-hidden="true">
              restart_alt
            </span>
            새 버전 만들기
          </Button>
          <Button
            disabled={confirmDisabled}
            aria-describedby={showNote ? 'schedule-action-note' : undefined}
            onClick={() => setConfirmOpen(true)}
          >
            <span className="material-symbols-rounded" aria-hidden="true">
              lock
            </span>
            확정
          </Button>
        </div>
      </header>

      {/* 비활성 사유는 title 이 아니라 가시 텍스트로 둔다 — 비활성 버튼은 포커스가 가지 않는다. */}
      {showNote && (
        <p className="schedule-action-note" id="schedule-action-note" role="status">
          {reasons.join(' ')}
        </p>
      )}

      <section className="surface schedule-detail-card" aria-labelledby="schedule-header-title">
        <header className="surface-header">
          <h2 className="surface-title" id="schedule-header-title">
            스케줄 헤더 ·{' '}
            <span className="tabular-nums">schedule_header #{header ? value(header.scheduleHeaderId) : '-'}</span>
          </h2>
          {/* 지급단계는 이동 컨트롤이다(화면정의서 SCHE-W02, IF-API-28C). */}
          <div className="field schedule-stage-field">
            <label className="field-label" htmlFor="schedule-stage">
              지급단계
            </label>
            <select
              className="select-control"
              id="schedule-stage"
              value={header?.paymentStage ?? 'GA_TO_FC'}
              disabled={!header?.paymentStage || moving.isPending}
              aria-busy={moving.isPending || undefined}
              onChange={(event) => {
                if (event.target.value !== header?.paymentStage) moving.mutate(event.target.value)
              }}
            >
              <option value="GA_TO_FC">{PAYMENT_STAGE.GA_TO_FC}</option>
              <option value="INSURER_TO_GA">{PAYMENT_STAGE.INSURER_TO_GA}</option>
            </select>
          </div>
        </header>
        <div className="surface-body">
          {header ? (
            <HeaderSummary header={header} />
          ) : (
            <InlineState
              message={loadError ?? '스케줄을 불러오는 중입니다.'}
              error={Boolean(loadError)}
              onRetry={validId && detail.isError ? retry : undefined}
            />
          )}
        </div>
      </section>

      <section className="surface schedule-detail-card" aria-labelledby="schedule-lines-title">
        <header className="surface-header">
          <h2 className="surface-title" id="schedule-lines-title">
            회차별 예상 금액
          </h2>
          <p className="schedule-detail-note">
            줄마다 원 단위 <span className="tabular-nums">HALF_UP</span>으로 반올림한 뒤 합계를 냅니다
          </p>
        </header>
        <div
          className="data-table-viewport schedule-line-viewport"
          tabIndex={0}
          role="region"
          aria-label="회차별 예상 금액 표"
        >
          <table className="data-table schedule-line-table" aria-busy={detail.isFetching}>
            <caption className="visually-hidden">회차별 예상 지급 금액</caption>
            <colgroup>
              {[
                'no',
                'installment',
                'month',
                'due',
                'item',
                'recipient',
                'basis',
                'amount',
                'calc',
                'rate',
                'expected',
                'status',
                'rule',
              ].map((name) => (
                <col key={name} className={`schedule-line-col-${name}`} />
              ))}
            </colgroup>
            <thead>
              <tr>
                <th scope="col" className="is-number">
                  줄
                </th>
                <th scope="col" className="is-number">
                  회차
                </th>
                <th scope="col" className="is-number">
                  계약차월
                </th>
                <th scope="col">지급예정일</th>
                <th scope="col">수수료 항목</th>
                <th scope="col">수령자</th>
                <th scope="col">기준코드</th>
                <th scope="col" className="is-number">
                  기준금액
                </th>
                <th scope="col" className="is-center">
                  계산방식
                </th>
                <th scope="col" className="is-number">
                  요율
                </th>
                <th scope="col" className="is-number">
                  예상금액
                </th>
                <th scope="col" className="is-center">
                  상태
                </th>
                {/* IF-API-28 ruleRef — 규칙 5 "근거 없는 숫자는 화면에 띄우지 않습니다" */}
                <th scope="col" className="is-number">
                  규칙 ID
                </th>
              </tr>
            </thead>
            <tbody>
              {!header ? (
                <StateRow columns={13}>
                  <InlineState
                    message={loadError ?? '예상 스케줄을 불러오는 중입니다.'}
                    error={Boolean(loadError)}
                    onRetry={validId && detail.isError ? retry : undefined}
                  />
                </StateRow>
              ) : lines.length === 0 ? (
                <StateRow columns={13}>
                  <p className="schedule-inline-state is-empty" role="status">
                    등록된 회차가 없습니다.
                  </p>
                </StateRow>
              ) : (
                lines.map((line, index) => <LineRow key={line.lineNo ?? index} line={line} />)
              )}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={10}>
                  합계 (<span className="tabular-nums">{int(lines.length)}</span>줄)
                </td>
                {/* 합계는 서버가 줄별 반올림 금액을 더한 expectedTotal 을 그대로 쓴다(화면에서 다시 계산하지 않는다). */}
                <td className="is-number tabular-nums">
                  <Money amount={header ? header.expectedTotal : 0} />
                </td>
                <td colSpan={2} />
              </tr>
            </tfoot>
          </table>
        </div>
      </section>

      <section className="surface schedule-detail-card" aria-labelledby="schedule-versions-title">
        <header className="surface-header">
          <h2 className="surface-title" id="schedule-versions-title">
            버전 비교
          </h2>
          <p className="schedule-detail-note">지난 버전은 지우지 않고 남겨 둡니다</p>
        </header>
        <div
          className="data-table-viewport schedule-version-viewport"
          tabIndex={0}
          role="region"
          aria-label="스케줄 버전 비교 표"
        >
          <table className="data-table schedule-version-table" aria-busy={versions.isFetching}>
            <caption className="visually-hidden">같은 계약의 스케줄 버전 이력</caption>
            <colgroup>
              {['no', 'status', 'active', 'policy', 'reason', 'generated', 'lines', 'total'].map((name) => (
                <col key={name} className={`schedule-version-col-${name}`} />
              ))}
            </colgroup>
            <thead>
              <tr>
                <th scope="col" className="is-center">
                  버전
                </th>
                <th scope="col" className="is-center">
                  상태
                </th>
                <th scope="col" className="is-center">
                  사용중
                </th>
                <th scope="col">정책버전</th>
                <th scope="col">생성 사유</th>
                <th scope="col">생성 일시</th>
                <th scope="col" className="is-number">
                  회차 수
                </th>
                <th scope="col" className="is-number">
                  예상 총액
                </th>
              </tr>
            </thead>
            <tbody>
              {versions.isError ? (
                <StateRow columns={8}>
                  <InlineState
                    message={errorText(versions.error, '버전 이력을 불러오지 못했습니다.')}
                    error
                    onRetry={() => void versions.refetch()}
                  />
                </StateRow>
              ) : !versions.data ? (
                <StateRow columns={8}>
                  <InlineState message="버전 이력을 불러오는 중입니다." />
                </StateRow>
              ) : versions.data.length === 0 ? (
                <StateRow columns={8}>
                  <p className="schedule-inline-state is-empty" role="status">
                    같은 계약의 스케줄 버전이 없습니다.
                  </p>
                </StateRow>
              ) : (
                versions.data.map((version) => (
                  <VersionRow
                    key={version.scheduleHeaderId}
                    version={version}
                    current={String(version.scheduleHeaderId) === id}
                  />
                ))
              )}
            </tbody>
          </table>
        </div>
      </section>

      {header && (
        <ConfirmModal
          open={confirmOpen}
          header={header}
          onClose={() => setConfirmOpen(false)}
          onConfirm={async () => {
            const result = await confirmSchedule(id)
            client.setQueryData(scheduleDetailQuery(id).queryKey, result)
            void client.invalidateQueries({ queryKey: ['schedules', 'versions'] })
            void client.invalidateQueries({ queryKey: ['schedules', 'list'] })
            setConfirmOpen(false)
            toast('스케줄을 확정했습니다. 이제 금액을 고칠 수 없습니다. 바꾸려면 새 버전을 만드세요.', 'success', 4500)
          }}
        />
      )}
      <RegenerateModal
        open={regenerateOpen}
        onClose={() => setRegenerateOpen(false)}
        onSubmit={async (reason) => {
          const result = await regenerateSchedule(id, reason)
          void client.invalidateQueries({ queryKey: ['schedules'] })
          setRegenerateOpen(false)
          toast('새 스케줄 버전을 만들었습니다. 새 버전으로 이동합니다.', 'success', 3500)
          void navigate(`/schedules/${encodeURIComponent(String(result.scheduleHeaderId))}`)
        }}
      />
    </div>
  )
}

const StateRow = ({ columns, children }: { columns: number; children: ReactNode }) => (
  <tr className="schedule-state-row">
    <td colSpan={columns}>{children}</td>
  </tr>
)

function HeaderSummary({ header }: { header: ScheduleHeader }) {
  return (
    <dl className="schedule-detail-kv">
      <div>
        <dt>계약번호</dt>
        <dd className="tabular-nums">{value(header.contractNo)}</dd>
      </div>
      <div>
        <dt>보험회사 · 상품</dt>
        <dd>{join(header.insurerName, header.productName)}</dd>
      </div>
      <div>
        <dt>
          <RegimeEvidence />
        </dt>
        <dd>
          <ClassificationBadge
            label={responseLabel(header.scheduleRegimeLabel, SCHEDULE_REGIME, header.scheduleRegime)}
          />
        </dd>
      </div>
      <div>
        <dt>용도</dt>
        <dd>
          <ClassificationBadge
            label={responseLabel(header.schedulePurposeLabel, SCHEDULE_PURPOSE, header.schedulePurpose)}
          />
        </dd>
      </div>
      <div>
        <dt>버전</dt>
        <dd className="tabular-nums">{versionText(header.scheduleVersionNo)}</dd>
      </div>
      <div>
        <dt>상태</dt>
        <dd>
          <ScheduleStatusBadge
            code={header.status}
            label={responseLabel(header.statusLabel, SCHEDULE_STATUS, header.status)}
          />
        </dd>
      </div>
      <div>
        <dt>사용중</dt>
        <dd>
          <ActiveBadge active={header.activeYn} />
        </dd>
      </div>
      <div>
        <dt>정책버전</dt>
        <dd className="tabular-nums">{value(header.policyVersionLabel)}</dd>
      </div>
      <div>
        <dt>생성 사유 · 일시</dt>
        <dd>{join(header.generationReason, dateTime(header.generatedAt))}</dd>
      </div>
    </dl>
  )
}

function LineRow({ line }: { line: ScheduleLine }) {
  return (
    <tr>
      <td className="is-number tabular-nums">{int(line.lineNo)}</td>
      <td className="is-number tabular-nums">{int(line.installmentNo)}</td>
      <td className="is-number tabular-nums">{int(line.contractMonthNo)}</td>
      <td>{date(line.dueDate)}</td>
      <td>
        <Text content={line.commissionItemName} />
      </td>
      <td>
        <Text content={line.recipientName} />
      </td>
      <td>
        <Text content={line.basisCode} />
      </td>
      <td className="is-number tabular-nums">
        <Money amount={line.basisAmount} />
      </td>
      <td className="is-center">{responseLabel(null, CALCULATION_TYPE, line.calculationType)}</td>
      <td className="is-number tabular-nums">{line.ratePct == null ? EMPTY : `${rate(line.ratePct)}%`}</td>
      <td className="is-number tabular-nums">
        <Money amount={line.expectedAmount} />
      </td>
      <td className="is-center">
        <ScheduleStatusBadge code={line.lineStatus} label={responseLabel(null, LINE_STATUS, line.lineStatus)} />
      </td>
      <td className="is-number tabular-nums">{line.ruleRef == null ? EMPTY : int(line.ruleRef)}</td>
    </tr>
  )
}

function VersionRow({ version, current }: { version: ScheduleHeader; current: boolean }) {
  return (
    <tr className={current ? 'is-selected' : undefined} aria-current={current || undefined}>
      <td className="is-center">
        <Link className="tabular-nums" to={`/schedules/${encodeURIComponent(String(version.scheduleHeaderId))}`}>
          {versionText(version.scheduleVersionNo)}
        </Link>
      </td>
      <td className="is-center">
        <ScheduleStatusBadge
          code={version.status}
          label={responseLabel(version.statusLabel, SCHEDULE_STATUS, version.status)}
        />
      </td>
      <td className="is-center">
        <ActiveBadge active={version.activeYn} />
      </td>
      <td>
        <Text content={version.policyVersionLabel} />
      </td>
      <td>
        <Text content={version.generationReason} />
      </td>
      <td>{dateTime(version.generatedAt)}</td>
      <td className="is-number tabular-nums">{int(version.lineCount)}</td>
      <td className="is-number tabular-nums">
        <Money amount={version.expectedTotal} />
      </td>
    </tr>
  )
}

/** 확정은 되돌릴 수 없다(화면정의서 SCHE-W02) — 문구는 부록 A 표준 문구 그대로다. */
function ConfirmModal({
  open,
  header,
  onClose,
  onConfirm,
}: {
  open: boolean
  header: ScheduleHeader
  onClose: () => void
  onConfirm: () => Promise<void>
}) {
  const cancel = useRef<HTMLButtonElement>(null)
  const mutation = useMutation({
    mutationFn: onConfirm,
    meta: { errorToast: false },
    onError: (error) => toast(errorText(error, '예상 스케줄을 확정하지 못했습니다.'), 'error', 5000),
  })
  const close = () => {
    mutation.reset()
    onClose()
  }
  return (
    <Modal
      open={open}
      title="이 스케줄을 확정할까요?"
      className="schedule-modal"
      closeOnBackdrop
      initialFocusRef={cancel}
      onClose={close}
      footer={
        <>
          <button ref={cancel} type="button" className="button button-secondary" onClick={close}>
            취소
          </button>
          <Button loading={mutation.isPending} onClick={() => mutation.mutate()}>
            확정
          </Button>
        </>
      }
    >
      <p className="schedule-modal-description">
        되돌릴 수 없습니다 — 확정된 스케줄은 새 버전으로만 바꿉니다. 확정 후에는 금액을 고칠 수 없고, 고치려면{' '}
        <strong>[새 버전 만들기]</strong>를 눌러야 합니다.
      </p>
      <dl className="schedule-confirm-summary">
        <div>
          <dt>계약번호</dt>
          <dd className="tabular-nums">{value(header.contractNo)}</dd>
        </div>
        <div>
          <dt>버전</dt>
          <dd className="tabular-nums">{versionText(header.scheduleVersionNo)}</dd>
        </div>
        <div>
          <dt>회차 수</dt>
          <dd className="tabular-nums">{int(header.lineCount)}</dd>
        </div>
        <div>
          <dt>예상 총액</dt>
          <dd className="tabular-nums">{won(header.expectedTotal)}</dd>
        </div>
      </dl>
      {mutation.isError && (
        <p className="field-error schedule-modal-error" role="alert">
          {errorText(mutation.error, '예상 스케줄을 확정하지 못했습니다.')}
        </p>
      )}
    </Modal>
  )
}

function RegenerateModal({
  open,
  onClose,
  onSubmit,
}: {
  open: boolean
  onClose: () => void
  onSubmit: (reason: string) => Promise<void>
}) {
  const input = useRef<HTMLTextAreaElement>(null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: onSubmit,
    meta: { errorToast: false },
    onError: (caught) => {
      // 서버 검증(@NotBlank·@Size)이 사유 필드를 지목하면 Toast 가 아니라 필드 옆에 둔다(가이드 §11).
      if (caught instanceof ApiError && caught.field === 'reason') setError(caught.message)
      else toast(errorText(caught, '새 스케줄 버전을 만들지 못했습니다.'), 'error', 5000)
    },
  })
  const close = () => {
    setReason('')
    setError('')
    mutation.reset()
    onClose()
  }
  function submit() {
    const trimmed = reason.trim()
    const message = !trimmed
      ? '저장 불가 — 생성 사유를 입력하세요.'
      : trimmed.length > REASON_MAX
        ? `생성 사유는 ${REASON_MAX}자 이하여야 합니다.`
        : ''
    setError(message)
    if (message) {
      input.current?.focus()
      return
    }
    mutation.mutate(trimmed)
  }
  return (
    <Modal
      open={open}
      title="새 버전 만들기"
      className="schedule-modal"
      closeOnBackdrop
      initialFocusRef={input}
      onClose={close}
      footer={
        <>
          <button type="button" className="button button-secondary" onClick={close}>
            취소
          </button>
          <Button loading={mutation.isPending} onClick={submit}>
            새 버전 만들기
          </Button>
        </>
      }
    >
      <p className="schedule-modal-description">
        기존 스케줄은 그대로 두고 버전 번호를 올린 새 스케줄을 만듭니다. 지난 버전을 지우지 않으므로 당시 금액의 근거도
        계속 확인할 수 있습니다.
      </p>
      <div className={`field ${error ? 'is-error' : ''}`}>
        <label className="field-label" htmlFor="regenerate-reason">
          생성 사유
          <span className="field-required" aria-hidden="true">
            {' '}
            *
          </span>
        </label>
        <textarea
          ref={input}
          className="textarea-control"
          id="regenerate-reason"
          maxLength={REASON_MAX}
          rows={4}
          required
          aria-invalid={error ? true : undefined}
          aria-describedby="regenerate-reason-error regenerate-reason-help"
          placeholder="예) 2026-07 정책버전 개정 반영"
          value={reason}
          onChange={(event) => {
            setReason(event.target.value)
            const trimmed = event.target.value.trim()
            if (trimmed && trimmed.length <= REASON_MAX) setError('')
          }}
        />
        <p className="field-error" id="regenerate-reason-error" role="alert" hidden={!error}>
          {error}
        </p>
        <p className="field-helper" id="regenerate-reason-help">
          감사로그에 이전값·이후값과 함께 남습니다. ({REASON_MAX}자 이내)
        </p>
      </div>
    </Modal>
  )
}
