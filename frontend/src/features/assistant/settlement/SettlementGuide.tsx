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

const id = (value: string | null) =>
  value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : undefined
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
  const [snapshot, setSnapshot] = useState<GuideSnapshot | null>(null)
  const card = useRef<HTMLElement | null>(null)
  const position = useSpotlight(snapshot?.target ?? null, card)
  const runtime = useRef<GuideRuntime<Evidence> | null>(null)
  const request = useRef<AbortController | null>(null)
  const epoch = useRef(0)
  const dirtyForm = useRef<Element | null>(null)
  const acknowledged = useRef('')
  const current = useRef({ location, user, selection, dirty: false })
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
        setCollapsed(true)
        current.current.dirty = true
        dirtyForm.current = (event.target as HTMLElement).closest('form, [data-transaction-form]')
        runtime.current?.pause('INPUT_CHANGED')
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
    current.current.dirty = false
    runtime.current?.contextChanged()
  }, [location.pathname, location.search, user.roleCode])
  useEffect(() => {
    const target = runtime.current?.getSnapshot().target
    if (!target) return
    target.classList.add('settlement-spotlight')
    const observer = new MutationObserver(() => runtime.current?.contextChanged())
    observer.observe(document.getElementById('main-content')!, { childList: true, subtree: true })
    return () => {
      target.classList.remove('settlement-spotlight')
      observer.disconnect()
    }
  }, [snapshot])

  function collect(base: Selection): Selection {
    const path = current.current.location.pathname
    const params = new URLSearchParams(current.current.location.search)
    const next = { ...base }
    const contract = path.match(/^\/contracts\/(\d+)$/)?.[1]
    if (contract) {
      if (next.contractId && next.contractId !== Number(contract))
        throw new Error('다른 계약을 선택했습니다. 종료 후 현재 대상으로 새 안내를 시작하세요.')
      next.contractId = Number(contract)
    }
    if (path === '/transactions/new') {
      const context = getPaymentGuideObserver()?.readContext()
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
  async function show(base: Selection, initial = false, acknowledge = false, replacePayment = false) {
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
      const sourceLocation = current.current.location.pathname + current.current.location.search
      const next = initial || replacePayment ? base : collect(base)
      const evidence = await readEvidence(next, signal)
      signal.throwIfAborted()
      if (current.current.user.roleCode !== user.roleCode)
        throw new Error('권한이 바뀌었습니다. 현재 권한으로 다시 시작하세요.')
      if (token !== epoch.current) return
      if (sourceLocation !== current.current.location.pathname + current.current.location.search)
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
      const dirty = paymentContext?.dirty ?? (current.current.dirty && !!dirtyForm.current?.isConnected)
      const pathNow = current.current.location.pathname
      if (pathNow !== destination.path.split('?')[0] && dirty) {
        throw new Error(
          '미저장 입력이 있습니다. 현재 화면에서 저장하거나 입력을 보존한 뒤 다시 확인하세요. 안내 이동은 취소했습니다.',
        )
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
        clientContextRevision: String(token),
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
      if (
        destination.screen === 'CONT-W02' &&
        current.current.location.pathname === destination.path.split('?')[0] &&
        new URLSearchParams(current.current.location.search).get('tab') !== 'schedules'
      )
        await navigate(destination.path)
      await runner.start({
        workflowId: catalog.workflowId,
        guideVersion: FLOW_VERSION,
        stepId: result.step,
        entityRefs: refs,
        clientContextRevision: String(token),
        serverStateVersion: null,
      })
      if (
        (result.step === 'reconciliation.review' && !next.reconciliationRunId) ||
        (result.step === 'validation.execute' && !next.validationRunId)
      )
        setCollapsed(true)
      if (result.complete) await runner.next()
    } catch (failure) {
      if (!controller.signal.aborted && token === epoch.current)
        setError(failure instanceof Error ? failure.message : '최신 상태를 확인하지 못했습니다.')
    } finally {
      if (token === epoch.current) setBusy(false)
    }
  }
  async function start() {
    const controller = new AbortController()
    request.current?.abort()
    request.current = controller
    setBusy(true)
    setError('')
    try {
      const base: Selection = { month, stage }
      if (paymentNo) {
        const paymentId = id(paymentNo)
        if (!paymentId) throw new Error('올바른 지급 번호를 입력하세요.')
        const payment = await readPayment(String(paymentId), controller.signal)
        const contracts = [...new Set(payment.attributions?.map((row) => row.contractId))]
        if (contracts.length !== 1 || !contracts[0]) throw new Error('하나의 계약에 귀속된 지급 건을 선택하세요.')
        base.contractId = contracts[0]
        base.paymentId = paymentId
      } else if (contractNo.trim()) {
        const page = await guideGet<ContractPage>(
          `/contracts?contractNo=${encodeURIComponent(contractNo.trim())}&page=1&size=20`,
          controller.signal,
        )
        const exact = page.content?.filter((item) => item.contractNo === contractNo.trim())
        if (exact?.length !== 1 || !exact[0].contractId) throw new Error('정확한 기존 계약번호를 입력하세요.')
        base.contractId = exact[0].contractId
      }
      controller.signal.throwIfAborted()
      await show(base, true)
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '시작할 수 없습니다.')
    } finally {
      setBusy(false)
    }
  }
  function revisit(path: string) {
    const dirty =
      getPaymentGuideObserver()?.readContext().dirty ?? (current.current.dirty && !!dirtyForm.current?.isConnected)
    if (dirty) {
      setError('미저장 입력이 있어 이동을 취소했습니다. 먼저 현재 화면에서 저장하세요.')
      return
    }
    runtime.current?.pause('USER_NAVIGATION')
    setCollapsed(true)
    void navigate(path)
  }
  return (
    <aside
      ref={card}
      style={{
        left: 'auto',
        right: 'auto',
        top: 'auto',
        bottom: 'auto',
        ...(collapsed ? { top: 80, right: 16 } : position.card),
      }}
      className="settlement-guide"
      aria-label="전체 업무 가이드"
      aria-busy={busy}
    >
      <header>
        <img src="/images/brand/agent-mascot.png" alt="" className="settlement-guide-mascot" width="32" height="32" />
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
        <button disabled={busy} onClick={() => void show(selection)}>
          현재 결과로 이어서 안내
        </button>
      ) : !selection ? (
        <>
          <label>
            지급단계
            <select value={stage} onChange={(event) => setStage(event.target.value as Selection['stage'])}>
              <option value="GA_TO_FC">GA → 설계사</option>
              <option value="INSURER_TO_GA">보험사 → GA</option>
            </select>
          </label>
          <label>
            기존 계약번호 (새 계약은 비워두세요)
            <input value={contractNo} onChange={(event) => setContractNo(event.target.value)} disabled={!!paymentNo} />
          </label>
          <label>
            기존 지급 번호로 이어서 안내 (선택)
            <input inputMode="numeric" value={paymentNo} onChange={(event) => setPaymentNo(event.target.value)} />
          </label>
          <button className="button button-primary" disabled={busy} onClick={() => void start()}>
            안내 시작
          </button>
        </>
      ) : (
        <>
          <p>{decision ? `${STEPS.indexOf(decision.step) + 1}/9 · ${titles[STEPS.indexOf(decision.step)]}` : ''}</p>
          <p>{decision?.reason}</p>
          {snapshot?.status === 'PAUSED' || snapshot?.status === 'ERROR' ? (
            <p role="status">안내 대상을 확인하지 못했거나 화면·입력이 바뀌었습니다. 현재 결과로 다시 확인하세요.</p>
          ) : null}
          {snapshot?.status === 'COMPLETED' ? (
            <strong>전체 업무 안내 완료</strong>
          ) : (
            <div className="settlement-guide-actions">
              {decision?.step === 'schedule.review' && (
                <button disabled={busy} onClick={() => void show(selection, false, true)}>
                  스케줄 확인 완료
                </button>
              )}
              <button disabled={busy} onClick={() => void show(selection)}>
                현재 결과로 이어서 안내
              </button>
            </div>
          )}
          {decision?.step === 'validation.execute' && !user.canProcess && (
            <button onClick={() => revisit(`/validation-runs?month=${selection.month}`)}>월 검증 결과 선택</button>
          )}
          {decision?.step === 'exception.resolve' &&
            !handoffFor('exception.resolve', user.roleCode ?? '', user.canProcess) && (
              <div className="settlement-guide-actions">
                <label>
                  정정 후 지급 번호
                  <input
                    inputMode="numeric"
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
                <button onClick={() => revisit(`/validation-runs?month=${selection.month}`)}>
                  새 월 검증 선택·생성
                </button>
                <button onClick={() => revisit(destinations(selection)['validation.execute'].path)}>
                  월 검증 다시 확인
                </button>
                <button onClick={() => revisit(destinations(selection)['reconciliation.review'].path)}>
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
      {!collapsed && <small>안내는 저장·실행·확정을 대신하지 않습니다. 종료해도 저장된 업무 데이터는 남습니다.</small>}
    </aside>
  )
}
