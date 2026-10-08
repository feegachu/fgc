import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useOutletContext, useParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/errors'
import { dateTime, errorText, int, month as formatMonth } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { withMonth } from '../../stores/workspace'
import { useAuth } from '../auth/useAuth'
import {
  executeRun,
  finalizeChecklistQuery,
  finalizeRun,
  isRunning,
  runDetailQuery,
  runProgressQuery,
  runsQuery,
} from './api'
import type { FinalizeChecklist, ValidationRunDetail, ValidationTarget } from './api'
import { FinalizeDialogs } from './FinalizeDialogs'
import { RunStatusBadge } from './RunStatusBadge'
import { MANUAL_STEP_FROM, progressText, STEP_NAMES, STEP_STATE_TEXT, stepState } from './steps'
import './validation.css'

/*
 * FGC-UI-VRUN-W02 월 통합검증 상세·확정 (FUN-042·043·044). 1차 vrun/detail.html 과 같은 문구·딥링크다.
 * 헤더·대상 선별·결과 요약은 IF-API-47, 실행은 IF-API-48, 진행률은 IF-API-49(2초 폴링),
 * 확정 조건은 IF-API-50, 확정은 IF-API-51(멱등키)이다.
 * [★ 10단계 중 ⑨담당자 검토와 ⑩확정은 배치가 하지 않는다] 사람이 검토하고 사람이 확정한다.
 * [막을 것] 조건 미충족 확정(버튼 비활성 + 서버도 거부), 확정 취소·되돌리기(만들지 않는다).
 */
const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))

export default function ValidationRunDetailPage() {
  const { id = '' } = useParams()
  const detail = useQuery(runDetailQuery(id))
  if (detail.isPending) {
    return (
      <div className="page-content vrun-page vrun-detail-page">
        <p className="vrun-inline-state is-loading" role="status">
          불러오는 중입니다.
        </p>
      </div>
    )
  }
  if (detail.error) return <DetailError error={detail.error} onRetry={() => void detail.refetch()} />
  return <DetailBody key={id} id={id} detail={detail.data} refetchDetail={() => void detail.refetch()} />
}

