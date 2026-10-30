import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { matchPath, useLocation, useNavigate } from 'react-router'
import { screens, canOpen, screenFor } from '../../app/screens'
import type { ShellUser } from '../../app/shell/Sidebar'
import { useWorkspaceStore, withMonth } from '../../stores/workspace'
import { GuideRuntime } from './guide/runtime'
import { GuideFailure, GuideTargets } from './guide/targets'
import { useSpotlight } from './guide/useSpotlight'
import type { GuideContext, GuideDefinition } from './guide/types'
import { WORKFLOW_GUIDES, findWorkflowGuide } from './workflow-catalog'
import { useWorkflowGuide } from './workflow-state'
import mascot from './assets/pico-guide.png'
import './payment-guide.css'
import { findGuide } from './catalog'
import { launchPaymentGuide } from '../transaction/payment-guide-launch'
import { toast } from '../../stores/toasts'

const messages: Record<string, string> = {
  SELECT_TARGET: '목록에서 안내받을 대상을 직접 선택한 뒤 현재 대상에서 다시 시작하세요.',
  DUPLICATE_TARGET: '같은 기능이 여러 행에 있습니다. 안내받을 행의 항목을 직접 선택한 뒤 이어가세요.',
  SCREEN_CHANGED: '다른 화면으로 이동했습니다. 돌아와 이어가거나 현재 업무 안내를 새로 시작할 수 있습니다.',
  CONTEXT_CHANGED: '기준월 또는 대상이 바뀌었습니다. 현재 정보를 확인한 뒤 다시 시작하세요.',
  ENTITY_CHANGED_RESTART_REQUIRED: '대상이 바뀌었습니다. 현재 대상에서 다시 시작하세요.',
  TARGET_CHANGED: '화면 구성이 바뀌었습니다. 현재 영역을 확인한 뒤 이어가세요.',
  TARGET_TIMEOUT: '안내 영역이 아직 보이지 않습니다. 필요한 탭·상세·확인 창을 직접 연 뒤 이어가세요.',
  USER_PAUSED: '안내를 잠시 멈췄습니다. 다른 화면을 자유롭게 확인할 수 있습니다.',
  PERMISSION_DENIED: '현재 계정으로 열 수 없는 업무입니다.',
  CONDITION_NOT_READY: '현재 항목을 입력하거나 검색 결과의 실제 항목을 선택한 뒤 다음 안내로 진행하세요.',
}

function waitForScreen(id: string, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const stop = () => { observer.disconnect(); clearTimeout(timer); signal.removeEventListener('abort', abort) }
    const check = () => {
      if (document.querySelector<HTMLElement>('[data-guide-page]')?.dataset.guidePage === id) { stop(); resolve() }
    }
    const abort = () => { stop(); reject(new GuideFailure('CANCELLED')) }
    const observer = new MutationObserver(check)
    const timer = setTimeout(() => { stop(); reject(new GuideFailure('TARGET_TIMEOUT')) }, 5000)
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-guide-page'] })
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
    else check()
  })
}

export function WorkspaceGuide({ user }: { user: ShellUser }) {
  if (import.meta.env.VITE_ASSISTANT_AI !== 'true') return null
  return <WorkspaceGuideSession key={`${user.loginId}:${user.roleCode}:${user.canProcess}:${user.canViewAuditLog}`} user={user} />
}

