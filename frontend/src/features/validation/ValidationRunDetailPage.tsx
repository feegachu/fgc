import { SaveGuide } from '../assistant/guide/save-guide'
import { useRunGuide } from '../assistant/guide/useRunGuide'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useOutletContext, useParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { KpiCardContent } from '../../components/KpiCard'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/errors'
import { dateTime, errorText, month as formatMonth } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { withMonth } from '../../stores/workspace'
import { useAuth } from '../auth/useAuth'
import {
  executeRun,
  hasNewAttempt,
  finalizeChecklistQuery,
  finalizeRun,
  isRunning,
  runDetailQuery,
  runProgressQuery,
  runsQuery,
} from './api'
import type { FinalizeChecklist, ValidationRunDetail, ValidationTarget } from './api'
import { FinalizeDialogs } from './FinalizeDialogs'
import { ValidationRestartPanel } from './ValidationRestartPanel'
import { RunStatusBadge } from './RunStatusBadge'
import { failureText, MANUAL_STEP_FROM, progressText, STEP_NAMES, STEP_STATE_TEXT, stepState } from './steps'
import { useRestartAttemptDeadline } from './useRestartAttemptDeadline'
import { useRunPresentation } from './useRunPresentation'
import { useRunProgressToast } from './useRunProgressToast'
import { AnimatedRunChecklist } from './AnimatedRunChecklist'
import { safeInternalLink } from './utils'
import { RunSummaryCards, RunExceptionCards } from './RunResultCards'
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
      <div className="vrun-page vrun-detail-page">
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
    <div className="vrun-page vrun-detail-page">
      <header className="page-header">
        <ScreenHelp screenId="VRUN-W02" />
        <div className="page-header-copy">
          <h1 className="page-title">월 통합검증 상세/확정</h1>
        </div>
      </header>
      <section data-guide-feature="features.validation.validation-run-detail-page.section" className="surface">
        <p className="vrun-inline-state is-error" role="alert">
          {notFound ? '존재하지 않는 검증 실행입니다.' : errorText(error, '실행 정보를 불러오지 못했습니다.')}
        </p>
        <p className="vrun-inline-state">
          {notFound ? (
            <Link data-guide-feature="features.validation.validation-run-detail-page.link" className="button button-secondary" to="/validation-runs">
              실행 목록으로
            </Link>
          ) : (
            <Button data-guide-feature="features.validation.validation-run-detail-page.button" variant="secondary" onClick={onRetry}>
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
  const { month: shellMonth, helpVersion } = useOutletContext<ShellContext>()
  const [guidePreview, setGuidePreview] = useState(false)
  const [guideGeneration, setGuideGeneration] = useState(0)
  const guidePresentation = useRunPresentation('COMPLETED', 8, guideGeneration, guidePreview, false)
  useEffect(() => {
    const start = () => { setGuideGeneration(value => value + 1); setGuidePreview(true) }
    const stop = () => setGuidePreview(false)
    window.addEventListener('fgc-validation-guide-preview-start', start)
    window.addEventListener('fgc-validation-guide-preview-stop', stop)
    return () => { window.removeEventListener('fgc-validation-guide-preview-start', start); window.removeEventListener('fgc-validation-guide-preview-stop', stop) }
  }, [])
  useEffect(() => {
    if (!guidePreview || guidePresentation.catchingUp) return
    const timer = window.setTimeout(() => setGuideGeneration(value => value + 1), 1600)
    return () => window.clearTimeout(timer)
  }, [guidePreview, guidePresentation.catchingUp])
  const { user } = useAuth()
  const client = useQueryClient()
  const header = detail.header ?? {}
  const [executed, setExecuted] = useState(false)
  const [previousAttemptId, setPreviousAttemptId] = useState<number | null>(null)
  const [attemptWaitGeneration, setAttemptWaitGeneration] = useState(0)
  const attemptWaitExpired = useRestartAttemptDeadline(executed && previousAttemptId !== null, attemptWaitGeneration)
  const polling =
    executed ||
    header.status === 'RUNNING' ||
    (header.status === 'FAILED' && header.runType !== 'MANUAL_CONTRACT' && user?.canProcess === true)
  const progress = useQuery(runProgressQuery(id, polling, previousAttemptId, attemptWaitExpired))
  const startUnconfirmed = attemptWaitExpired && !hasNewAttempt(progress.data, previousAttemptId)
  // 확정(FINALIZED)은 폴링 응답보다 늦게 도착한 헤더가 정답이다 — 이전 폴링 값이 확정 상태를 덮지 않게 한다.
  const live =
    header.status === 'FINALIZED' || !hasNewAttempt(progress.data, previousAttemptId) ? undefined : progress.data
  const status = live?.status ?? header.status
  const currentStep = live?.currentStep ?? header.currentStep ?? 0
  const failureMessage = live?.failureMessage ?? header.failureMessage
  const revised = helpVersion !== 'before'
  const awaitingAttempt = executed && previousAttemptId !== null && !hasNewAttempt(progress.data, previousAttemptId)
  const presentation = useRunPresentation(status, currentStep, attemptWaitGeneration, revised, awaitingAttempt)
  const displayed = presentation
  const resultDetail = detail
  const resultStep = Math.min(displayed.completed, resultDetail.header?.currentStep ?? 0)

  // Results are fetched at server milestones, independently of the slower visual playback.
  useEffect(() => {
    if (!revised || awaitingAttempt || currentStep < 2) return
    void client.invalidateQueries({ queryKey: ['validation-runs', 'detail', id], exact: true })
  }, [client, id, revised, currentStep, status, awaitingAttempt])

  const checklist = useQuery(finalizeChecklistQuery(id, status === 'COMPLETED'))
  const canProcess = user?.canProcess === true
  const canFinalize = user?.canFinalizeValidation === true
  const canExecute = (status === 'CREATED') && !startUnconfirmed

  // 폴링이 종료 상태에 닿으면 헤더·결과 요약·확정 조건을 다시 불러온다(1차는 전체 리로드였다).
  const finished = (executed || header.status === 'RUNNING') && live && !isRunning(live.status)
  const announced = useRef(false)
  useEffect(() => {
    if (!finished || announced.current) return
    announced.current = true
    toast(status === 'FAILED' ? '검증이 중단되었습니다. 실패 사유를 확인해 주세요.' : '검증 계산이 끝났습니다. 결과를 불러옵니다.', status === 'FAILED' ? 'error' : 'success', 3000)
    void client.invalidateQueries({ queryKey: ['validation-runs'] })
  }, [finished, client, status])

  // 일시 오류는 다음 폴링에서 회복되므로 연속 3회 실패해야 한 번만 알린다.
  // errorUpdateCount 는 누적값이라, 마지막 성공 시점의 값을 기준으로 연속 실패만 센다.
  const warned = useRef(false)
  const baseErrors = useRef(0)
  useEffect(() => {
    if (progress.isSuccess && !progress.isError) {
      if (warned.current) {
        warned.current = false
        toast('진행률 조회가 정상으로 돌아왔습니다.', 'info', 3000)
      }
      baseErrors.current = progress.errorUpdateCount
    } else if (progress.errorUpdateCount - baseErrors.current >= 3 && !warned.current) {
      warned.current = true
      toast(
        errorText(progress.error as Error, '진행률을 불러오지 못하고 있습니다. 연결을 확인해 주세요.'),
        'warning',
        6000,
      )
    }
  }, [progress.errorUpdateCount, progress.dataUpdatedAt, progress.error, progress.isSuccess, progress.isError])

  const [executionGuide] = useState(() => new SaveGuide())
  const [finalizeGuide] = useState(() => new SaveGuide())
  useRunGuide(executionGuide, id, Boolean(status), id, status, progress.isError ? '진행률 조회에 실패했습니다. 다시 조회하세요.' : status === 'FAILED' ? failureMessage || '검증 실행 실패' : '')
  useEffect(() => {
    if (status === 'FINALIZED' && finalizeGuide.getSnapshot().savedId === null) {
      finalizeGuide.begin()
      void finalizeGuide.saved(id).catch(error => finalizeGuide.fail(String(error)))
    }
  }, [finalizeGuide, id, status])

  const execute = useMutation({
    mutationFn: () => { executionGuide.begin(); return executeRun(id) },
    meta: { errorToast: false },
    onMutate: () => ({ restarting: false, attemptId: progress.data?.attemptId ?? 0 }),
    onSuccess: (_response, _variables, before) => {
      announced.current = false
      setPreviousAttemptId(before?.restarting ? before.attemptId : null)
      setExecuted(true)
      setAttemptWaitGeneration((value) => value + 1)
      document.querySelector('[data-guide-target="validation.execute"]')?.dispatchEvent(new Event('fgc-validation-execute-accepted', { bubbles: true }))
      if (!revised) toast('검증 실행을 시작했습니다. 진행률을 2초 간격으로 갱신합니다.', 'info')
    },
    onError: (error) => { executionGuide.fail(errorText(error)); toast(errorText(error), 'error') },
  })

  const progressToastActive = revised && (execute.isPending || awaitingAttempt || status === 'RUNNING' || displayed.catchingUp)
  const summariesLoaded = [resultDetail.capSummary?.violationCount, resultDetail.capSummary?.warningCount,
    resultDetail.arbitrageSummary?.candidateCount, resultDetail.ledgerSummary?.imbalanceCount,
    resultDetail.reconciliationSummary?.mismatchCount, resultDetail.exceptionSummary?.openWorkItemCount, resultDetail.exceptionSummary?.detectedCount, resultDetail.exceptionSummary?.newCount,
    resultDetail.exceptionSummary?.recurringCount, resultDetail.exceptionSummary?.reopenedCount, resultDetail.exceptionSummary?.notDetectedCount].every(value => value != null)
  const guideError = status === 'COMPLETED' && checklist.isError ? '확정 전 점검을 불러오지 못했습니다. 점검 카드에서 다시 시도해 주세요.' : progress.isError ? '진행률 조회에 실패했습니다. 진행 단계에서 새로고침해 주세요.'
    : startUnconfirmed ? '실행 시작을 확인하지 못했습니다. 진행 단계를 확인해 주세요.'
      : status === 'FAILED' ? failureText(failureMessage, currentStep) : ''
  const guideState = guideError ? 'error' : progressToastActive || executed && status === 'CREATED' ? 'running'
    : ['COMPLETED', 'FINALIZED'].includes(status ?? '') && resultStep >= 8 && summariesLoaded ? 'ready' : 'waiting'
  const progressToastTitle = execute.isPending || awaitingAttempt ? '월 통합검증 시작 중'
      : status === 'RUNNING' ? '월 통합검증 실행 중' : '검증 결과 표시 중'
  const activeStepName = STEP_NAMES.find((_name, index) => displayed.state(index + 1) === 'running')
  useRunProgressToast(progressToastActive,
    `${progressToastTitle}, ${activeStepName ?? '진행 상태 확인 중'} · ${displayed.completed}/10단계 (${displayed.completed * 10}%)`,
    { steps: STEP_NAMES, completed: displayed.completed, running: activeStepName ? STEP_NAMES.indexOf(activeStepName) : null, buffering: displayed.buffering })

  const link = (path: string) => (shellMonth ? withMonth(path, shellMonth) : path)
  const progressDisplay = guidePreview ? guidePresentation : displayed

  const executionActions = (<div className="page-header-actions">
            <Button data-guide-feature="features.validation.validation-run-detail-page.button-2"
              variant="secondary"
              className="vrun-row-button"
              onClick={() => {
                toast('최신 상태를 불러옵니다.', 'info', 2000)
                refetchDetail()
                if (polling) void progress.refetch()
                void progress.refetch()
                void checklist.refetch()
              }}
            >
              <span className="material-symbols-rounded" aria-hidden="true">
                refresh
              </span>
              새로고침
            </Button>
            {/* FAILED 월 실행은 같은 실행의 실패 지점부터 재시작한다(FUN-045). */}
            <Button data-guide-feature="features.validation.validation-run-detail-page.button-3"
              className="vrun-row-button"
              data-guide-target="validation.execute" data-guide-completed={status !== 'CREATED' || executed} aria-describedby="execute-action-note"
              loading={execute.isPending}
              disabled={!canProcess || !canExecute || (executed && !finished)}
              onClick={() => execute.mutate()}
            >
              <span className="material-symbols-rounded" aria-hidden="true">
                play_arrow
              </span>
              실행
            </Button>
          </div>)
  return (
    <div className="vrun-page vrun-detail-page" data-presentation={revised ? 'after' : 'before'}>
      <header className="page-header">
        <ScreenHelp screenId="VRUN-W02" saveGuide={executionGuide} finalizeGuide={finalizeGuide} runStatus={status} outcome="execution" />
        <div className="page-header-copy">
          <h1 className="page-title">월 통합검증 상세/확정</h1>
        </div>
        <RunPicker currentId={id} link={link} />
        <Link data-guide-feature="validation.restart" data-guide-target="validation.restart" className="button button-secondary" to={link('/validation-runs')}>신규 실행 만들기</Link>
      </header>

      {/* 확정 불가역 경고 — 업무·규제 제약이므로 화면 설명문과 달리 남긴다 (운영정책서 제43조) */}
      <div className="guidance guidance-warning">
        <span className="material-symbols-rounded guidance-icon" aria-hidden="true">
          gavel
        </span>
        <div>
          <p>
            <strong>확정하면 이 실행의 결과는 고칠 수 없습니다.</strong> 바로잡으려면 새 실행을 만들어야 합니다.
          </p>
        </div>
      </div>

      <section data-guide-feature="features.validation.validation-run-detail-page.section-2" className="surface" aria-labelledby="vrun-header-title">
        <header className="surface-header">
          <h2 className="surface-title" id="vrun-header-title">
            <span className="tabular-nums">{formatMonth(header.validationMonth)}</span> {header.runNo}차 실행
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
              <dt>실행 차수</dt>
              <dd className="tabular-nums">{header.runNo}차</dd>
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
              <dt>확정자, 확정시각</dt>
              <dd className="tabular-nums">
                {header.finalizedAt ? `${header.finalizedBy ?? '-'}, ${dateTime(header.finalizedAt)}` : '-'}
              </dd>
            </div>
          </dl>
          {status === 'FAILED' && (
            <p className="vrun-inline-state is-error" role="alert">
              {failureText(failureMessage, currentStep)}
            </p>
          )}
        </div>
      </section>

      <section data-guide-feature="features.validation.validation-run-detail-page.section-3" data-guide-target="validation.progress" data-guide-completed={guideState === 'running' || guideState === 'ready'} className="surface" aria-labelledby="vrun-stepper-title">
        <header className="surface-header">
          <h2 className="surface-title" id="vrun-stepper-title">
            진행 단계
          </h2>
          {!revised && executionActions}
        </header>
        <div className="surface-body">
          {/* 숨겨져도 [실행]의 aria-describedby로 읽히므로 숨기는 상태에는 글자를 두지 않는다. */}
          <p
            className="vrun-action-note"
            id="execute-action-note"
            role={startUnconfirmed ? 'alert' : 'status'}
            hidden={canProcess && !startUnconfirmed && (canExecute || (status === 'FAILED' && header.runType !== 'MANUAL_CONTRACT')) && (!executed || finished)}
          >
            {status === 'FINALIZED'
              ? '확정된 실행은 다시 돌리지 않습니다. 새 실행을 만드세요.'
              : !canProcess
                ? '실행은 정산담당자와 시스템관리자만 할 수 있습니다.'
                : startUnconfirmed
                  ? '재시작 시도의 진행을 확인하지 못했습니다. 자동 조회를 멈췄습니다. 새로고침으로 상태를 확인하세요.'
                  : executed && !finished
                    ? '실행을 시작했습니다. 진행률을 확인하고 있습니다.'
                    : status === 'RUNNING'
                      ? '실행 중입니다. 끝나면 결과가 표시됩니다.'
                      : status === 'COMPLETED'
                        ? '계산을 마친 실행입니다. 다시 계산하려면 새 실행을 만드세요.'
                        : status === 'FAILED' && header.runType === 'MANUAL_CONTRACT'
                          ? '계약별 실행은 실패 지점부터 재시작할 수 없습니다. 새 실행을 만드세요.'
                          : ''}
          </p>
          {header.runType !== 'MANUAL_CONTRACT' && <ValidationRestartPanel key={id} id={id} month={header.validationMonth ?? ""} runNo={header.runNo ?? 0} status={status ?? 'CREATED'} waiting={!progress.isSuccess || !!(executed && !finished)} canProcess={canProcess} onAccepted={() => {
            announced.current = false
            setPreviousAttemptId(progress.data?.attemptId ?? 0)
            setExecuted(true)
            setAttemptWaitGeneration(value => value + 1)
            toast('재시작 요청을 접수했습니다. 실제 진행 상태를 확인합니다.', 'info')
          }} />}
          <p className="vrun-progress-line">
            <span>{guidePreview ? '진행 표시 미리보기' : revised && presentation.catchingUp && status !== 'RUNNING' ? '완료된 단계 표시 중' : '진행률'}</span>
            <span className="vrun-progress-value" role="status" aria-live="polite">
              {progressText(revised ? progressDisplay.completed : currentStep)}
            </span>
          </p>
          <ol key={guidePreview ? `guide-${guideGeneration}` : 'live'} className="vrun-stepper" aria-label="월 통합검증 10단계 진행 상태">
            {STEP_NAMES.map((name, index) => {
              const state = revised ? progressDisplay.state(index + 1) : stepState(index + 1, status, currentStep)
              return (
                <li
                  key={name}
                  className={`vrun-stepper-step ${state === 'pending' ? '' : `fgc-stepper__step--${state}`}`}
                  data-buffering={state === 'running' && progressDisplay.buffering}
                >
                  <span className="vrun-stepper-dot" aria-hidden="true">
                    {revised && state === 'done' ? <svg className="vrun-check-mark" viewBox="0 0 24 24"><path d="m5 12 4 4 10-10" /></svg>
                      : revised && state === 'running' && progressDisplay.buffering ? <span className="vrun-step-spinner" />
                      : revised && state === 'failed' ? '×' : index + 1}
                  </span>
                  {revised && index < STEP_NAMES.length - 1 && <span className={`vrun-step-connection${state === 'done' && progressDisplay.state(index + 2) !== 'pending' ? ' is-filled' : ''}`} aria-hidden="true" />}
                  <span className="vrun-stepper-label">
                    <span>{name}</span>
                    {index + 1 >= MANUAL_STEP_FROM && <span className="vrun-stepper-manual">담당자 처리</span>}
                  </span>
                  <span className="visually-hidden">{STEP_STATE_TEXT[state]}</span>
                </li>
              )
            })}
          </ol>
          {revised && <div className="vrun-bottom-actions">{executionActions}</div>}
        </div>
      </section>

      <div className="vrun-results-layout" data-guide-target="validation.results" data-guide-validation-state={guideState} data-guide-validation-error={guideError} data-guide-completed={guideState === 'ready'}>
      <div className="vrun-mid-grid">
        <TargetSection key={`targets-${attemptWaitGeneration}`} detail={resultDetail} revised={revised} ready={!revised || resultStep >= 2} />
        <SummarySection detail={resultDetail} status={status} completed={revised ? resultStep : undefined} ready={guideState === 'ready'} />
      </div>

      <ExceptionSection detail={resultDetail} openLink={link} completed={revised ? resultStep : undefined} />

      <div className="vrun-finalize-sidebar">
      <div data-guide-target="validation.checklist"><ChecklistSection status={status} checklist={checklist} revised={revised} completed={resultStep} generation={`${attemptWaitGeneration}-live`} /></div>

      <FinalizeSection
        revised={revised}
        finalizeGuide={finalizeGuide}
        id={id}
        status={status}
        canFinalize={canFinalize}
        runType={header.runType}
        checklist={checklist.isError || checklist.isFetching ? undefined : checklist.data}
        validationMonth={formatMonth(header.validationMonth)}
        runNo={header.runNo}
        onDone={() => {
          void client.invalidateQueries({ queryKey: ['validation-runs'] })
        }}
        onFailed={() => void client.invalidateQueries({ queryKey: ['validation-runs'] })}
      />
      </div>
      </div>
    </div>
  )
}

/** 최근 실행 20건 — 고르고 [이동]을 눌러야 넘어간다(1차와 같다). */
function RunPicker({ currentId, link }: { currentId: string; link: (path: string) => string }) {
  const navigate = useNavigate()
  const runs = useQuery(runsQuery({ status: '', page: 1, sort: '' }))
  const [picked, setPicked] = useState('')
  const value = picked || currentId
  return (
    <div className="page-header-actions">
      <div className="filter-field vrun-run-picker">
        <label className="filter-field-label" htmlFor="pick-run">
          실행
        </label>
        <select data-guide-feature="features.validation.validation-run-detail-page.pick-run"
          className="select-control"
          id="pick-run"
          value={value}
          onChange={(event) => setPicked(event.target.value)}
        >
          {(runs.data?.content ?? []).map((run) => (
            <option key={run.validationRunId} value={String(run.validationRunId)}>
              {formatMonth(run.validationMonth)}, {run.runNo}차 실행, {run.statusLabel}
            </option>
          ))}
        </select>
      </div>
      <Button data-guide-feature="features.validation.validation-run-detail-page.button-4" variant="secondary" onClick={() => void navigate(link(`/validation-runs/${value}`))}>
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

function TargetSection({ detail, revised, ready }: { detail: ValidationRunDetail; revised: boolean; ready: boolean }) {
  const summary = detail.targetSummary
  const targets = detail.targets ?? []
  const columns: TableColumn<ValidationTarget>[] = [
    // 계약번호는 자르지 않는다(좁은 화면에서는 표 안에서 가로로 넘긴다).
    { key: 'contract', label: '계약', width: '12rem', cellClassName: 'tabular-nums', render: (t) => t.contractNo },
    {
      key: 'status',
      label: '선별',
      width: '5rem',
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
      width: '5.5rem',
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
    <section data-guide-feature="features.validation.validation-run-detail-page.section-4" className="surface" aria-labelledby="vrun-target-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-target-title">
          대상 선별 결과
        </h2>
        <p className="vrun-card-note">
          {ready ? `선정 ${summary?.selectedCount ?? 0}, 제외 ${summary?.excludedCount ?? 0}, 검토필요 ${summary?.reviewRequiredCount ?? 0}` : '대상 선별 대기'}
        </p>
      </header>
      <DataTable
        caption="이번 실행의 검증 대상 선별 결과"
        className="vrun-target-table"
        viewportClassName="vrun-table-viewport"
        columns={columns}
        rows={ready ? targets : []}
        rowClassName={revised ? (target) => `vrun-target-reveal vrun-row-delay-${Math.min(targets.indexOf(target), 12)}` : undefined}
        emptyMessage={ready ? '조건에 맞는 자료가 없습니다.' : '대상 선별을 마치면 계약별 결과가 표시됩니다.'}
        rowKey={(t) => String(t.validationTargetId)}
      />
    </section>
  )
}

function SummaryCard({ label, value, unit, footer }: { label: string; value?: number; unit: string; footer?: string }) {
  return (
    <div className="kpi-card">
      <KpiCardContent label={label} value={value ?? null} unit={unit} footer={footer} />
    </div>
  )
}

/** ④ 결과 요약 4블록 — 결과 테이블을 실행 스코프로 매번 집계한다(FUN-043). 실행 전(CREATED)에는 값이 없다. */
function SummarySection({ detail, status, completed, ready = false }: { detail: ValidationRunDetail; status?: string; completed?: number; ready?: boolean }) {
  const cap = detail.capSummary
  const arb = detail.arbitrageSummary
  const ledger = detail.ledgerSummary
  const reco = detail.reconciliationSummary
  const before = status === 'CREATED'
  return (
    <section data-guide-feature="features.validation.validation-run-detail-page.section-5" className="surface" aria-labelledby="vrun-summary-title" data-guide-completed={ready}>
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-summary-title">
          결과 요약
        </h2>
      </header>
      {completed !== undefined ? <RunSummaryCards detail={detail} completed={completed} /> : <div className="kpi-grid kpi-group vrun-summary-grid">
        <SummaryCard
          label="1,200%"
          unit={before ? '실행 전' : '건 검사'}
          value={before ? undefined : cap?.checkedCount}
          footer={
            before
              ? undefined
              : `위반 ${cap?.violationCount}, 주의 ${cap?.warningCount}, 검토 ${cap?.reviewRequiredCount}`
          }
        />
        <SummaryCard
          label="차익거래"
          unit={before ? '실행 전' : '건 검사'}
          value={before ? undefined : arb?.checkedCount}
          footer={before ? undefined : `검토대상 ${arb?.candidateCount}, 검토 ${arb?.reviewRequiredCount}`}
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
      </div>}
    </section>
  )
}

/** SRC-032: 실행별 검출과 관리자 업무건을 분리해 재실행이 업무량을 부풀리지 않게 한다. */
function ExceptionSection({ detail, openLink, completed }: { detail: ValidationRunDetail; openLink: (path: string) => string; completed?: number }) {
  const summary = detail.exceptionSummary
  const month = detail.header?.validationMonth
  // validationMonth 는 예외함의 검증월 검색조건이다(셸 기준월 month 와는 별개 파라미터).
  const href = openLink(`/exceptions?status=OPEN&validationMonth=${encodeURIComponent(month ?? '')}`)
  return (
    <section data-guide-feature="features.validation.validation-run-detail-page.section-6" className="surface" aria-labelledby="vrun-exception-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-exception-title">
          예외 생성 결과
        </h2>
        <Link data-guide-feature="features.validation.validation-run-detail-page.link-2" className={completed !== undefined ? 'vrun-text-link' : 'button button-secondary vrun-row-button'} to={href}>
          예외함 열기
        </Link>
      </header>
      {completed !== undefined ? <RunExceptionCards detail={detail} completed={completed} /> : <div className="kpi-grid kpi-group vrun-exception-grid">
        <SummaryCard label="이번 실행 검출" value={summary?.detectedCount ?? 0} unit="건" />
        <SummaryCard label="새 예외" value={summary?.newCount ?? 0} unit="건" />
        <SummaryCard label="다시 나온 예외" value={summary?.recurringCount ?? 0} unit="건" />
        <SummaryCard label="해결 후 재발" value={summary?.reopenedCount ?? 0} unit="건" />
        <SummaryCard label="이번 실행 미검출" value={summary?.notDetectedCount ?? 0} unit="건" />
        <SummaryCard label="미처리 예외" value={summary?.openWorkItemCount ?? 0} unit="건" />
      </div>}
    </section>
  )
}

function ChecklistSection({
  status,
  checklist,
  revised,
  completed,
  generation,
}: {
  status?: string
  checklist: ReturnType<typeof useQuery<FinalizeChecklist>>
  revised: boolean
  completed: number
  generation: string
}) {
  const data = checklist.data
  if (revised && status !== 'FINALIZED') return <AnimatedRunChecklist
    key={generation} completed={completed} data={status === 'COMPLETED' && !checklist.isFetching && !checklist.isError ? data : undefined}
    error={status === 'COMPLETED' && checklist.isError ? errorText(checklist.error as Error, '확정 조건을 불러오지 못했습니다.') : undefined}
    retry={() => void checklist.refetch()} />
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
        <Button data-guide-feature="features.validation.validation-run-detail-page.button-5" variant="secondary" onClick={() => void checklist.refetch()}>
          다시 시도
        </Button>
      </p>
    )
  } else if (data) {
    summary = data.passed
      ? { text: '8개 조건 모두 통과', tone: 'success' }
      : { text: '미충족 조건 있음', tone: 'error' }
    body = (
      <div className="vrun-checklist">
        {data.conditions?.map((condition) => {
          const to = condition.passed ? null : safeInternalLink(condition.linkUrl)
          return (
            <div className="vrun-checklist-item" data-guide-condition={condition.no} key={condition.no}>
              {revised && <span className={`vrun-condition-icon ${condition.passed ? 'is-pass' : 'is-fail'}`} aria-hidden="true">
                {condition.passed ? <svg className="vrun-check-mark" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg> : <svg className="vrun-condition-cross" viewBox="0 0 24 24" fill="none"><path d="m7 7 10 10M17 7 7 17" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" /></svg>}
              </span>}
              <div className="vrun-checklist-label">
                {revised ? <>
                  <span>{condition.no}. {condition.label}</span>
                  <span className={condition.passed ? 'vrun-condition-pass' : 'vrun-condition-fail'}> · {condition.passed ? '통과' : `미충족 ${condition.count}건`}</span>
                </> : <CellDisclosure preview={`${condition.no}. ${condition.label}`} />}
              </div>
              <div className="vrun-checklist-result">
                {!revised && <StatusBadge tone={condition.passed ? 'success' : 'error'}>
                  {condition.passed ? '통과' : `미충족 ${condition.count}건`}
                </StatusBadge>}
                {to && (
                  <Link data-guide-feature="features.validation.validation-run-detail-page.legacy-checklist-link" className={revised ? 'vrun-text-link' : undefined} to={to} aria-label={`${condition.label} 미충족 건 바로가기`}>
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
    <section data-guide-feature="features.validation.validation-run-detail-page.legacy-checklist" className="surface" aria-labelledby="vrun-checklist-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-checklist-title">
          확정 전 8단계 점검
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
  revised,
  finalizeGuide,
  runType,
  id,
  status,
  canFinalize,
  checklist,
  validationMonth,
  runNo,
  onDone,
  onFailed,
}: {
  revised: boolean
  finalizeGuide: SaveGuide
  id: string
  runType?: string
  status?: string
  canFinalize: boolean
  checklist?: FinalizeChecklist
  validationMonth: string
  runNo?: number
  onDone: () => void
  onFailed: () => void
}) {
  const [confirming, setConfirming] = useState(false)
  const [showDisabledReason, setShowDisabledReason] = useState(false)
  const [finalized, setFinalized] = useState(false)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const supportedType = runType === 'MONTHLY' || runType === 'PRE_CONFIRM'
  const finalizable = supportedType && canFinalize && status === 'COMPLETED' && checklist?.passed === true
  useEffect(() => { finalizeGuide.update(id, finalizable) }, [finalizeGuide, id, finalizable])
  // 멱등키는 성공하거나 FGC-VRUN-006(다른 실행에 귀속된 키)을 받을 때까지 같은 값으로 재시도한다.
  const key = useRef<string | null>(null)
  const mutation = useMutation({
    mutationFn: () => {
      finalizeGuide.begin()
      key.current ??= newKey(id)
      return finalizeRun(id, key.current)
    },
    meta: { errorToast: false },
    onSuccess: async (result) => {
      if (result.status === 'FINALIZED') onDone()
      if (!mounted.current) return
      if (result.status !== 'FINALIZED') throw new Error('최종 확정 상태를 확인하지 못했습니다.')
      if (!(await finalizeGuide.saved(id)) || !mounted.current) return
      setConfirming(false)
      setFinalized(true)
    },
    onError: (error) => {
      finalizeGuide.fail(errorText(error))
      if (error instanceof ApiError && error.code === 'FGC-VRUN-006') key.current = null
      if (!finalizeGuide.isActive()) setConfirming(false)
      toast(errorText(error as Error, '검증 실행 확정에 실패했습니다.'), 'error', 5000)
      onFailed()
    },
  })
  const passed = checklist?.passed === true
  const enabled = finalizable && !mutation.isPending
  let reason = ''
  if (mutation.isPending) reason = '확정 요청을 처리하고 있습니다. 잠시 기다려 주세요.'
  else if (!canFinalize) reason = '확정은 GA관리자와 시스템관리자만 할 수 있습니다.'
  else if (status === 'FINALIZED') reason = '이미 확정된 실행입니다. 결과를 바꾸려면 새 실행을 만드세요.'
  else if (!supportedType) reason = '월 전체를 계산한 월간·확정 전 실행만 최종 확정할 수 있습니다.'
  else if (status !== 'COMPLETED') reason = '검증 계산이 끝난 실행만 확정할 수 있습니다.'
  else if (!checklist) reason = '확정 조건을 아직 확인하지 못했습니다. 잠시 후 새로고침해 주세요.'
  else if (!passed) reason = revised ? '위 8개 조건을 모두 통과하면 확정할 수 있습니다.' : '확정 조건 8개를 모두 통과해야 합니다. 아래 체크리스트를 확인하세요.'
  const blockedConditions = checklist?.conditions?.filter(condition => !condition.passed) ?? []
  return (
    <section data-guide-feature="features.validation.validation-run-detail-page.section-8" data-guide-target="validation.finalize" className="surface" aria-labelledby="vrun-finalize-section-title">
      <div className="vrun-finalize-row">
        <div className="vrun-finalize-copy">
          <p className="vrun-finalize-title" id="vrun-finalize-section-title">
            검증 결과 확정
          </p>
          <p className="field-helper vrun-finalize-guide" id="finalize-action-guide">
            {revised ? <>확정하면 검증 결과와 계산 근거가 잠깁니다.<br />실제 송금은 아니며, 변경하려면 새 실행이 필요합니다.</> : <>
            위 8개 조건이 모두 통과한 실행만 확정할 수 있습니다.
            <br />
            확정하면 이 실행의 검증 결과와 그때 쓰인 규칙/계산근거가 <strong>잠깁니다</strong>.{' '}
            <br />
            <strong>되돌릴 수 없습니다.</strong> 결과를 바꾸려면 새 실행을 만드세요.
            </>}
          </p>
          {/* 비활성 사유는 title 이 아니라 가시 텍스트로 전한다 — disabled 버튼은 포커스가 가지 않는다. */}
          <p className="vrun-action-note" id="finalize-action-note" role="status" hidden={!reason}>
            {reason}
          </p>
        </div>
        <div className="vrun-finalize-trigger"
          tabIndex={!enabled ? 0 : undefined}
          aria-label={!enabled ? '확정 불가 사유' : undefined}
          aria-describedby={!enabled && showDisabledReason ? 'finalize-disabled-tooltip' : undefined}
          onMouseEnter={() => setShowDisabledReason(true)}
          onMouseLeave={() => setShowDisabledReason(false)}
          onFocus={() => setShowDisabledReason(true)}
          onBlur={() => setShowDisabledReason(false)}
          onKeyDown={event => { if (event.key === 'Escape') setShowDisabledReason(false) }}
        >
        <Button data-guide-feature="features.validation.validation-run-detail-page.btn-finalize"
          id="btn-finalize"
          aria-describedby="finalize-action-guide finalize-action-note"
          loading={mutation.isPending}
          disabled={!enabled}
          onClick={() => setConfirming(true)}
        >
          <span className="material-symbols-rounded" aria-hidden="true">
            lock
          </span>
          <span>{status === 'FINALIZED' ? '확정(잠김)' : '확정'}</span>
        </Button>
        {!enabled && showDisabledReason && <div id="finalize-disabled-tooltip" role="tooltip" className="vrun-finalize-tooltip">
          <strong>확정할 수 없는 이유</strong>
          <p>{reason}</p>
          {status === 'COMPLETED' && blockedConditions.length > 0 && <ul>
            {blockedConditions.map(condition => <li key={condition.no}>{condition.label} · 미충족 {condition.count}건</li>)}
          </ul>}
        </div>}
        </div>
      </div>
      <FinalizeDialogs
        saveGuide={finalizeGuide}
        allowed={finalizable}
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