function DetailError({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const notFound = error instanceof ApiError && (error.status === 404 || error.code === 'FGC-COMMON-004')
  return (
    <div className="page-content vrun-page vrun-detail-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">월 통합검증 상세 · 확정</h1>
        </div>
      </header>
      <section className="surface">
        <p className="vrun-inline-state is-error" role="alert">
          {notFound ? '존재하지 않는 검증 실행입니다.' : errorText(error, '실행 정보를 불러오지 못했습니다.')}
        </p>
        <p className="vrun-inline-state">
          {notFound ? (
            <Link className="button button-secondary" to="/validation-runs">
              실행 목록으로
            </Link>
          ) : (
            <Button variant="secondary" onClick={onRetry}>
              다시 시도
            </Button>
          )}
        </p>
      </section>
    </div>
  )
}

function DetailBody({
  id,
  detail,
  refetchDetail,
}: {
  id: string
  detail: ValidationRunDetail
  refetchDetail: () => void
}) {
  const { month: shellMonth } = useOutletContext<ShellContext>()
  const { user } = useAuth()
  const client = useQueryClient()
  const header = detail.header ?? {}
  const [executed, setExecuted] = useState(false)
  const polling = executed || header.status === 'RUNNING'
  const progress = useQuery(runProgressQuery(id, polling))
  // 확정(FINALIZED)은 폴링 응답보다 늦게 도착한 헤더가 정답이다 — 이전 폴링 값이 확정 상태를 덮지 않게 한다.
  const live = header.status === 'FINALIZED' ? undefined : progress.data
  const status = live?.status ?? header.status
  const currentStep = live?.currentStep ?? header.currentStep ?? 0
  const failureMessage = live?.failureMessage ?? header.failureMessage

  const checklist = useQuery(finalizeChecklistQuery(id, status === 'COMPLETED'))
  const canProcess = user?.canProcess === true
  const canFinalize = user?.canFinalizeValidation === true

  // 폴링이 종료 상태에 닿으면 헤더·결과 요약·확정 조건을 다시 불러온다(1차는 전체 리로드였다).
  const finished = live && !isRunning(live.status)
  const announced = useRef(false)
  useEffect(() => {
    if (!finished || announced.current) return
    announced.current = true
    toast('검증 계산이 끝났습니다. 결과를 불러옵니다.', 'success', 3000)
    void client.invalidateQueries({ queryKey: ['validation-runs'] })
  }, [finished, client])

  // 일시 오류는 다음 폴링에서 회복되므로 연속 3회 실패해야 한 번만 알린다.
  const warned = useRef(false)
  useEffect(() => {
    if (progress.errorUpdateCount >= 3 && !warned.current) {
      warned.current = true
      toast(
        errorText(progress.error as Error, '진행률을 불러오지 못하고 있습니다. 연결을 확인해 주세요.'),
        'warning',
        6000,
      )
    } else if (warned.current && progress.errorUpdateCount === 0 && progress.isSuccess) {
      warned.current = false
      toast('진행률 조회가 정상으로 돌아왔습니다.', 'info', 3000)
    }
  }, [progress.errorUpdateCount, progress.error, progress.isSuccess])

  const execute = useMutation({
    mutationFn: () => executeRun(id),
    meta: { errorToast: false },
    onSuccess: () => {
      announced.current = false
      setExecuted(true)
      toast('검증 실행을 시작했습니다. 진행률을 2초 간격으로 갱신합니다.', 'info')
    },
    onError: (error) => toast(errorText(error as Error, '실행 요청에 실패했습니다.'), 'error'),
  })

  const link = (path: string) => (shellMonth ? withMonth(path, shellMonth) : path)

  return (
    <div className="page-content vrun-page vrun-detail-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">월 통합검증 상세 · 확정</h1>
        </div>
        <RunPicker currentId={id} link={link} />
      </header>

      {/* 확정 불가역 경고 — 업무·규제 제약이므로 화면 설명문과 달리 남긴다 (운영정책서 제43조) */}
      <div className="guidance guidance-warning">
        <span className="material-symbols-rounded guidance-icon" aria-hidden="true">
          gavel
        </span>
        <div>
          <p>
            <strong>확정하면 이 실행의 결과는 고칠 수 없습니다.</strong> 바로잡으려면 새 실행을 만들어야 합니다.
            (운영정책서 제43조)
          </p>
        </div>
      </div>

      <section className="surface" aria-labelledby="vrun-header-title">
        <header className="surface-header">
          <h2 className="surface-title" id="vrun-header-title">
            실행 <span className="tabular-nums">{header.validationRunId}</span>
          </h2>
          <RunStatusBadge status={status} label={live?.statusLabel ?? header.statusLabel} />
        </header>
        <div className="surface-body">
          <dl className="vrun-kv">
            <div>
              <dt>검증월</dt>
              <dd className="tabular-nums">{formatMonth(header.validationMonth)}</dd>
            </div>
            <div>
              <dt>회차</dt>
              <dd className="tabular-nums">{header.runNo}</dd>
            </div>
            <div>
              <dt>실행 유형</dt>
              <dd>{dash(header.runTypeLabel)}</dd>
            </div>
            <div>
              <dt>실행자</dt>
              <dd>{dash(header.triggeredBy)}</dd>
            </div>
            <div>
              <dt>시작</dt>
              <dd className="tabular-nums">{dateTime(header.startedAt)}</dd>
            </div>
            <div>
              <dt>완료</dt>
              <dd className="tabular-nums">{dateTime(header.completedAt)}</dd>
            </div>
            <div>
              <dt>확정자 · 확정시각</dt>
              <dd className="tabular-nums">
                {header.finalizedAt ? `${header.finalizedBy ?? '-'} · ${dateTime(header.finalizedAt)}` : '-'}
              </dd>
            </div>
          </dl>
          {status === 'FAILED' && (
            <p className="vrun-inline-state is-error" role="alert">
              {failureMessage}
            </p>
          )}
        </div>
      </section>

      <section className="surface" aria-labelledby="vrun-stepper-title">
        <header className="surface-header">
          <h2 className="surface-title" id="vrun-stepper-title">
            진행 단계 (운영정책서 제43조)
          </h2>
          <div className="page-header-actions">
            <Button
              variant="secondary"
              className="vrun-row-button"
              onClick={() => {
                toast('최신 상태를 불러옵니다.', 'info', 2000)
                refetchDetail()
                void progress.refetch()
                void checklist.refetch()
              }}
            >
              <span className="material-symbols-rounded" aria-hidden="true">
                refresh
              </span>
              새로고침
            </Button>
            {/* 실행은 SETTLEMENT (화면정의서 :1485, IF-API-48). CREATED 에서만 기동할 수 있다 — 재기동은 없다. */}
            <Button
              className="vrun-row-button"
              aria-describedby="execute-action-note"
              loading={execute.isPending}
              disabled={!canProcess || status !== 'CREATED' || executed}
              onClick={() => execute.mutate()}
            >
              <span className="material-symbols-rounded" aria-hidden="true">
                play_arrow
              </span>
              실행
            </Button>
          </div>
        </header>
        <div className="surface-body">
          <p
            className="vrun-action-note"
            id="execute-action-note"
            role="status"
            hidden={canProcess && status === 'CREATED' && !executed}
          >
            {status === 'FINALIZED'
              ? '확정된 실행은 다시 돌리지 않습니다. 새 실행을 만드세요.'
              : !canProcess
                ? '실행 기동은 정산 담당자만 할 수 있습니다.'
                : '생성됨 상태의 실행만 기동할 수 있습니다.'}
          </p>
          <p className="vrun-progress-line">
            <span>진행률</span>
            <span className="vrun-progress-value" role="status" aria-live="polite">
              {progressText(currentStep)}
            </span>
          </p>
          <ol className="vrun-stepper" aria-label="월 통합검증 10단계 진행 상태">
            {STEP_NAMES.map((name, index) => {
              const state = stepState(index + 1, status, currentStep)
              return (
                <li
                  key={name}
                  className={`vrun-stepper-step ${state === 'pending' ? '' : `fgc-stepper__step--${state}`}`}
                >
                  <span className="vrun-stepper-dot" aria-hidden="true">
                    {index + 1}
                  </span>
                  <span className="vrun-stepper-label">
                    <span>{name}</span>
                    {index + 1 >= MANUAL_STEP_FROM && <span className="vrun-stepper-manual">사람이 수행</span>}
                  </span>
                  <span className="visually-hidden">{STEP_STATE_TEXT[state]}</span>
                </li>
              )
            })}
          </ol>
        </div>
      </section>

      <div className="vrun-mid-grid">
        <TargetSection detail={detail} />
        <SummarySection detail={detail} status={status} />
      </div>

      <ExceptionSection detail={detail} openLink={link} />

      <ChecklistSection status={status} checklist={checklist} />

      <FinalizeSection
        id={id}
        status={status}
        canFinalize={canFinalize}
        checklist={checklist.data}
        validationMonth={formatMonth(header.validationMonth)}
        runNo={header.runNo}
        onDone={() => {
          void client.invalidateQueries({ queryKey: ['validation-runs'] })
        }}
        onFailed={() => void checklist.refetch()}
      />
    </div>
  )
}

/** 최근 실행 20건 — 고르고 [이동]을 눌러야 넘어간다(1차와 같다). */
function RunPicker({ currentId, link }: { currentId: string; link: (path: string) => string }) {
  const navigate = useNavigate()
  const runs = useQuery(runsQuery({ status: '', page: 1 }))
  const [picked, setPicked] = useState('')
  const value = picked || currentId
  return (
    <div className="page-header-actions">
      <div className="field vrun-run-picker">
        <label className="field-label" htmlFor="pick-run">
          실행
        </label>
        <select
          className="select-control"
          id="pick-run"
          value={value}
          onChange={(event) => setPicked(event.target.value)}
        >
          {(runs.data?.content ?? []).map((run) => (
            <option key={run.validationRunId} value={String(run.validationRunId)}>
              {formatMonth(run.validationMonth)} · {run.runNo}회차 · {run.statusLabel}
            </option>
          ))}
        </select>
      </div>
      <Button variant="secondary" onClick={() => void navigate(link(`/validation-runs/${value}`))}>
        <span className="material-symbols-rounded" aria-hidden="true">
          open_in_new
        </span>
        이동
      </Button>
    </div>
  )
}

const selectionTone = (status?: string) =>
  status === 'REVIEW_REQUIRED' ? 'review' : status === 'EXCLUDED' ? 'neutral' : 'success'

function TargetSection({ detail }: { detail: ValidationRunDetail }) {
  const summary = detail.targetSummary
  const targets = detail.targets ?? []
  const columns: TableColumn<ValidationTarget>[] = [
    { key: 'contract', label: '계약', width: '8rem', cellClassName: 'tabular-nums', render: (t) => t.contractNo },
    {
      key: 'status',
      label: '선별',
      width: '6rem',
      align: 'center',
      render: (t) => <StatusBadge tone={selectionTone(t.selectionStatus)}>{t.selectionStatusLabel}</StatusBadge>,
    },
    {
      key: 'offering',
      label: '상품 판매버전',
      width: '11rem',
      render: (t) => {
        const text = `${t.productName} ${t.offeringVersion}`
        return <CellDisclosure singleLine preview={text} />
      },
    },
    {
      key: 'refund',
      label: '환급률표',
      width: '6.5rem',
      cellClassName: 'tabular-nums',
      render: (t) => dash(t.refundRateTableId),
    },
    {
      key: 'reason',
      label: '사유',
      width: '12rem',
      render: (t) => <CellDisclosure singleLine preview={dash(t.selectionReason)} />,
    },
  ]
  return (
    <section className="surface" aria-labelledby="vrun-target-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-target-title">
          ③ 대상 선별 결과
        </h2>
        <p className="vrun-card-note">
          선정 {summary?.selectedCount ?? 0} · 제외 {summary?.excludedCount ?? 0} · 검토필요{' '}
          {summary?.reviewRequiredCount ?? 0}
        </p>
      </header>
      <DataTable
        caption="이번 실행의 검증 대상 선별 결과"
        className="vrun-target-table"
        viewportClassName="vrun-table-viewport"
        columns={columns}
        rows={targets}
        rowKey={(t) => String(t.validationTargetId)}
      />
    </section>
  )
}

function SummaryCard({ label, value, unit, footer }: { label: string; value?: number; unit: string; footer?: string }) {
  return (
    <div className="kpi-card">
      <p className="kpi-label">{label}</p>
      <p className="kpi-value-row">
        {value === undefined ? (
          <span className="kpi-unit">{unit}</span>
        ) : (
          <>
            <span className="kpi-value tabular-nums">{int(value)}</span>
            <span className="kpi-unit">{unit}</span>
          </>
        )}
      </p>
      {footer && <p className="kpi-card-footer">{footer}</p>}
    </div>
  )
}

/** ④ 결과 요약 4블록 — 결과 테이블을 실행 스코프로 매번 집계한다(FUN-043). 실행 전(CREATED)에는 값이 없다. */
function SummarySection({ detail, status }: { detail: ValidationRunDetail; status?: string }) {
  const cap = detail.capSummary
  const arb = detail.arbitrageSummary
  const ledger = detail.ledgerSummary
  const reco = detail.reconciliationSummary
  const before = status === 'CREATED'
  return (
    <section className="surface" aria-labelledby="vrun-summary-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-summary-title">
          ④ 결과 요약
        </h2>
      </header>
      <div className="kpi-grid vrun-summary-grid">
        <SummaryCard
          label="1,200%"
          unit={before ? '실행 전' : '건 검사'}
          value={before ? undefined : cap?.checkedCount}
          footer={
            before
              ? undefined
              : `위반 ${cap?.violationCount} · 주의 ${cap?.warningCount} · 검토 ${cap?.reviewRequiredCount}`
          }
        />
        <SummaryCard
          label="차익거래"
          unit={before ? '실행 전' : '건 검사'}
          value={before ? undefined : arb?.checkedCount}
          footer={before ? undefined : `검토대상 ${arb?.candidateCount} · 검토 ${arb?.reviewRequiredCount}`}
        />
        <SummaryCard
          label="원장"
          unit={before ? '실행 전' : '건 분개'}
          value={before ? undefined : ledger?.journalCount}
          footer={before ? undefined : `불균형 ${ledger?.imbalanceCount}`}
        />
        <SummaryCard
          label="대사"
          unit={before ? '실행 전' : '건 결과'}
          value={before ? undefined : reco?.resultCount}
          footer={before ? undefined : `불일치 ${reco?.mismatchCount}`}
        />
      </div>
    </section>
  )
}

/** SRC-032: 실행별 검출과 관리자 업무건을 분리해 재실행이 업무량을 부풀리지 않게 한다. */
function ExceptionSection({ detail, openLink }: { detail: ValidationRunDetail; openLink: (path: string) => string }) {
  const summary = detail.exceptionSummary
  const month = detail.header?.validationMonth
  // validationMonth 는 예외함의 검증월 검색조건이다(셸 기준월 month 와는 별개 파라미터).
  const href = openLink(`/exceptions?status=OPEN&validationMonth=${encodeURIComponent(month ?? '')}`)
  return (
    <section className="surface" aria-labelledby="vrun-exception-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-exception-title">
          예외 생성 결과
        </h2>
        <Link className="button button-secondary vrun-row-button" to={href}>
          예외함 열기
        </Link>
      </header>
      <div className="kpi-grid vrun-exception-grid">
        <SummaryCard label="이번 실행 검출" value={summary?.detectedCount ?? 0} unit="건" />
        <SummaryCard label="신규 업무건" value={summary?.newCount ?? 0} unit="건" />
        <SummaryCard label="기존 업무건 재검출" value={summary?.recurringCount ?? 0} unit="건" />
        <SummaryCard label="해결 후 재발" value={summary?.reopenedCount ?? 0} unit="건" />
        <SummaryCard label="이번 실행 미검출" value={summary?.notDetectedCount ?? 0} unit="건" />
        <SummaryCard label="미처리 업무건" value={summary?.openWorkItemCount ?? 0} unit="건" />
      </div>
    </section>
  )
}

/** 체크리스트 링크는 사이트 내부 경로만 쓴다 — 서버 응답이라도 외부 주소로 보내지 않는다. */
function safeInternalLink(linkUrl?: string | null) {
  if (!linkUrl || !linkUrl.startsWith('/') || linkUrl.startsWith('//') || linkUrl.includes('\\')) return null
  return linkUrl
}

function ChecklistSection({
  status,
  checklist,
}: {
  status?: string
  checklist: ReturnType<typeof useQuery<FinalizeChecklist>>
}) {
  const data = checklist.data
  let summary: { text: string; tone: 'success' | 'error' | 'review' | null } = { text: '확인 중', tone: null }
  let body: React.ReactNode
  if (status === 'FINALIZED') {
    summary = { text: '확정 완료', tone: 'review' }
    body = (
      <p className="vrun-inline-state is-empty" role="status">
        확정된 실행의 결과와 계산 근거가 잠겼습니다.
      </p>
    )
  } else if (status !== 'COMPLETED') {
    summary = { text: '확인 대기', tone: null }
    body = (
      <p className="vrun-inline-state is-empty" role="status">
        검증 실행이 완료되면 확정 조건을 확인할 수 있습니다.
      </p>
    )
  } else if (checklist.isError) {
    summary = { text: '조회 실패', tone: 'error' }
    body = (
      <p className="vrun-inline-state is-error" role="alert">
        {errorText(checklist.error as Error, '확정 조건을 불러오지 못했습니다.')}
        <Button variant="secondary" onClick={() => void checklist.refetch()}>
          다시 시도
        </Button>
      </p>
    )
  } else if (data) {
    summary = data.passed
      ? { text: '6개 조건 모두 통과', tone: 'success' }
      : { text: '미충족 조건 있음', tone: 'error' }
    body = (
      <div className="vrun-checklist">
        {data.conditions?.map((condition) => {
          const to = condition.passed ? null : safeInternalLink(condition.linkUrl)
          return (
            <div className="vrun-checklist-item" key={condition.no}>
              <div className="vrun-checklist-label">
                <CellDisclosure preview={`${condition.no}. ${condition.label}`} />
              </div>
              <div className="vrun-checklist-result">
                <StatusBadge tone={condition.passed ? 'success' : 'error'}>
                  {condition.passed ? '통과' : `미충족 ${condition.count}건`}
                </StatusBadge>
                {to && (
                  <Link to={to} aria-label={`${condition.label} 미충족 건 바로가기`}>
                    바로가기
                  </Link>
                )}
              </div>
            </div>
          )
        })}
      </div>
    )
  } else {
    body = (
      <p className="vrun-inline-state is-loading" role="status">
        확정 조건을 불러오는 중입니다.
      </p>
    )
  }
  return (
    <section className="surface" aria-labelledby="vrun-checklist-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-checklist-title">
          ⑤ 확정 조건 (운영정책서 제44조 · 6개 전부 통과해야 함)
        </h2>
        {summary.tone ? (
          <StatusBadge tone={summary.tone}>{summary.text}</StatusBadge>
        ) : (
          <span className="vrun-card-note">{summary.text}</span>
        )}
      </header>
      <div className="surface-body" aria-live="polite" aria-busy={status === 'COMPLETED' && checklist.isPending}>
        {body}
      </div>
    </section>
  )
}

function newKey(id: string) {
  const random = globalThis.crypto?.randomUUID?.() ?? String(Date.now())
  return `vrun-finalize-${id}-${random}`
}

function FinalizeSection({
  id,
  status,
  canFinalize,
  checklist,
  validationMonth,
  runNo,
  onDone,
  onFailed,
}: {
  id: string
  status?: string
  canFinalize: boolean
  checklist?: FinalizeChecklist
  validationMonth: string
  runNo?: number
  onDone: () => void
  onFailed: () => void
}) {
  const [confirming, setConfirming] = useState(false)
  const [finalized, setFinalized] = useState(false)
  // 멱등키는 성공하거나 FGC-VRUN-006(다른 실행에 귀속된 키)을 받을 때까지 같은 값으로 재시도한다.
  const key = useRef<string | null>(null)
  const mutation = useMutation({
    mutationFn: () => {
      key.current ??= newKey(id)
      return finalizeRun(id, key.current)
    },
    meta: { errorToast: false },
    onSuccess: () => {
      setConfirming(false)
      setFinalized(true)
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'FGC-VRUN-006') key.current = null
      setConfirming(false)
      toast(errorText(error as Error, '검증 실행 확정에 실패했습니다.'), 'error', 5000)
      onFailed()
    },
  })
  const passed = checklist?.passed === true
  const enabled = canFinalize && status === 'COMPLETED' && passed && !mutation.isPending
  let reason = ''
  if (!canFinalize) reason = '확정 권한은 GA_ADMIN 또는 SYSTEM_ADMIN 에게만 있습니다.'
  else if (status === 'FINALIZED') reason = '이미 확정된 실행입니다. 결과를 바꾸려면 새 실행을 만드세요.'
  else if (status !== 'COMPLETED') reason = '검증 계산이 끝난 실행만 확정할 수 있습니다.'
  else if (!passed) reason = '확정 조건 6개를 모두 통과해야 합니다. 아래 체크리스트를 확인하세요.'
  return (
    <section className="surface" aria-labelledby="vrun-finalize-section-title">
      <div className="vrun-finalize-row">
        <div className="vrun-finalize-copy">
          <p className="vrun-finalize-title" id="vrun-finalize-section-title">
            ⑥ 확정 (FINALIZED)
          </p>
          <p className="field-helper vrun-finalize-guide" id="finalize-action-guide">
            위 6개 조건이 모두 통과한 실행만 확정할 수 있습니다.
            <br />
            확정하면 이 실행의 검증 결과와 그때 쓰인 룰셋·계산근거가 <strong>잠깁니다</strong>.{' '}
            <strong>검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.</strong>
            <br />
            <strong>되돌릴 수 없습니다.</strong> 결과를 바꾸려면 새 실행을 만드세요. 확정 권한은{' '}
            <span className="tabular-nums">GA_ADMIN</span> · <span className="tabular-nums">SYSTEM_ADMIN</span> 에게만
            있습니다.
          </p>
          {/* 비활성 사유는 title 이 아니라 가시 텍스트로 전한다 — disabled 버튼은 포커스가 가지 않는다. */}
          <p className="vrun-action-note" id="finalize-action-note" role="status" hidden={!reason}>
            {reason}
          </p>
        </div>
        <Button
          id="btn-finalize"
          aria-describedby="finalize-action-guide finalize-action-note"
          loading={mutation.isPending}
          disabled={!enabled}
          onClick={() => setConfirming(true)}
        >
          <span className="material-symbols-rounded" aria-hidden="true">
            lock
          </span>
          <span>{status === 'FINALIZED' ? '확정됨' : '확정'}</span>
        </Button>
      </div>
      <FinalizeDialogs
        confirming={confirming}
        finalized={finalized}
        pending={mutation.isPending}
        validationMonth={validationMonth}
        runNo={runNo}
        onCancel={() => setConfirming(false)}
        onConfirm={() => mutation.mutate()}
        onClosed={() => {
          setFinalized(false)
          onDone()
        }}
      />
    </section>
  )
}
