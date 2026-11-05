import { useUnsavedWorkStore } from '../../../stores/unsavedWork'
import { useSpotlight } from '../guide/useSpotlight'
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useLocation, useNavigate } from 'react-router'
import type { ShellUser } from '../../../app/shell/Sidebar'
import { screenFor } from '../../../app/screens'
import { GuideRuntime } from '../guide/runtime'
import { GuideTargets } from '../guide/targets'
import type { EntityRef, GuideContext, GuideSnapshot } from '../guide/types'
import { getPaymentGuideObserver } from '../../transaction/payment-guide'
import type { ContractPage } from '../../contract/api'
import { readPayment } from '../../transaction/form-api'
import { closeSettlementGuide, settlementEntry, subscribeSettlementEntry } from './entry'
import { decide, STEPS, handoffFor, replacePayment as replacementSelection } from './state'
import type { Selection, Evidence, Decision } from './state'
import { guideGet, readEvidence } from './api'
import { destinations, settlementCatalog, titles, FLOW_VERSION } from './catalog'
import './settlement.css'
import '../../transaction/payment-guide.css'
import { Modal } from '../../../components/Modal'
import guideMascot from '../assets/pico-guide.png'
import { useWorkflowGuide } from '../workflow-state'
import { usePaymentWalkthrough } from './usePaymentWalkthrough'
import { useWorkflowWalkthrough } from './useWorkflowWalkthrough'

const id = (value: string | null) =>
  value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : undefined
