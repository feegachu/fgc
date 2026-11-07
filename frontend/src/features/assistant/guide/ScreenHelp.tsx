import { Fragment, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { ReactNode, Ref } from 'react'
import { createPortal } from 'react-dom'
import { useInRouterContext, useLocation, useOutletContext } from 'react-router'
import type { ShellContext } from '../../../app/shell/AppShell'
import { Button } from '../../../components/Button'
import { closeSettlementGuide, settlementEntry, subscribeSettlementEntry } from '../settlement/entry'
import { GuideRuntime } from './runtime'
import { GuideTargets } from './targets'
import { useSpotlight } from './useSpotlight'
import type { GuideDefinition } from './types'
import definitions from './screen-help.json'
import guideMascot from '../assets/pico-guide.png'
import type { SaveGuide } from './save-guide'
const noSave = { valid: false, pending: false, savedId: null, error: '' }
const noSubscribe = () => () => {}
const noSnapshot = () => noSave
const targetMessages: Record<string, string> = {
  CONTEXT_CHANGED: '입력이나 선택 대상이 바뀌었습니다. 현재 내용을 확인한 후 다시 찾으세요.',
  DUPLICATE_TARGET: '안내 대상이 여러 곳에 표시되어 있습니다. 대상 선택이나 열린 화면을 확인한 후 다시 찾으세요.',
  TARGET_CHANGED: '안내 대상이 숨겨지거나 화면 구성이 바뀌었습니다. 해당 탭이나 영역을 연 후 다시 찾으세요.',
  TARGET_TIMEOUT: '안내 영역을 찾지 못했습니다. 화면 로딩이 끝났는지 확인하고 필요한 탭이나 상세를 연 후 다시 찾으세요.',
  PREPARATION_TIMEOUT: '안내 영역이 5초 안에 준비되지 않았습니다. 화면 로딩이 끝났는지 확인하고 필요한 탭이나 상세를 연 후 다시 찾으세요.',
  TARGET_VERSION_OR_REGISTRATION_MISSING: '현재 화면과 도움말 버전이 맞지 않습니다. 입력을 보존한 뒤 화면을 다시 열어 주세요.',
}
export interface ScreenHelpStep {
  targetId: string
  selector?: string
  firstMatch?: boolean
  anchor?: 'start'
  concise?: boolean
  /** Read the current preview's one-line explanation without changing the step. */
  liveDescriptionSelector?: string
  presentation?: { side?: 'right' | 'left' | 'above' | 'either'; includeLabel?: boolean; sideBoundary?: string }
  openControl?: boolean
  advanceOnSelection?: boolean
  /** Presentation lifecycle only: replay a card or start/stop a chart preview. */
  onEnter?: () => void | (() => void)
  attribute?: 'data-guide-target' | 'data-guide-feature'
  scope?: 'shell'
  group: string
  title: string
  instruction: string
  details: readonly { label: string; text: string }[]
  interaction: string
}
type HelpProps = { screenId: string; variant?: string; walkthrough?: readonly ScreenHelpStep[]; saveGuide?: SaveGuide; finalizeGuide?: SaveGuide; runStatus?: string; outcome?: 'execution' | 'processing' | 'finalization' | 'correction' }
import '../../transaction/payment-guide.css'
import './screen-help.css'
import { ScreenWalkthrough } from './ScreenWalkthrough'
import { getScreenWalkthrough } from './page-guides'

function HelpIcon() {
  return <svg className="screen-help-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 1.5-2.5 2-2.5 4m0 3h.01" />
  </svg>
}

/** Read-only guide orchestration; existing business handlers own all writes and report save outcomes. */
export function ScreenHelp({ walkthrough, legacyTrigger, disabled, triggerRef, ...props }: HelpProps & { legacyTrigger?: ReactNode; disabled?: boolean; triggerRef?: Ref<HTMLButtonElement> }) {
  const { screenId, variant } = props
  const routed = useInRouterContext()
  const helpVersion = useOutletContext<ShellContext | null>()?.helpVersion ?? 'after'
  const definition = definitions.find((entry) => entry.screenId === screenId && entry.variant === variant)
  if (!definition) return <button type="button" className="button button-secondary screen-help-trigger" disabled title="등록된 도움말 버전을 찾을 수 없습니다."><HelpIcon />도움말</button>
  if (helpVersion === 'after') {
    const tour = <ScreenWalkthrough title={definition.title} steps={walkthrough ?? getScreenWalkthrough(screenId, variant)} disabled={disabled} triggerRef={triggerRef} />
    return routed ? <RoutedWalkthrough screenId={screenId}>{tour}</RoutedWalkthrough> : tour
  }
  return <Fragment key={helpVersion}>
    {legacyTrigger ?? (routed ? <RoutedScreenHelp {...props} /> : <ScreenHelpSession {...props} />)}
  </Fragment>
}

function RoutedWalkthrough({ screenId, children }: { screenId: string; children: ReactNode }) {
  const location = useLocation()
  const context = new URLSearchParams(location.search)
  const selectionParams: Record<string, string[]> = {
    'CONT-W02': ['tab', 'capCheckId'], 'EXCP-W01': ['selected'], 'AUDT-W01': ['selected'],
    'CAP-W01': ['capCheckId'], 'POL-W01': ['policyVersionId', 'tab', 'section'],
    'TRAN-W01': ['selectedId'], 'SCHE-W01': ['selectedId'], 'RPT-W02': ['entryKey'],
    'LEDG-W01': ['selected'], 'RECO-W01': ['resultId'], 'RECO-W02': ['resultId'],
  }
  selectionParams[screenId]?.forEach(key => context.delete(key))
  context.sort()
  return <Fragment key={`${location.pathname}?${context}`}>{children}</Fragment>
}

function RoutedScreenHelp(props: HelpProps) {
  const { screenId, saveGuide } = props
  const location = useLocation()
  const context = new URLSearchParams(location.search)
  if (screenId === 'RECO-W01') Array.from(context.keys()).filter(key => key !== 'month' && key !== 'runId').forEach(key => context.delete(key))
  // Read-only row selection may prepare a target without discarding the explanation.
  if (!saveGuide) {
    const selectionParams: Record<string, string[]> = {
      'EXCP-W01': ['selected'], 'AUDT-W01': ['selected'], 'CAP-W01': ['capCheckId'],
      'POL-W01': ['policyVersionId', 'tab', 'section'], 'TRAN-W01': ['selectedId'], 'SCHE-W01': ['selectedId'],
      'RPT-W02': ['entryKey'], 'LEDG-W01': ['selected'],
    }
    selectionParams[screenId]?.forEach(key => context.delete(key))
  }
  // Registered contract tab preparation changes the URL, but not the selected contract/month.
  if (screenId === 'CONT-W02') { context.delete('tab'); context.delete('capCheckId') }
  return <ScreenHelpSession key={`${location.pathname}?${context}`} {...props} />
}
function ScreenHelpSession({ screenId, variant, walkthrough, saveGuide, finalizeGuide, runStatus, outcome }: HelpProps) {
  const action = outcome === 'correction' ? '정정' : outcome === 'execution' ? '실행' : outcome === 'processing' ? '처리' : outcome === 'finalization' ? '확정' : '저장'
  const baseDefinition = definitions.find((entry) => entry.screenId === screenId && entry.variant === variant)!
  const definition = useMemo(() => walkthrough ? { ...baseDefinition, steps: walkthrough } : baseDefinition, [baseDefinition, walkthrough])
  // Usage help has no business capabilities; screen/action permissions remain with the page.
  const role = 'READER'
  const [trigger, setTrigger] = useState<HTMLButtonElement | null>(null)
  const card = useRef<HTMLElement>(null)
  const revision = useRef(0)
  const currentStatus = useRef(runStatus)
  useEffect(() => { currentStatus.current = runStatus }, [runStatus])
  const validation = screenId === 'VRUN-W02' && !variant && Boolean(finalizeGuide)
  const [open, setOpen] = useState(false)
  const [folded, setFolded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [modal, setModal] = useState(false)
  const runtime = useMemo(() => {
    const guide: GuideDefinition = {
      workflowId: `screen.${screenId.toLowerCase()}${variant ? `.${variant}` : ''}`, guideVersion: '3.0', title: definition.title,
      keywords: [], exampleQuestions: [], roles: [role], requiredEntities: [],
      steps: definition.steps.map((step, index) => {
        const completion = validation ? ({ 'validation.execute': 'EXECUTION_RESULT', 'validation.restart': 'READ', 'validation.checklist': 'READ', 'validation.finalize': 'FINALIZED' }[step.targetId]) : undefined
        return {
          ...step, screenId, stepId: `help.${index}`, entryConditionId: 'READ',
          completionConditionId: completion ?? (saveGuide ? (index === 0 ? 'INPUT' : 'SAVED') : 'READ'),
          next: validation && step.targetId === 'validation.execute'
            ? [{ conditionId: 'FAILED', stepId: 'help.2' }, { conditionId: 'SUCCEEDED', stepId: 'help.3' }]
            : validation && step.targetId === 'validation.restart' ? []
              : index + 1 < definition.steps.length ? [{ conditionId: 'READ', stepId: `help.${index + 1}` }] : [],
        }
      }),
    }
    const targets = new GuideTargets()
    for (const step of guide.steps) targets.register({
      screenId, guideVersion: guide.guideVersion, targetId: step.targetId,
      attribute: walkthrough?.find(item => item.targetId === step.targetId)?.attribute,
      root: () => walkthrough?.find(item => item.targetId === step.targetId)?.scope === 'shell'
        ? trigger?.closest<HTMLElement>('.app-shell') ?? null
        : trigger?.closest<HTMLElement>('[data-guide-scope]') ?? trigger?.closest<HTMLElement>('[role="dialog"]') ?? document.getElementById('main-content'),
      prepare: async (signal) => {
        if (signal.aborted) return
        // Only registered tab controls; never click a business action.
        const tabId = step.targetId === 'contract.schedules' ? 'contract-tab-schedules'
          : step.targetId === 'contract.statusHistory' ? 'contract-tab-basic' : null
        if (tabId) {
          const tab = document.getElementById(tabId)
          if (tab?.getAttribute('aria-selected') !== 'true') tab?.click()
        }
      },
    })
    return new GuideRuntime([guide], targets, {
      readContext: () => ({ screenId, role, entityRefs: [], clientContextRevision: String(revision.current), serverStateVersion: null, dirty: true }),
      readBusinessState: async () => ({ ...(saveGuide?.getSnapshot() ?? noSave), finalized: finalizeGuide?.getSnapshot().savedId != null, failed: currentStatus.current === 'FAILED' }),
      conditions: {
        READ: () => 'SATISFIED', INPUT: (_, state) => state.valid || state.savedId !== null ? 'SATISFIED' : 'WAITING_INPUT',
        SAVED: (_, state) => state.savedId !== null && !state.pending ? 'SATISFIED' : 'WAITING_RESULT',
        EXECUTION_RESULT: (_, state) => state.failed || (state.savedId !== null && !state.pending) ? 'SATISFIED' : 'WAITING_RESULT',
        FAILED: (_, state) => state.failed ? 'SATISFIED' : 'WAITING_RESULT',
        SUCCEEDED: (_, state) => !state.failed && state.savedId !== null && !state.pending ? 'SATISFIED' : 'WAITING_RESULT',
        FINALIZED: (_, state) => state.finalized ? 'SATISFIED' : 'WAITING_RESULT',
      },
      openScreen: async (requested) => { if (requested !== screenId) throw new Error('SCREEN_UNAVAILABLE') },
      confirmLeave: async () => false,
    })
  }, [definition, role, screenId, variant, trigger, saveGuide, finalizeGuide, validation, walkthrough])
  const saveState = useSyncExternalStore(saveGuide?.subscribe ?? noSubscribe, saveGuide?.getSnapshot ?? noSnapshot)
  const finalizeState = useSyncExternalStore(finalizeGuide?.subscribe ?? noSubscribe, finalizeGuide?.getSnapshot ?? noSnapshot)
  const snapshot = useSyncExternalStore(runtime.observe.bind(runtime), runtime.getSnapshot)
  const position = useSpotlight(open && !modal ? snapshot.target : null, card, Boolean(walkthrough))
  const close = () => { runtime.stop(); setOpen(false); saveGuide?.deactivate(); trigger?.focus() }
  useEffect(() => () => runtime.stop(), [runtime])
  useEffect(() => () => saveGuide?.deactivate(false), [saveGuide])
  useEffect(() => subscribeSettlementEntry(() => {
    if (settlementEntry()) { runtime.stop(); setOpen(false); saveGuide?.deactivate() }
  }), [runtime, saveGuide])
  useEffect(() => {
    if (!open) return
    const main = trigger?.closest<HTMLElement>('[data-guide-scope]') ?? trigger?.closest<HTMLElement>('[role="dialog"]') ?? document.getElementById('main-content')
    const input = () => { if (!saveGuide) { revision.current++; runtime.contextChanged() } }
    const observer = new MutationObserver(() => {
      setModal(Boolean(document.querySelector('[role="dialog"], [aria-modal="true"]')) && !trigger?.closest('[role="dialog"]'))
      runtime.contextChanged()
    })
    main?.addEventListener('input', input)
    // Dialogs are portalled outside main; visibility changes can also use CSS or inert.
    observer.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['hidden', 'inert', 'aria-hidden', 'data-guide-target', 'class', 'style'] })
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && (!document.querySelector('[role="dialog"], [aria-modal="true"]') || trigger?.closest('[role="dialog"]'))) {
        event.preventDefault(); event.stopImmediatePropagation(); runtime.stop(); setOpen(false); saveGuide?.deactivate(); trigger?.focus()
      }
    }
    document.addEventListener('keydown', escape, true)
    return () => { observer.disconnect(); main?.removeEventListener('input', input); document.removeEventListener('keydown', escape, true) }
  }, [open, runtime, trigger, saveGuide])
  const run = async (action: () => Promise<void>) => {
    if (busy) return
    setBusy(true)
    try { await action() } finally { setBusy(false) }
  }
  // 다시 시작, 다시 찾기를 누르면 그 자리 버튼이 꺼진 [이전]으로 바뀌어 포커스가 갇힌다. 끝나면 패널의 첫 활성 버튼으로 옮긴다.
  const refocus = useRef(false)
  useEffect(() => {
    if (busy || !refocus.current) return
    refocus.current = false
    const panel = card.current
    const active = document.activeElement
    if (active !== document.body && !panel?.contains(active)) return // 그새 패널 밖으로 옮긴 포커스는 뺏지 않는다.
    const next = panel?.querySelector<HTMLButtonElement>('.payment-guide-actions button:not(:disabled)') ?? panel?.querySelector<HTMLButtonElement>('button:not(:disabled)')
    next?.focus()
  }, [busy])
  useEffect(() => {
    const other = (event: Event) => {
      if ((event as CustomEvent).detail !== trigger) { runtime.stop(); setOpen(false); saveGuide?.deactivate() }
    }
    document.addEventListener('fgc-screen-help-open', other)
    return () => document.removeEventListener('fgc-screen-help-open', other)
  }, [runtime, trigger, saveGuide])
  const start = () => {
    document.dispatchEvent(new CustomEvent('fgc-screen-help-open', { detail: trigger }))
    closeSettlementGuide(); saveGuide?.activate(); setOpen(true); setFolded(false)
    void run(() => runtime.start({ workflowId: `screen.${screenId.toLowerCase()}${variant ? `.${variant}` : ''}`, stepId: 'help.0', guideVersion: '3.0', entityRefs: [], clientContextRevision: String(revision.current), serverStateVersion: null }))
  }
  const restart = () => { refocus.current = true; start() }
  const paused = ['PAUSED', 'ERROR'].includes(snapshot.status)
  const complete = snapshot.status === 'COMPLETED'
  const failedPath = validation && runStatus === 'FAILED'
  const staleCompletion = complete && saveGuide && (validation ? !failedPath && finalizeState.savedId === null : saveState.savedId === null)
  const index = definition.steps.findIndex((step) => step.targetId === snapshot.step?.targetId)
  const detailStep = walkthrough?.[index]
  useEffect(() => {
    if (walkthrough && card.current) card.current.scrollTop = 0
  }, [walkthrough, index])
  const needsFinalization = validation && snapshot.step?.targetId === 'validation.finalize'
  const nextReady = !saveGuide || (validation && ['validation.restart', 'validation.checklist'].includes(snapshot.step?.targetId ?? ''))
    || (validation && snapshot.step?.targetId === 'validation.execute' && failedPath)
    || (needsFinalization ? finalizeState.savedId !== null && !finalizeState.pending
      : !saveState.pending && (index === 0 ? saveState.valid || saveState.savedId !== null : saveState.savedId !== null))
  return <>
    <button type="button" ref={setTrigger} className="button button-secondary screen-help-trigger" aria-expanded={open} onClick={open ? close : start}><HelpIcon />도움말</button>
    {open && !modal && createPortal(<>
      {!folded && position.box && !paused && !complete && <div className={`payment-guide-spotlight${walkthrough ? ' screen-step-spotlight' : ''}`} aria-hidden="true" style={position.box} />}
      <section ref={card} className={`payment-guide-card${walkthrough ? ' screen-step-guide' : ''}`} aria-label={`${definition.title} 도움말`} data-help-target={snapshot.step?.targetId} data-guide-anchored={Boolean(snapshot.target && !modal)} style={position.card}>
        <header className="payment-guide-header">
          <img className="payment-guide-mascot" src={guideMascot} width="40" height="40" alt="" />
          <span className={walkthrough ? 'screen-step-count' : undefined}>{walkthrough ? '화면 요소 안내' : saveGuide ? `${action} 가이드` : '화면 사용법'} · {Math.max(1, index + 1)}/{definition.steps.length}</span>
          <Button variant="ghost" onClick={() => setFolded(!folded)}>{folded ? '펼치기' : '접기'}</Button>
          <Button variant="ghost" onClick={close}>종료</Button>
        </header>
        {!folded && <>
          {walkthrough && <>
            <progress className="screen-step-progress" max={definition.steps.length} value={Math.max(1, index + 1)} aria-label="도움말 진행" />
            <p className="screen-step-group">{detailStep?.group ?? '화면 요소를 찾고 있습니다'}</p>
            <span className="visually-hidden" role="status">{detailStep && `${index + 1}/${definition.steps.length} ${detailStep.title}`}</span>
          </>}
          <h2>{detailStep && !complete ? detailStep.title : staleCompletion ? '실행 결과 변경 · 다시 확인' : complete ? (validation ? (failedPath ? '신규 실행 절차 안내 완료' : '서버 확정 확인 · 가이드 완료') : saveGuide ? `서버 ${action} 확인 · 가이드 완료` : '화면 사용법 안내 완료') : definition.title}</h2>
          <p>{validation && complete && !staleCompletion ? (failedPath ? '실행 실패를 확인했습니다. 신규 실행 만들기로 이동해 새 검증을 생성하세요. 기존 실행을 최종 확정한 것이 아닙니다.' : `이 검증 실행의 서버 확정 결과를 확인했습니다. 검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.`) : staleCompletion ? '입력·선택 대상 또는 서버 결과가 바뀌었습니다. 현재 조건으로 안내를 다시 시작하세요.' : complete ? (saveGuide ? (outcome === 'correction' ? `서버 역분개·재기표 성공과 새 분개 번호 ${saveState.savedId}를 확인했습니다. 안내를 마치면 정정 이력을 갱신합니다. 재검증·재대사는 별도로 실행하세요.` : outcome === 'processing' ? `선택한 예외의 서버 처리 순번 ${saveState.savedId}를 확인했습니다. 원천 정정·재검증·재대사 완료와는 별개입니다. 안내를 마치면 처리 이력을 갱신합니다.` : outcome === 'finalization' ? `이 검증 실행의 서버 확정 결과를 확인했습니다. 검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.` : outcome === 'execution' ? `서버 실행 번호 ${saveState.savedId}의 완료 결과를 확인했습니다. 불일치 해소와 최종 확정은 별도 절차입니다.` : `서버에서 저장한 번호 ${saveState.savedId}를 확인했습니다. 안내를 마치면 저장 결과 화면으로 이동합니다.`) : '설명 확인을 마쳤습니다. 실제 저장·실행·확정 결과는 해당 업무 화면에서 확인하세요.') : outcome === 'execution' && saveState.savedId !== null && index <= 1 ? '이미 완료된 실행의 서버 결과를 확인했습니다. 다시 실행하지 않고 다음으로 진행하세요.' : snapshot.step?.instruction}</p>
          {saveGuide && !complete && !needsFinalization && <p role="status">{saveState.error || (saveState.pending ? `서버 ${action} 결과를 기다리고 있습니다.` : saveState.savedId !== null ? `서버 ${action} 성공 · ${outcome === 'processing' ? '처리 순번' : '번호'} ${saveState.savedId}. 다음을 눌러 완료를 확인하세요.` : index === 0 ? (saveState.valid ? '필수 입력과 형식 조건을 충족했습니다.' : '필수 입력·형식을 확인하세요. 입력 조건이 충족되어야 다음으로 진행할 수 있습니다.') : `강조된 실제 ${action} 버튼을 누르세요. 서버 완료 결과를 확인해야 다음으로 진행할 수 있습니다.`)}</p>}
          {needsFinalization && !complete && <p role="status">{finalizeState.savedId !== null ? '서버의 최종 확정을 확인했습니다. 다음으로 완료를 확인하세요.' : '검증 계산 완료와 최종 확정은 다릅니다. 실제 확정의 서버 결과를 기다리고 있습니다.'}</p>}
          {paused && <p role="status">{snapshot.status === 'ERROR' ? '시스템 오류가 발생했습니다. 잠시 후 다시 시작하세요.' : targetMessages[snapshot.reasonCode ?? ''] ?? '안내 대상을 표시할 수 없습니다. 대상 선택·탭·권한을 확인한 후 다시 찾으세요.'}</p>}
          {detailStep && !complete && <>
            <dl className="screen-step-details">
              {detailStep.details.map(item => <div key={item.label}><dt>{item.label}</dt><dd>{item.text}</dd></div>)}
            </dl>
            <p className="screen-step-interaction"><strong>화면에서 할 수 있는 일</strong>{detailStep.interaction}</p>
          </>}
          <div className="payment-guide-actions">
            {complete ? <><Button onClick={staleCompletion ? restart : close}>{staleCompletion ? '다시 시작' : '안내 마치기'}</Button>{!staleCompletion && <Button variant="secondary" disabled={busy} onClick={restart}>다시 보기</Button>}</> : snapshot.status === 'ERROR' ? <Button disabled={busy} onClick={restart}>다시 시작</Button> : paused ? <Button disabled={busy} onClick={() => { refocus.current = true; void run(() => runtime.resume()) }}>다시 찾기</Button> : <>
              <Button variant={walkthrough ? 'secondary' : 'primary'} disabled={busy || index <= 0 || (Boolean(walkthrough) && !runtime.canGoBack())} onClick={() => void run(() => runtime.previous())}>이전</Button>
              <Button className={walkthrough ? 'screen-step-next' : undefined} disabled={busy || !snapshot.target || !nextReady} onClick={() => walkthrough && index === definition.steps.length - 1 ? close() : void run(() => runtime.next())}>{walkthrough ? index === definition.steps.length - 1 ? '안내 마치기' : '다음' : saveGuide ? '다음' : '설명 확인 · 다음'}</Button>
            </>}
          </div>
        </>}
      </section>
    </>, trigger?.closest('[role="dialog"]') ?? document.body)}
  </>
}
