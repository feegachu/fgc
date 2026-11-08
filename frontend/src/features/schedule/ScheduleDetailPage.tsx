import { ScheduleLinesTable } from './ScheduleLinesTable'
import { SchedulePreviewForm } from './SchedulePreviewForm'
import { SaveGuide } from '../assistant/guide/save-guide'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useUnsavedWork } from '../../stores/unsavedWork'
import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useLocation } from 'react-router'
import { paymentEntryHref, safeReturnTo } from '../transaction/payment-entry'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { Modal } from '../../components/Modal'
import { ApiError } from '../../lib/api/client'
import { exportCsv } from '../../lib/exportCsv'
import { dateTime, errorText, int, isNegative, won } from '../../lib/format'
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
import type { ScheduleHeader } from './api'
import { ActiveBadge, ClassificationBadge, RegimeEvidence, ScheduleStatusBadge } from './badges'
import {
  EMPTY,
  generationReason,
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
  parts.filter((part) => part != null && part !== '' && part !== EMPTY).join(', ') || EMPTY
const Money = ({ amount }: { amount: unknown }) => (
  <span className={isNegative(amount) ? 'is-negative-amount' : undefined}>{amount == null ? '자료 없음' : won(amount)}</span>
)
const Text = ({ content }: { content: unknown }) => <CellDisclosure singleLine preview={value(content)} />

function InlineState({ message, error, onRetry }: { message: string; error?: boolean; onRetry?: () => void }) {
  return (
    <p className={`schedule-inline-state ${error ? 'is-error' : 'is-loading'}`} role={error ? 'alert' : 'status'}>
      {message}
      {onRetry && (
        <Button data-guide-feature="features.schedule.schedule-detail-page.button" variant="secondary" onClick={onRetry}>
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
  const location = useLocation()
  const canProcess = usePermission('canProcess')
  const detail = useQuery({ ...scheduleDetailQuery(id), enabled: validId })
  // 1차처럼 헤더를 받은 뒤에 버전 이력을 부른다.
  const versions = useQuery({ ...scheduleVersionsQuery(id), enabled: detail.isSuccess })
  const header = detail.data?.header
  const lines = detail.data?.lines ?? []
  const [infoTab, setInfoTab] = useState<'info' | 'versions'>('info')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [regenerateOpen, setRegenerateOpen] = useState(false)
  const previewForm = useRef<HTMLFieldSetElement>(null)

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

  const previewOnly = header?.scheduleRegime === 'FOUR_YEAR_2027' || header?.scheduleRegime === 'SEVEN_YEAR_2029'
  const unavailable = !detail.isSuccess || detail.isFetching
  const confirmDisabled =
    unavailable ||
    previewOnly ||
    header?.canConfirm === false ||
    !canProcess ||
    header?.status !== 'PLANNED' ||
    header.activeYn !== true
  const hasActiveVersion = header?.activeYn === true && header.status !== 'CANCELLED'
  const previewRegime = detail.data?.previewContext?.policy?.selection?.resolution?.declaredFeeRegimeCode
  const canOpenPreview = Boolean(header?.contractId && header.paymentStage && previewRegime === header.scheduleRegime)
  const regenerateDisabled = unavailable || !canProcess || !hasActiveVersion || (previewOnly && !canOpenPreview)

  function openRegeneration() {
    if (!previewOnly) {
      setRegenerateOpen(true)
      return
    }
    previewForm.current?.scrollIntoView?.({ block: 'center' })
    previewForm.current?.querySelector('input')?.focus({ preventScroll: true })
  }
  const reasons: string[] = []
  if (header) {
    if (header.canConfirm === false && !previewOnly && header.activeYn === true && header.status === 'PLANNED') {
      reasons.push(header.confirmBlockedReason === 'SCHEDULE_NOT_ACTIVE_PLANNED'
        ? '사용 중인 예정 상태의 스케줄만 확정할 수 있습니다.'
        : header.confirmBlockedReason === 'FGC-IMPL-SCHEDULE_PREVIEW_ONLY'
          ? '4년·7년 예상 스케줄은 지급 확정 대상이 아닙니다.'
          : '확정할 수 없는 상태입니다.')
    }
    if (previewOnly) reasons.push('4년·7년 예상 스케줄은 지급 확정 대상이 아닙니다.')
    if (!canProcess) reasons.push('확정과 재생성은 정산담당자와 시스템관리자만 할 수 있습니다.')
    if (header.activeYn !== true) reasons.push('사용 중인 버전이 아닙니다.')
    if (header.status === 'CONFIRMED') reasons.push('이미 확정된 스케줄입니다.')
    else if (header.status !== 'PLANNED') reasons.push('예정 상태에서만 확정할 수 있습니다.')
  }
  const showNote = header && (confirmDisabled || regenerateDisabled) && reasons.length > 0
  const retry = () => void detail.refetch()
  const loadError = !validId
    ? '스케줄을 찾을 수 없습니다.'
    : detail.isError
      ? errorText(detail.error, '예상 스케줄을 불러오지 못했습니다.')
      : null

  return (
    <div className="schedule-detail-page">
      <header className="page-header schedule-page-header">
        <ScreenHelp screenId="SCHE-W02" />
        <div className="page-header-copy">
          <h1 className="page-title">예상 스케줄 상세</h1>
        </div>
        <div className="page-header-actions">
          <Link data-guide-feature="features.schedule.schedule-detail-page.link"
            className="button button-secondary"
            to={safeReturnTo(new URLSearchParams(location.search).get('returnTo'), '/schedules')}
          >
            목록으로
          </Link>
          <Button data-guide-feature="features.schedule.schedule-detail-page.button-2"
            variant="secondary"
            loading={exporting.isPending}
            disabled={!validId || unavailable}
            onClick={() => exporting.mutate()}
          >
            <span className="material-symbols-rounded" aria-hidden="true">
              download
            </span>
            CSV 내보내기
          </Button>
          <Button data-guide-feature="features.schedule.schedule-detail-page.button-3"
            variant="secondary"
            data-guide-target="schedule.generate" disabled={regenerateDisabled}
            aria-describedby={showNote ? 'schedule-action-note' : undefined}
            onClick={openRegeneration}
          >
            <span className="material-symbols-rounded" aria-hidden="true">
              restart_alt
            </span>
            새 버전 만들기
          </Button>
          <Button data-guide-feature="features.schedule.schedule-detail-page.button-4"
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

      {header && detail.isFetching && <InlineState message="스케줄을 갱신 중입니다. 최신 상태를 확인한 후 처리할 수 있습니다." />}
      {header && loadError && <InlineState message={loadError} error onRetry={retry} />}

      {/* 비활성 사유는 title 이 아니라 가시 텍스트로 둔다 — 비활성 버튼은 포커스가 가지 않는다. */}
      {showNote && (
        <p className="schedule-action-note" id="schedule-action-note" role="status">
          {reasons.join(' ')}
        </p>
      )}

      <section data-guide-feature="features.schedule.schedule-detail-page.section" className="surface schedule-detail-card" aria-labelledby="schedule-header-title">
        <header className="surface-header schedule-info-header">
          <h2 className="surface-title" id="schedule-header-title">
            스케줄 기본정보
          </h2>
          {/* 지급단계는 이동 컨트롤이다(화면정의서 SCHE-W02, IF-API-28C). */}
          <div className="schedule-info-actions">
            <div className="schedule-info-tabs" role="tablist" aria-label="스케줄 정보 보기"
              onKeyDown={event => {
                if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
                event.preventDefault()
                const next = event.key === 'Home' ? 'info' : event.key === 'End' ? 'versions' : infoTab === 'info' ? 'versions' : 'info'
                setInfoTab(next)
                document.getElementById(`schedule-${next}-tab`)?.focus()
              }}>
              {(['info', 'versions'] as const).map(tab => <button data-guide-feature="features.schedule-detail-page.button-1" type="button" role="tab" key={tab}
                id={tab === 'info' ? 'schedule-info-tab' : 'schedule-versions-tab'} aria-controls={`schedule-${tab}-panel`} aria-selected={infoTab === tab}
                tabIndex={infoTab === tab ? 0 : -1} onClick={() => setInfoTab(tab)}>
                {tab === 'info' ? '기준정보' : '버전비교'}
              </button>)}
            </div>
            <div className="filter-field schedule-stage-field">
              <label className="filter-field-label" htmlFor="schedule-stage">
                지급단계
              </label>
              <select data-guide-feature="features.schedule.schedule-detail-page.schedule-stage"
                className="select-control"
                id="schedule-stage"
                value={header?.paymentStage ?? 'GA_TO_FC'}
                disabled={unavailable || previewOnly || !header?.paymentStage || moving.isPending}
                aria-busy={moving.isPending || undefined}
                onChange={(event) => {
                  if (event.target.value !== header?.paymentStage) moving.mutate(event.target.value)
                }}
              >
                <option value="GA_TO_FC">{PAYMENT_STAGE.GA_TO_FC}</option>
                <option value="INSURER_TO_GA">{PAYMENT_STAGE.INSURER_TO_GA}</option>
              </select>
            </div>
          </div>
        </header>
        <div className="surface-body" role="tabpanel" id="schedule-info-panel" aria-labelledby="schedule-info-tab" hidden={infoTab !== 'info'}>
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
        <div role="tabpanel" id="schedule-versions-panel" aria-labelledby="schedule-versions-tab" hidden={infoTab !== 'versions'}>
          <section data-guide-feature="features.schedule.schedule-detail-page.section-3" className="schedule-version-panel" aria-labelledby="schedule-versions-title">
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
              <table data-guide-feature="features.schedule.schedule-detail-page.table-2" className="data-table schedule-version-table" aria-busy={versions.isFetching}>
                <caption className="visually-hidden">같은 계약의 스케줄 버전 이력</caption>
                <colgroup>
                  {['no', 'status', 'active', 'policy', 'reason', 'generated', 'lines', 'total'].map((name) => (
                    <col key={name} className={`schedule-version-col-${name}`} />
                  ))}
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col" className="is-left">
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
                    <th scope="col" className="is-left">
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
        </div>
      </section>

      <section data-guide-feature="features.schedule.schedule-detail-page.section-2" data-guide-target="schedule.lines" className="surface schedule-detail-card" aria-labelledby="schedule-lines-title">
        <header className="surface-header schedule-lines-header">
          <h2 className="surface-title" id="schedule-lines-title">
            회차별 예상 금액
          </h2>
          {header && (
            <p className="schedule-lines-total">예상 총액 <Money amount={header.expectedTotal} /></p>
          )}
          <p className="schedule-detail-note">
            줄마다 원 단위로 반올림(5 이상 올림)한 뒤 더합니다
          </p>
        </header>
        <ScheduleLinesTable
          lines={header ? lines : []}
          total={header?.expectedTotal}
          emptyMessage={!header ? <InlineState message={loadError ?? '예상 스케줄을 불러오는 중입니다.'} error={Boolean(loadError)} onRetry={validId && detail.isError ? retry : undefined} /> : '등록된 회차가 없습니다.'}
          paymentHref={line => canProcess && !unavailable && !previewOnly && line.contractId && line.commissionItemId
            ? paymentEntryHref({
                contractId: String(line.contractId),
                agentId: line.beneficiaryAgentId == null ? undefined : String(line.beneficiaryAgentId),
                commissionItemId: String(line.commissionItemId),
                attributionDate: line.attributionDate,
                paymentStage: header?.paymentStage,
                cashflowType: line.cashflowType === 'DEDUCTION' ? 'DEDUCTION' : 'PAYMENT',
                amount: line.expectedAmount == null ? undefined : String(Math.abs(Number(line.expectedAmount))),
              }, location.pathname + location.search, line.attributionDate?.slice(0, 7))
            : undefined}
        />
      </section>

      {hasActiveVersion && header?.contractId && header.paymentStage && (
        <fieldset ref={previewForm} disabled={unavailable} className="schedule-preview-availability">
          <SchedulePreviewForm
            context={detail.data?.previewContext}
            contractId={String(header.contractId)}
            stage={header.paymentStage}
            scheduleId={id}
          />
        </fieldset>
      )}
      {previewOnly && header && <p role="status">정책 정의 {header.installmentCount ?? '-'}회차 · {header.lineCount ?? '-'}행. 실제 정책 구간만 표시하며 빈 회차를 추가하지 않습니다.</p>}


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
      {regenerateOpen && <RegenerateModal
        open={regenerateOpen}
        onClose={() => setRegenerateOpen(false)}
        onSubmit={async (reason, committed) => {
          const result = await regenerateSchedule(id, reason, () => {
            committed()
            void client.invalidateQueries({ queryKey: ['schedules'] })
          })
          if (Number(result.scheduleHeaderId) === Number(id) || !Number.isSafeInteger(result.versionNo) || result.versionNo! <= (header?.scheduleVersionNo ?? 0)) throw new Error('새 스케줄 버전을 확인하지 못했습니다. 목록을 새로 조회하세요.')
          return result.scheduleHeaderId!
        }}
        onComplete={(newId) => {
          setRegenerateOpen(false)
          toast('새 스케줄 버전을 만들었습니다. 새 버전으로 이동합니다.', 'success', 3500)
          void navigate(`/schedules/${encodeURIComponent(String(newId))}`)
        }}
      />}
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
        <dt>보험회사, 상품</dt>
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
        <dt>생성 사유, 일시</dt>
        <dd>{join(generationReason(header.generationReason), dateTime(header.generatedAt))}</dd>
      </div>
    </dl>
  )
}

function VersionRow({ version, current }: { version: ScheduleHeader; current: boolean }) {
  return (
    <tr className={current ? 'is-selected' : undefined} aria-current={current || undefined}>
      <td className="is-left">
        <Link data-guide-feature="features.schedule.schedule-detail-page.link-3" className="tabular-nums" to={`/schedules/${encodeURIComponent(String(version.scheduleHeaderId))}`}>
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
        <Text content={generationReason(version.generationReason)} />
      </td>
      <td>{dateTime(version.generatedAt)}</td>
      <td className="is-left tabular-nums">{int(version.installmentCount)}</td>
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
          <button data-guide-feature="features.schedule.schedule-detail-page.button-5" ref={cancel} type="button" className="button button-secondary" onClick={close}>
            취소
          </button>
          <Button data-guide-feature="features.schedule.schedule-detail-page.button-6" loading={mutation.isPending} onClick={() => mutation.mutate()}>
            확정
          </Button>
        </>
      }
    >
      <p className="schedule-modal-description">
        되돌릴 수 없습니다, 확정된 스케줄은 새 버전으로만 바꿉니다. 확정 후에는 금액을 고칠 수 없고, 고치려면{' '}
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
          <dd className="tabular-nums">{int(header.installmentCount)}</dd>
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
  onComplete,
}: {
  open: boolean
  onClose: () => void
  onSubmit: (reason: string, committed: () => void) => Promise<number>
  onComplete: (id: number) => void
}) {
  const [saveGuide] = useState(() => new SaveGuide())
  const saved = useSyncExternalStore(saveGuide.subscribe, saveGuide.getSnapshot).savedId !== null
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const input = useRef<HTMLTextAreaElement>(null)
  const [reason, setReason] = useState('')
  const stored = useRef(false)
  const [committed, setCommitted] = useState(false)
  const clearUnsaved = useUnsavedWork(open && !committed && Boolean(reason), reason)
  const [error, setError] = useState('')
  useEffect(() => { saveGuide.update(reason, Boolean(reason.trim()) && reason.trim().length <= REASON_MAX) }, [saveGuide, reason])
  const mutation = useMutation({
    mutationFn: async (value: string) => {
      saveGuide.begin()
      const newId = await onSubmit(value, () => {
        stored.current = true
        clearUnsaved()
        if (mounted.current) setCommitted(true)
      })
      if (!mounted.current) return
      if (await saveGuide.saved(newId)) { if (mounted.current) onComplete(newId) }
    },
    meta: { errorToast: false },
    onError: (caught) => {
      if (stored.current) {
        const message = '생성은 되었지만 결과를 표시하지 못했습니다. 다시 생성하지 말고 목록에서 새 버전을 확인하세요.'
        saveGuide.fail(message)
        setError(message)
        toast(message, 'warning')
        return
      }
      saveGuide.fail(errorText(caught, '새 스케줄 버전을 만들지 못했습니다.'))
      // 서버 검증(@NotBlank·@Size)이 사유 필드를 지목하면 Toast 가 아니라 필드 옆에 둔다(가이드 §11).
      if (caught instanceof ApiError && caught.field === 'reason') setError(caught.message)
      else toast(errorText(caught, '새 스케줄 버전을 만들지 못했습니다.'), 'error', 5000)
    },
  })
  const close = () => {
    if (mutation.isPending) return
    setReason('')
    setError('')
    mutation.reset()
    onClose()
  }
  function submit() {
    if (stored.current || mutation.isPending) return
    const trimmed = reason.trim()
    const message = !trimmed
      ? '저장할 수 없습니다. 생성 사유를 입력하세요.'
      : trimmed.length > REASON_MAX
        ? `생성 사유는 ${REASON_MAX}자 이하여야 합니다.`
        : ''
    setError(message)
    if (message) {
      input.current?.focus()
      return
    }
    if (!mutation.isPending) mutation.mutate(trimmed)
  }
  return (
    <Modal
      open={open}
      title="새 버전 만들기"
      className="schedule-modal"
      closeOnBackdrop={!mutation.isPending}
      closeOnEscape={!mutation.isPending}
      initialFocusRef={input}
      onClose={close}
      footer={
        <>
          <button data-guide-feature="features.schedule.schedule-detail-page.button-7" type="button" className="button button-secondary" disabled={mutation.isPending} onClick={close}>
            취소
          </button>
          <Button data-guide-feature="features.schedule.schedule-detail-page.button-8" data-guide-target="schedule.regenerate.submit" loading={mutation.isPending && !saved} disabled={mutation.isPending || committed} onClick={submit}>
            새 버전 만들기
          </Button>
        </>
      }
    >
      <ScreenHelp screenId="SCHE-W02" variant="regenerate" saveGuide={saveGuide} />
      {saved && <p role="status">새 스케줄 버전을 만들었습니다. 안내에서 다음을 누르세요.</p>}
      <p className="schedule-modal-description">
        기존 스케줄은 남기고 새 버전을 만듭니다.
      </p>
      <div className={`field ${error ? 'is-error' : ''}`}>
        <label className="field-label" htmlFor="regenerate-reason">
          생성 사유
          <span className="field-required" aria-hidden="true">
            {' '}
            *
          </span>
        </label>
        <textarea data-guide-feature="features.schedule.schedule-detail-page.regenerate-reason"
          ref={input}
          data-guide-target="schedule.regenerate.reason"
          disabled={mutation.isPending || committed}
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
          감사로그에 이전값/이후값과 함께 남습니다. ({REASON_MAX}자 이내)
        </p>
      </div>
    </Modal>
  )
}