function WorkspaceGuideSession({ user }: { user: ShellUser }) {
  const navigate = useNavigate()
  const location = useLocation()
  const request = useWorkflowGuide((state) => state.request)
  const card = useRef<HTMLElement>(null)
  const mounted = useRef(false)
  const [runtime] = useState(() => {
    const targets = new GuideTargets()
    const selected = new Map<string, HTMLElement>()
    const allowed = WORKFLOW_GUIDES.filter((guide) => guide.roles.includes(user.roleCode))
    const registered = new Set<string>()
    for (const guide of allowed) for (const step of guide.steps) {
      const key = `${step.screenId}:${step.targetId}`
      if (registered.has(key)) continue
      registered.add(key)
      targets.register({ screenId: step.screenId, guideVersion: guide.guideVersion, targetId: step.targetId, attribute: 'data-guide-feature', root: () => {
        if (document.querySelector<HTMLElement>('[data-guide-page]')?.dataset.guidePage !== step.screenId) return null
        const picked = selected.get(key)
        return picked?.isConnected ? picked : document.body
      } })
    }
    const definitions: GuideDefinition[] = allowed.map((guide) => ({
      workflowId: guide.workflowId, guideVersion: guide.guideVersion, title: guide.title, roles: guide.roles,
      keywords: [], exampleQuestions: [], requiredEntities: [],
      steps: guide.steps.map((step) => ({ ...step, entryConditionId: 'AVAILABLE', completionConditionId: step.completionRuleId })),
    }))
    const readContext = (): GuideContext => ({
      screenId: document.querySelector<HTMLElement>('[data-guide-page]')?.dataset.guidePage ?? 'CMN-C04',
      role: user.roleCode, entityRefs: [],
      clientContextRevision: `${useWorkspaceStore.getState().month ?? ''}:${(() => {
        const pathname = document.querySelector<HTMLElement>('[data-guide-page]')?.dataset.guidePathname ?? ''
        const current = screenFor(pathname)
        const guide = findWorkflowGuide(engine.getSnapshot().workflowId ?? useWorkflowGuide.getState().request?.id ?? '', '1.0')
        const target = screens.find((screen) => screen.path === guide?.route)
        return guide?.route.includes(':') && current != null && target != null && current.menu === target.menu
          ? matchPath({ path: current.path, end: true }, pathname)?.params.id ?? '' : ''
      })()}`, serverStateVersion: null,
      // Global route protection owns unsaved edits. These steps only acknowledge instructions, never business success.
      dirty: false,
    })
    const engine: GuideRuntime<{ available: boolean; inputValid: boolean }> = new GuideRuntime(definitions, targets, {
      readContext, readBusinessState: async (): Promise<{ available: boolean; inputValid: boolean }> => {
        const current = engine.getSnapshot()
        const target = current.step ? targets.find(current.step.screenId, '1.0', current.step.targetId) : null
        const control = target instanceof HTMLInputElement || target instanceof HTMLSelectElement || target instanceof HTMLTextAreaElement ? target : null
        return {
          available: target != null && !(control?.disabled) && !(target instanceof HTMLButtonElement && target.disabled),
          inputValid: target?.getAttribute('role') === 'tab' ? target.getAttribute('aria-selected') === 'true' : control != null && !control.disabled && control.validity.valid && control.getAttribute('aria-invalid') !== 'true' &&
            control.getAttribute('aria-expanded') !== 'true' && (control instanceof HTMLInputElement && ['radio', 'checkbox'].includes(control.type) ? control.checked : control.value.trim() !== ''),
        }
      },
      conditions: { AVAILABLE: (_context, state) => state.available ? 'SATISFIED' : 'WAITING_INPUT', USER_REVIEWED: () => 'SATISFIED',
        INPUT_VALID: (_context, state) => state.inputValid ? 'SATISFIED' : 'WAITING_INPUT' },
      confirmLeave: async () => false,
      needsNavigation: (step) => {
        const guide = findWorkflowGuide(engine.getSnapshot().workflowId ?? '', '1.0')
        const pathname = document.querySelector<HTMLElement>('[data-guide-page]')?.dataset.guidePathname ?? ''
        return guide?.steps[0].screenId === step.screenId && !matchPath({ path: guide.route, end: true }, pathname)
      },
      openScreen: async (id, signal) => {
        const guide = findWorkflowGuide(engine.getSnapshot().workflowId ?? '', '1.0')
        const screen = (guide?.steps[0].screenId === id ? screens.find((screen) => screen.path === guide.route) : null)
          ?? screens.find((screen) => screen.id === id && !screen.path.includes(':')) ?? screens.find((screen) => screen.id === id)
        if (!screen || !canOpen(screen, user)) throw new GuideFailure('PERMISSION_DENIED')
        if (screen.path.includes(':')) {
          const pathname = document.querySelector<HTMLElement>('[data-guide-page]')?.dataset.guidePathname ?? ''
          const current = screenFor(pathname)
          const selectedId = current?.menu === screen.menu ? matchPath({ path: current.path, end: true }, pathname)?.params.id : undefined
          if (selectedId && /^[1-9][0-9]*$/.test(selectedId)) {
            await navigate(withMonth(screen.path.replace(':id', encodeURIComponent(selectedId)), useWorkspaceStore.getState().month ?? ''))
            await waitForScreen(id, signal)
            return
          }
          const parent = screens.find((entry) => entry.path === screen.menu)
          if (parent && canOpen(parent, user)) await navigate(withMonth(parent.path, useWorkspaceStore.getState().month ?? ''))
          throw new GuideFailure('SELECT_TARGET')
        }
        const path = guide?.steps[0].screenId === id && !guide.route.includes(':') ? guide.route : screen.path
        await navigate(withMonth(path, useWorkspaceStore.getState().month ?? ''))
        await waitForScreen(id, signal)
      },
    })
    return { engine, readContext, selected }
  })
  const snapshot = useSyncExternalStore(runtime.engine.observe.bind(runtime.engine), runtime.engine.getSnapshot)
  const position = useSpotlight(snapshot.target, card)
  useEffect(() => {
    useWorkflowGuide.setState({ active: !['IDLE', 'PAUSED', 'COMPLETED', 'ERROR'].includes(snapshot.status),
      guideContext: snapshot.workflowId && snapshot.step ? { workflowId: snapshot.workflowId, stepId: snapshot.step.stepId } : null })
  }, [snapshot.status, snapshot.workflowId, snapshot.step])
  useEffect(() => {
    if (!request) { runtime.engine.stop(); return }
    const payment = findGuide(request.id, '1.0')
    if (payment) {
      const controller = new AbortController()
      void (async () => {
        try {
          const screen = screens.find((screen) => screen.id === 'TRAN-W02')!
          if (!payment.roles.includes(user.roleCode as typeof payment.roles[number]) || !canOpen(screen, user)) throw new GuideFailure('PERMISSION_DENIED')
          if (runtime.readContext().screenId !== screen.id) await navigate(withMonth(screen.path, useWorkspaceStore.getState().month ?? ''))
          await waitForScreen(screen.id, controller.signal)
          const deadline = Date.now() + 5000
          while (!controller.signal.aborted && Date.now() < deadline) {
            if (launchPaymentGuide(payment.workflowId)) {
              if (useWorkflowGuide.getState().request === request) useWorkflowGuide.getState().clear()
              return
            }
            await new Promise((resolve) => setTimeout(resolve, 50))
          }
          if (!controller.signal.aborted) throw new GuideFailure('SCREEN_UNAVAILABLE')
        } catch {
          if (!controller.signal.aborted) {
            toast('지급 안내를 열지 못했습니다. 현재 화면과 권한을 확인한 뒤 다시 시작해주세요.', 'warning')
            useWorkflowGuide.getState().clear()
          }
        }
      })()
      return () => controller.abort()
    }
    const guide = findWorkflowGuide(request.id, '1.0')
    if (!guide || !guide.roles.includes(user.roleCode)) { useWorkflowGuide.getState().clear(); return }
    runtime.engine.stop()
    runtime.selected.clear()
    const context = runtime.readContext()
    void runtime.engine.start({ workflowId: guide.workflowId, stepId: guide.steps[0].stepId, guideVersion: guide.guideVersion,
      entityRefs: context.entityRefs, clientContextRevision: context.clientContextRevision, serverStateVersion: null })
  }, [request, runtime, user, navigate])
  useEffect(() => { runtime.engine.contextChanged() }, [location.pathname, location.search, runtime])
  useEffect(() => {
    mounted.current = true
    const mutations = new MutationObserver(() => runtime.engine.contextChanged())
    mutations.observe(document.body, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['hidden', 'inert', 'aria-hidden', 'data-guide-page', 'data-guide-feature'] })
    const choose = (event: MouseEvent) => {
      const element = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-guide-feature]') : null
      const step = runtime.engine.getSnapshot().step
      if (element && step && element.dataset.guideFeature === step.targetId) runtime.selected.set(`${step.screenId}:${step.targetId}`, element)
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('[aria-modal="true"]')) { runtime.engine.stop(); useWorkflowGuide.getState().clear() }
    }
    document.addEventListener('click', choose, true)
    document.addEventListener('keydown', escape)
    return () => {
      mounted.current = false
      const pending = useWorkflowGuide.getState().request
      document.removeEventListener('click', choose, true)
      document.removeEventListener('keydown', escape)
      mutations.disconnect()
      queueMicrotask(() => {
        if (!mounted.current) {
          runtime.engine.dispose()
          if (useWorkflowGuide.getState().request === pending) useWorkflowGuide.getState().clear()
        }
      })
    }
  }, [runtime])
  if (snapshot.status === 'IDLE') return null
  const guide = findWorkflowGuide(snapshot.workflowId ?? '', '1.0')
  const stop = () => { runtime.engine.stop(); useWorkflowGuide.getState().clear() }
  return createPortal(<>
    {position.box && snapshot.status !== 'PAUSED' && <div className="payment-guide-spotlight" style={position.box} aria-hidden="true" />}
    <section ref={card} className="payment-guide-card" aria-label="업무 튜토리얼" style={{ ...position.card, left: position.card.left ?? 'auto', transform: 'none' }}>
      <header><img src={mascot} alt="" /><div><strong>{guide?.title}</strong><small>화면과 입력 위치 안내</small></div><button onClick={stop} aria-label="업무 튜토리얼 종료">닫기</button></header>
      <h3>{snapshot.status === 'COMPLETED' ? '안내를 마쳤어요' : guide?.steps.find((step) => step.stepId === snapshot.step?.stepId)?.title}</h3>
      <p>{snapshot.status === 'COMPLETED' ? '안내 확인을 마쳤습니다. 실제 저장·검증·확정 결과는 기존 업무 화면에서 확인해주세요.' : snapshot.step?.instruction}</p>
      {snapshot.reasonCode && <p role="status">{messages[snapshot.reasonCode] ?? '화면의 조건을 확인하고 필요한 영역을 연 뒤 이어가세요.'}</p>}
      <nav aria-label="튜토리얼 단계 이동">
        <button onClick={() => void runtime.engine.previous()} disabled={['PREPARING', 'COMPLETED'].includes(snapshot.status)}>이전 안내</button>
        {snapshot.status === 'PAUSED' ? <>
          <button onClick={() => void runtime.engine.resume()}>돌아와 이어가기</button>
          <button onClick={() => guide && useWorkflowGuide.getState().start(guide.workflowId)}>현재 대상에서 다시 시작</button>
        </> : snapshot.status !== 'COMPLETED' && <button disabled={snapshot.status === 'PREPARING'} onClick={() => void runtime.engine.next()}>확인했어요 · 다음 안내</button>}
        {snapshot.status === 'COMPLETED' ? <button onClick={stop}>안내 닫기</button> : <button onClick={() => runtime.engine.pause()}>잠시 멈추기</button>}
      </nav>
      <small>업무 버튼은 직접 누르세요. 안내 확인은 실제 업무 처리 완료를 뜻하지 않습니다.</small>
    </section>
  </>, document.body)
}