function failureText(failure: unknown, fallback: string) {
  return failure instanceof DOMException && failure.name === 'TimeoutError'
    ? '최신 상태 조회가 5초 안에 끝나지 않았습니다. 잠시 후 다시 확인하세요.'
    : failure instanceof Error ? failure.message : fallback
}
export function SettlementGuide({ user, month }: { user: ShellUser; month: string | null }) {
  const open = useSyncExternalStore(subscribeSettlementEntry, settlementEntry)
  useEffect(() => () => closeSettlementGuide(), [user.loginId])
  if (!open || !month || user.roleCode === 'AGENT') return null
  return <Flow key={`${user.loginId}:${month}`} user={user} month={month} />
}
function Flow({ user, month }: { user: ShellUser; month: string }) {
  const location = useLocation(),
    navigate = useNavigate()
  const [selection, setSelection] = useState<Selection | null>(null)
  const [contractLabel, setContractLabel] = useState('')
  const [replacementNo, setReplacementNo] = useState('')
  const [contractNo, setContractNo] = useState(''),
    [paymentNo, setPaymentNo] = useState('')
  const [stage, setStage] = useState<Selection['stage']>('GA_TO_FC')
  const [decision, setDecision] = useState<Decision | null>(null)
  const [collapsed, setCollapsed] = useState(false)
  const [busy, setBusy] = useState(false),
    [error, setError] = useState('')
  const [leave, setLeave] = useState<(() => void) | null>(null)
  const stayButton = useRef<HTMLButtonElement>(null)
  const [snapshot, setSnapshot] = useState<GuideSnapshot | null>(null)
  const card = useRef<HTMLElement | null>(null)
  const params = new URLSearchParams(location.search)
  const routeContract = location.pathname.match(/^\/contracts\/(\d+)$/)?.[1]
  const routeSchedule = location.pathname.match(/^\/schedules\/(\d+)$/)?.[1]
  const contextMismatch = !!selection && (
    (!!params.get('month') && params.get('month') !== selection.month) ||
    (!!routeContract && !!selection.contractId && Number(routeContract) !== selection.contractId) ||
    (!!routeSchedule && !!selection.scheduleId && Number(routeSchedule) !== selection.scheduleId)
  )
  const detailedPayment = !busy && decision?.step === 'payment.draft' && location.pathname === '/transactions/new' && !error && !contextMismatch
  const walkthrough = usePaymentWalkthrough(detailedPayment, month, selection?.stage, selection?.contractId)
  // show() can navigate from the just-saved editor to the same route with its new ID.
  // Bind only after that transition has finished: the old editor is replaced while
  // its saved draft loads, which is not loss of a target in the new explanation.
  const explanation = useWorkflowWalkthrough(busy ? undefined : decision?.step, location.pathname, !!new URLSearchParams(location.search).get('selected'), decision?.correctionPending)
  const detailedWorkflow = snapshot?.status !== 'COMPLETED' && !!explanation.step && !error && !contextMismatch && !handoffFor(decision!.step, user.roleCode ?? '', user.canProcess)
  const activeTarget = detailedPayment ? walkthrough.target : detailedWorkflow ? explanation.target : snapshot?.target ?? null
  const position = useSpotlight(!collapsed ? activeTarget : null, card)
  const detailedInput = useRef(false)
  useLayoutEffect(() => { detailedInput.current = detailedPayment || detailedWorkflow })
  const runtime = useRef<GuideRuntime<Evidence> | null>(null)
  const request = useRef<AbortController | null>(null)
  const epoch = useRef(0)
  const acknowledged = useRef('')
  useEffect(() => useWorkflowGuide.subscribe((state, previous) => {
    if (state.chatSequence !== previous.chatSequence) {
      request.current?.abort()
      setBusy(false)
      setCollapsed(true)
      runtime.current?.pause('USER_PAUSED')
    }
  }), [])
  const current = useRef({ location, user, selection })
  useLayoutEffect(() => {
    current.current = { ...current.current, location, user, selection }
  })
  const savedFocus = useRef(document.activeElement instanceof HTMLElement ? document.activeElement : null)
  const close = () => {
    runtime.current?.dispose()
    request.current?.abort()
    closeSettlementGuide()
    savedFocus.current?.focus()
  }
  useEffect(() => {
    const input = (event: Event) => {
      if ((event.target as HTMLElement).closest('#main-content form, [data-transaction-form]')) {
        request.current?.abort()
        setBusy(false)
        if (!detailedInput.current) {
          setCollapsed(true)
          runtime.current?.pause('INPUT_CHANGED')
        }
      }
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('[role="dialog"]')) close()
    }
    document.addEventListener('input', input, true)
    document.addEventListener('change', input, true)
    document.addEventListener('keydown', escape)
    return () => {
      request.current?.abort()
      runtime.current?.dispose()
      document.removeEventListener('input', input, true)
      document.removeEventListener('change', input, true)
      document.removeEventListener('keydown', escape)
    }
  }, []) // Shell-owned lifetime, no persisted progress.
  useEffect(() => {
    runtime.current?.contextChanged()
  }, [location.pathname, location.search, user.roleCode])
  useEffect(() => {
    const target = runtime.current?.getSnapshot().target
    if (!target) return
    target.classList.add('settlement-scroll-target')
    const observer = new MutationObserver(() => runtime.current?.contextChanged())
    observer.observe(document.getElementById('main-content')!, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['hidden', 'inert', 'aria-hidden', 'class', 'style', 'data-guide-target'],
    })
    return () => {
      target.classList.remove('settlement-scroll-target')
      observer.disconnect()
    }
  }, [snapshot])

  function collect(base: Selection): Selection {
    const path = current.current.location.pathname
    const params = new URLSearchParams(current.current.location.search)
    const next = { ...base }
    if (params.get('month') && params.get('month') !== base.month)
      throw new Error('기준월이 바뀌었습니다. 현재 월에서 새 안내를 시작하세요.')
    if (path === '/transactions' && id(params.get('selectedId'))) {
      const selected = id(params.get('selectedId'))
      if (next.paymentId && next.paymentId !== selected)
        throw new Error('다른 지급 건을 선택했습니다. 새 안내를 시작하세요.')
      next.paymentId = selected
    }
    if (path === '/schedules' && id(params.get('selectedId'))) next.scheduleId = id(params.get('selectedId'))
    const contract = path.match(/^\/contracts\/(\d+)$/)?.[1]
    if (contract) {
      if (next.contractId && next.contractId !== Number(contract))
        throw new Error('다른 계약을 선택했습니다. 종료 후 현재 대상으로 새 안내를 시작하세요.')
      next.contractId = Number(contract)
    }
    if (path === '/transactions/new') {
      const context = getPaymentGuideObserver()?.readContext()
      if (context?.entityRefs.some((ref) => ref.type === 'CONTRACT' && next.contractId && ref.id !== next.contractId))
        throw new Error('다른 계약을 선택했습니다. 현재 대상으로 새 안내를 시작하세요.')
      const payment = context?.entityRefs.find((ref) => ref.type === 'PAYMENT')?.id
      if (payment) {
        if (next.paymentId && next.paymentId !== payment)
          throw new Error('다른 지급 건을 선택했습니다. 새 안내를 시작하세요.')
        next.paymentId = payment
      }
    }
    const validation = path.match(/^\/validation-runs\/(\d+)$/)?.[1]
    if (validation) {
      next.validationRunId = Number(validation)
      if (next.validationRunId !== base.validationRunId) {
        next.reconciliationRunId = undefined
        next.exceptionId = undefined
      }
    }
    if (path === '/reconciliations' && id(params.get('runId'))) next.reconciliationRunId = id(params.get('runId'))
    if (path === '/exceptions') next.exceptionId = id(params.get('selected'))
    const schedule = path.match(/^\/schedules\/(\d+)$/)?.[1]
    if (schedule) next.scheduleId = Number(schedule)
    return next
  }
  // The shell adds the selected month after navigation; this is the same business context.
  function locationContext(location: { pathname: string; search: string }) {
    const params = new URLSearchParams(location.search)
    if (!params.has('month')) params.set('month', month)
    params.sort()
    return `${location.pathname}?${params}`
  }
  async function show(
    base: Selection,
    initial = false,
    acknowledge = false,
    replacePayment = false,
    allowDiscard = false,
  ) {
    request.current?.abort()
    const controller = new AbortController(),
      token = ++epoch.current
    request.current = controller
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(5000)])
    setBusy(true)
    setCollapsed(false)
    setError('')
    runtime.current?.pause('REFRESHING')
    try {
      const sourceLocation = locationContext(current.current.location)
      const next = initial || replacePayment ? base : collect(base)
      const evidence = await readEvidence(next, signal)
      signal.throwIfAborted()
      if (current.current.user.roleCode !== user.roleCode)
        throw new Error('권한이 바뀌었습니다. 현재 권한으로 다시 시작하세요.')
      if (token !== epoch.current) return
      if (sourceLocation !== locationContext(current.current.location))
        throw new Error('조회 중 화면이 바뀌었습니다. 현재 대상으로 다시 확인하세요.')
      if (!next.scheduleId && evidence.schedule?.header?.scheduleHeaderId)
        next.scheduleId = evidence.schedule.header.scheduleHeaderId
      const key = JSON.stringify(evidence.schedule)
      if (acknowledge) acknowledged.current = key
      evidence.scheduleAcknowledged = acknowledged.current === key && !!evidence.schedule
      // Existing confirmed payments can resume without repeating a previously completed schedule review.
      if (
        initial &&
        evidence.payment?.status === 'CONFIRMED' &&
        evidence.schedule?.header?.scheduleRegime === 'CURRENT'
      ) {
        evidence.scheduleAcknowledged = true
        acknowledged.current = key
      }
      if (replacePayment && evidence.payment?.status !== 'CONFIRMED')
        throw new Error('정정 후 확정된 지급 번호를 선택하세요.')
      const result = decide(next, evidence, user.roleCode ?? '')
      const routes = destinations(next)
      const destination = routes[result.step]
      const paymentContext = getPaymentGuideObserver()?.readContext()
      const dirty = Boolean(paymentContext?.dirty) || Object.keys(useUnsavedWorkStore.getState().owners).length > 0
      const pathNow = current.current.location.pathname
      const destinationUrl = new URL(destination.path, 'https://guide.local')
      const currentParams = new URLSearchParams(current.current.location.search)
      const needsNavigation =
        pathNow !== destinationUrl.pathname ||
        [...destinationUrl.searchParams].some(([key, value]) => currentParams.get(key) !== value)
      if (needsNavigation && dirty && !allowDiscard) {
        setLeave(() => () => {
          void show(base, initial, acknowledge, replacePayment, true)
        })
        return
      }
      const handoff = handoffFor(result.step, user.roleCode ?? '', user.canProcess)
      setContractLabel(evidence.contract?.contractNo ?? '')
      setSelection(next)
      current.current.selection = next
      setDecision(result)
      if (handoff) {
        runtime.current?.dispose()
        setSnapshot(null)
        setDecision({ ...result, reason: handoff })
        setError(handoff)
        return
      }
      const refs: EntityRef[] = []
      for (const [type, value] of [
        ['CONTRACT', next.contractId],
        ['SCHEDULE', next.scheduleId],
        ['PAYMENT', next.paymentId],
        ['VALIDATION_RUN', next.validationRunId],
        ['RECONCILIATION_RUN', next.reconciliationRunId],
        ['EXCEPTION', next.exceptionId],
      ] as const)
        if (value) refs.push({ type, id: value })
      const context = (): GuideContext => ({
        screenId: screenFor(current.current.location.pathname)?.id ?? '',
        role: current.current.user.roleCode ?? '',
        entityRefs: refs,
        clientContextRevision: `${token}:${current.current.location.pathname}${current.current.location.search}`,
        serverStateVersion: null,
        dirty: false,
      })
      const catalog = settlementCatalog(next),
        targets = new GuideTargets()
      catalog.steps.forEach((step) =>
        targets.register({
          screenId: step.screenId,
          targetId: step.targetId,
          guideVersion: FLOW_VERSION,
          root: () => document.getElementById('main-content'),
        }),
      )
      runtime.current?.dispose()
      const runner = new GuideRuntime([catalog], targets, {
        readContext: context,
        readBusinessState: async (_context, runtimeSignal) => {
          runtimeSignal.throwIfAborted()
          return evidence
        },
        conditions: {
          AVAILABLE: () => 'SATISFIED',
          NOT_AUTOMATIC: () => 'WAITING_RESULT',
          FINALIZED: () => (result.complete ? 'SATISFIED' : 'WAITING_RESULT'),
        },
        confirmLeave: async () => false,
        openScreen: async (screen, runtimeSignal) => {
          if (screen !== destination.screen) throw new Error('등록되지 않은 화면입니다.')
          await navigate(destination.path)
          while (screenFor(current.current.location.pathname)?.id !== screen) {
            runtimeSignal.throwIfAborted()
            await new Promise((resolve) => setTimeout(resolve, 20))
          }
        },
      })
      runtime.current = runner
      runner.observe(() => setSnapshot(runner.getSnapshot()))
      // A screen ID alone cannot distinguish another entity or a different tab on the same route.
      if (needsNavigation) {
        if (allowDiscard) useUnsavedWorkStore.setState({ owners: {} })
        await navigate(destination.path)
        while (
          current.current.location.pathname !== destinationUrl.pathname ||
          [...destinationUrl.searchParams].some(
            ([key, value]) => new URLSearchParams(current.current.location.search).get(key) !== value,
          )
        ) {
          signal.throwIfAborted()
          await new Promise((resolve) => setTimeout(resolve, 20))
        }
      }
      if (signal.aborted || token !== epoch.current) return
      await runner.start({
        workflowId: catalog.workflowId,
        guideVersion: FLOW_VERSION,
        stepId: result.step,
        entityRefs: refs,
        clientContextRevision: context().clientContextRevision,
        serverStateVersion: null,
      })

      if (result.complete) await runner.next()
    } catch (failure) {
      if (!controller.signal.aborted && token === epoch.current)
        setError(failureText(failure, '최신 상태를 확인하지 못했습니다.'))
    } finally {
      if (token === epoch.current) setBusy(false)
    }
  }
  async function start() {
    const controller = new AbortController()
    const startLocation = current.current.location.pathname + current.current.location.search
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(5000)])
    request.current?.abort()
    request.current = controller
    setBusy(true)
    setError('')
    try {
      const base: Selection = { month, stage }
      if (paymentNo) {
        const paymentId = id(paymentNo)
        if (!paymentId) throw new Error('올바른 지급 번호를 입력하세요.')
        const payment = await readPayment(String(paymentId), signal)
        const contracts = [...new Set(payment.attributions?.map((row) => row.contractId))]
        if (contracts.length !== 1 || !contracts[0]) throw new Error('하나의 계약에 귀속된 지급 건을 선택하세요.')
        base.contractId = contracts[0]
        base.paymentId = paymentId
      } else if (contractNo.trim()) {
        const page = await guideGet<ContractPage>(
          `/contracts?contractNo=${encodeURIComponent(contractNo.trim())}&page=1&size=20`,
          signal,
        )
        const exact = page.content?.filter((item) => item.contractNo === contractNo.trim())
        if (exact?.length !== 1 || !exact[0].contractId) throw new Error('정확한 기존 계약번호를 입력하세요.')
        base.contractId = exact[0].contractId
      }
      signal.throwIfAborted()
      if (startLocation !== current.current.location.pathname + current.current.location.search)
        throw new Error('조회 중 화면이 바뀌었습니다. 현재 대상으로 다시 시작하세요.')
      await show(base, true)
    } catch (failure) {
      if (!controller.signal.aborted) setError(failureText(failure, '시작할 수 없습니다.'))
    } finally {
      setBusy(false)
    }
  }
  function revisit(path: string, allowDiscard = false) {
    const dirty =
      Boolean(getPaymentGuideObserver()?.readContext().dirty) || Object.keys(useUnsavedWorkStore.getState().owners).length > 0
    if (dirty && !allowDiscard) {
      setLeave(() => () => revisit(path, true))
      return
    }
    if (allowDiscard) useUnsavedWorkStore.setState({ owners: {} })
    runtime.current?.pause('USER_NAVIGATION')
    setCollapsed(true)
    void navigate(path)
  }
  return (
    <>
      {!collapsed && position.box && activeTarget && (
        <div className="payment-guide-spotlight settlement-guide-spotlight" aria-hidden="true" style={position.box} />
      )}
      <aside
        ref={card}
        style={collapsed ? { left: 'auto', top: 80, right: 16, bottom: 'auto' } : position.card}
        className="settlement-guide"
        data-guide-anchored={Boolean(activeTarget && !collapsed)}
        aria-label="전체 업무 가이드"
        aria-busy={busy}
      >
        <header>
          <img
            src={guideMascot}
            alt=""
            className="settlement-guide-mascot"
            width="32"
            height="32"
          />
          <strong>전체 업무 가이드</strong>
          {selection && (
            <button type="button" onClick={() => setCollapsed((value) => !value)}>
              {collapsed ? '펼치기' : '접기'}
            </button>
          )}
          <button type="button" onClick={close}>
            종료
          </button>
        </header>
        {!collapsed && (
          <p>
            {month} · {(selection?.stage ?? stage) === 'GA_TO_FC' ? 'GA → 설계사' : '보험사 → GA'} ·{' '}
            {contractLabel || '새 업무'}
          </p>
        )}
        {collapsed && selection ? (
          <button disabled={busy} onClick={() => (detailedPayment || detailedWorkflow) ? setCollapsed(false) : void show(selection)}>
            현재 결과로 이어서 안내
          </button>
        ) : !selection ? (
          <>
            <label>
              지급단계
              <select
                disabled={busy}
                value={stage}
                onChange={(event) => setStage(event.target.value as Selection['stage'])}
              >
                <option value="GA_TO_FC">GA → 설계사</option>
                <option value="INSURER_TO_GA">보험사 → GA</option>
              </select>
            </label>
            <label>
              기존 계약번호 (새 계약은 비워두세요)
              <input
                value={contractNo}
                onChange={(event) => setContractNo(event.target.value)}
                disabled={busy || !!paymentNo}
              />
            </label>
            <label>
              기존 지급 번호로 이어서 안내 (선택)
              <input
                disabled={busy}
                inputMode="numeric"
                value={paymentNo}
                onChange={(event) => setPaymentNo(event.target.value)}
              />
            </label>
            <button className="button button-primary" disabled={busy} onClick={() => void start()}>
              안내 시작
            </button>
          </>
        ) : (
          <>
            <p>{decision ? `${STEPS.indexOf(decision.step) + 1}/9 · ${titles[STEPS.indexOf(decision.step)]}` : ''}</p>
            {detailedPayment && walkthrough.step ? (
              <section aria-label="지급 초안 세부 안내" aria-live="polite">
                <small>입력 안내 {walkthrough.index + 1}/{walkthrough.total}</small>
                <h2>{walkthrough.step.title}</h2>
                <p>{walkthrough.step.instruction}</p>
                {walkthrough.message && <p role="status">{walkthrough.message}</p>}
                {walkthrough.status === 'PAUSED' && <button onClick={walkthrough.retry}>다시 찾기</button>}
                {walkthrough.step.id === 'save' && walkthrough.saved && <p role="status">저장 응답을 받았습니다. 서버 결과를 확인하고 다음 업무로 이동하세요.</p>}
                <div className="settlement-guide-actions">
                  <button disabled={busy || walkthrough.index === 0} onClick={walkthrough.previous}>이전 설명</button>
                  {walkthrough.step.id === 'save' ? (
                    <button disabled={busy || !walkthrough.ready || !walkthrough.saved} onClick={() => void show(selection!)}>저장 결과 확인 · 다음 업무</button>
                  ) : <button disabled={busy || !walkthrough.ready} onClick={walkthrough.next}>확인하고 다음 설명</button>}
                </div>
              </section>
            ) : detailedWorkflow ? (
              <section aria-label="업무 세부 안내" aria-live="polite">
                <small>설명 {explanation.index + 1}/{explanation.total}</small>
                <h2>{explanation.step.title}</h2>
                <p>{explanation.step.instruction}</p>
                {explanation.message && <p role="status">{explanation.message}</p>}
                {explanation.status === 'PAUSED' && <button onClick={explanation.retry}>다시 찾기</button>}
                <div className="settlement-guide-actions">
                  <button disabled={busy || explanation.index === 0} onClick={explanation.previous}>이전 설명</button>
                  {!explanation.last && <button disabled={busy || !explanation.ready} onClick={explanation.next}>확인하고 다음 설명</button>}
                </div>
              </section>
            ) : <p>{decision?.reason}</p>}
            {detailedWorkflow && explanation.last && <p role="status">{decision?.reason}</p>}
            {contextMismatch && <p role="status">선택한 계약·스케줄·정산월과 현재 화면이 다릅니다. 기존 대상으로 돌아가거나 안내를 종료하고 새로 시작하세요.</p>}
            {busy && <p role="status">현재 서버 결과를 확인하고 있습니다.</p>}
            {!detailedPayment && !detailedWorkflow && !busy && (snapshot?.status === 'PAUSED' || snapshot?.status === 'ERROR') ? (
              <p role="status">안내 대상을 확인하지 못했거나 화면·입력이 바뀌었습니다. 현재 결과로 다시 확인하세요.</p>
            ) : null}
            {snapshot?.status === 'COMPLETED' ? (
              <strong>전체 업무 안내 완료</strong>
            ) : (
              <div className="settlement-guide-actions">
                {decision?.step === 'schedule.review' && (!detailedWorkflow || explanation.last) && (
                  <button disabled={busy || (detailedWorkflow && explanation.status === 'PAUSED')} onClick={() => void show(selection, false, true)}>
                    스케줄 확인 완료
                  </button>
                )}
                {!detailedPayment && (!detailedWorkflow || explanation.last || !explanation.target) && <button disabled={busy} onClick={() => void show(selection)}>
                  현재 결과로 이어서 안내
                </button>}
              </div>
            )}
            {decision?.step === 'validation.execute' && !user.canProcess && (
              <button disabled={busy} onClick={() => revisit(`/validation-runs?month=${selection.month}`)}>
                월 검증 결과 선택
              </button>
            )}
            {decision?.step === 'exception.resolve' && (!detailedWorkflow || explanation.last || !explanation.target) &&
              !handoffFor('exception.resolve', user.roleCode ?? '', user.canProcess) && (
                <div className="settlement-guide-actions">
                  <label>
                    정정 후 지급 번호
                    <input
                      inputMode="numeric"
                      disabled={busy}
                      value={replacementNo}
                      onChange={(event) => setReplacementNo(event.target.value)}
                    />
                  </label>
                  <button
                    disabled={busy}
                    onClick={() => {
                      const paymentId = id(replacementNo)
                      if (!paymentId) {
                        setError('정정 후 확정된 지급 번호를 입력하세요.')
                        return
                      }
                      void show(replacementSelection(selection, paymentId), false, false, true)
                    }}
                  >
                    정정 지급으로 이어서 안내
                  </button>
                  <button disabled={busy} onClick={() => revisit(`/validation-runs?month=${selection.month}`)}>
                    새 월 검증 선택·생성
                  </button>
                  <button disabled={busy} onClick={() => revisit(destinations(selection)['validation.execute'].path)}>
                    월 검증 다시 확인
                  </button>
                  <button
                    disabled={busy}
                    onClick={() => revisit(destinations(selection)['reconciliation.review'].path)}
                  >
                    대사 결과 다시 선택
                  </button>
                </div>
              )}
            <details>
              <summary>전체 9단계 보기</summary>
              <ol>
                {titles.map((title) => (
                  <li key={title}>{title}</li>
                ))}
              </ol>
            </details>
          </>
        )}
        {error && <p role="alert">{error}</p>}
        {!collapsed && (
          <small>안내는 저장·실행·확정을 대신하지 않습니다. 종료해도 저장된 업무 데이터는 남습니다.</small>
        )}
      </aside>
      <Modal
        open={leave !== null}
        title="미저장 입력이 있습니다"
        onClose={() => setLeave(null)}
        initialFocusRef={stayButton}
        footer={
          <>
            <button className="button button-secondary" ref={stayButton} onClick={() => setLeave(null)}>
              현재 화면 유지
            </button>
            <button
              className="button button-primary"
              onClick={() => {
                const proceed = leave
                setLeave(null)
                proceed?.()
              }}
            >
              입력 버리고 이동
            </button>
          </>
        }
      >
        <p>이동하면 현재 화면의 미저장 입력이 사라질 수 있습니다. 저장을 대신 실행하지 않습니다.</p>
      </Modal>
    </>
  )
}
