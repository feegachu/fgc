import { afterEach, describe, expect, it, vi } from 'vitest'
import { bindGuideRuntime } from './bindings'
import { PAYMENT_GUIDES } from './catalog'
import { GuideRuntime } from './runtime'
import { GuideTargets } from './targets'
import type { GuideContext, GuideDefinition, GuideEnvironment, GuideEvent, GuideStart } from './types'

interface State {
  valid: boolean
  saved: boolean
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
const runtimes: GuideRuntime<State>[] = []
afterEach(() => {
  runtimes.forEach((r) => r.dispose())
  runtimes.length = 0
  document.body.replaceChildren()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

function setup(
  options: {
    missing?: boolean
    duplicate?: boolean
    screen?: string
    dirty?: boolean
    role?: string
    guides?: GuideDefinition[]
  } = {},
) {
  const root = document.createElement('main')
  document.body.append(root)
  const add = (id: string) => {
    const node = document.createElement('button')
    node.dataset.guideTarget = id
    root.append(node)
    vi.spyOn(node, 'getClientRects').mockReturnValue([new DOMRect(0, 0, 50, 20)] as unknown as DOMRectList)
    node.scrollIntoView = vi.fn()
    return node
  }
  const input = options.missing ? null : add('payment.amount')
  const save = add('payment.save')
  const click = vi.fn()
  save.addEventListener('click', click)
  if (options.duplicate) add('payment.amount')
  let context: GuideContext = {
    screenId: options.screen ?? 'TRAN-W02',
    role: options.role ?? 'SETTLEMENT',
    entityRefs: [{ type: 'PAYMENT', id: 1 }],
    clientContextRevision: '1',
    serverStateVersion: '1',
    dirty: options.dirty ?? false,
  }
  let state: State = { valid: false, saved: false }
  const read = vi.fn(async () => ({ ...state }))
  const open = vi.fn(async (screenId: string) => {
    context = { ...context, screenId }
  })
  const confirm = vi.fn(async () => false)
  const preserve = vi.fn()
  const targets = new GuideTargets()
  const unregister = targets.register({
    screenId: 'TRAN-W02',
    guideVersion: '1.0',
    targetId: 'payment.amount',
    root: () => root,
  })
  targets.register({ screenId: 'TRAN-W02', guideVersion: '1.0', targetId: 'payment.save', root: () => root })
  const guide: GuideDefinition = {
    workflowId: 'payment.draft',
    guideVersion: '1.0',
    title: '초안',
    keywords: [],
    exampleQuestions: [],
    roles: ['SETTLEMENT', 'GA_ADMIN'],
    requiredEntities: ['PAYMENT'],
    steps: [
      {
        stepId: 'input',
        screenId: 'TRAN-W02',
        targetId: 'payment.amount',
        instruction: '입력',
        entryConditionId: 'ALWAYS',
        completionConditionId: 'VALID',
        next: [{ conditionId: 'ALWAYS', stepId: 'save' }],
      },
      {
        stepId: 'save',
        screenId: 'TRAN-W02',
        targetId: 'payment.save',
        instruction: '직접 저장',
        entryConditionId: 'VALID',
        completionConditionId: 'SAVED',
        next: [],
      },
    ],
  }
  const environment: GuideEnvironment<State> = {
    readContext: () => context,
    readBusinessState: read,
    openScreen: open,
    confirmLeave: confirm,
    preserveInput: preserve,
    conditions: {
      ALWAYS: () => 'SATISFIED',
      VALID: (_, value) => (value.valid ? 'SATISFIED' : 'WAITING_INPUT'),
      SAVED: (_, value) => (value.saved ? 'SATISFIED' : 'WAITING_RESULT'),
    },
  }
  const runtime = new GuideRuntime(options.guides ?? [guide], targets, environment)
  runtimes.push(runtime)
  const events: GuideEvent[] = []
  runtime.subscribe((event) => events.push(event))
  const request = (): GuideStart => ({
    workflowId: 'payment.draft',
    stepId: 'input',
    guideVersion: '1.0',
    entityRefs: context.entityRefs,
    clientContextRevision: context.clientContextRevision,
    serverStateVersion: context.serverStateVersion,
  })
  return {
    runtime,
    root,
    input,
    save,
    add,
    read,
    open,
    confirm,
    preserve,
    click,
    targets,
    unregister,
    guide,
    environment,
    events,
    request,
    updateContext: (patch: Partial<GuideContext>) => {
      context = { ...context, ...patch }
    },
    updateState: (patch: Partial<State>) => {
      state = { ...state, ...patch }
    },
  }
}

describe('FUN-087 execution boundary', () => {
  it('waits for input, points to save without clicking, and finishes only after read evidence', async () => {
    const s = setup()
    const network = vi.spyOn(globalThis, 'fetch')
    await s.runtime.start(s.request())
    expect(s.runtime.getSnapshot().target).toBe(s.input)
    await s.runtime.next()
    expect(s.runtime.getSnapshot().status).toBe('WAITING_INPUT')
    s.updateState({ valid: true })
    await s.runtime.next()
    expect(s.runtime.getSnapshot()).toMatchObject({ status: 'SHOWING', step: { stepId: 'save' } })
    await s.runtime.next()
    expect(s.runtime.getSnapshot().status).toBe('WAITING_RESULT')
    expect(s.click).not.toHaveBeenCalled()
    expect(network).not.toHaveBeenCalled()
    s.updateState({ saved: true })
    await s.runtime.next()
    expect(s.runtime.getSnapshot().status).toBe('COMPLETED')
    expect(s.events.at(-1)?.kind).toBe('FINISHED')
    expect(s.events.every((event) => !('entityRefs' in event))).toBe(true)
  })
  it('previous and stop preserve the form value and never click save', async () => {
    const s = setup()
    s.input!.textContent = '100000'
    s.updateState({ valid: true })
    await s.runtime.start(s.request())
    await s.runtime.next()
    await s.runtime.previous()
    s.runtime.stop()
    expect(s.input!.textContent).toBe('100000')
    expect(s.click).not.toHaveBeenCalled()
    expect(s.runtime.getSnapshot().status).toBe('IDLE')
  })
  it.each(['COMPLIANCE', 'AGENT'])('rejects processing guide for %s', async (role) => {
    const s = setup({ role })
    await s.runtime.start(s.request())
    expect(s.runtime.getSnapshot()).toMatchObject({ status: 'PAUSED', reasonCode: 'PERMISSION_DENIED', target: null })
    expect(s.read).not.toHaveBeenCalled()
  })
  it('rechecks changed roles before moving to another step', async () => {
    const s = setup()
    await s.runtime.start(s.request())
    s.updateContext({ role: 'COMPLIANCE' })
    await s.runtime.next()
    expect(s.runtime.getSnapshot().reasonCode).toBe('PERMISSION_DENIED')
  })
  it.each([
    { guideVersion: 'old' },
    { workflowId: 'javascript:alert(1)' },
    { stepId: 'unknown' },
    { clientContextRevision: 'old' },
    { entityRefs: [{ type: 'PAYMENT' as const, id: Number.MAX_SAFE_INTEGER + 1 }] },
  ])('rejects invalid start %j', async (patch) => {
    const s = setup()
    await s.runtime.start({ ...s.request(), ...patch })
    expect(s.runtime.getSnapshot()).toMatchObject({ status: 'PAUSED', target: null })
    expect(s.read).not.toHaveBeenCalled()
  })
  it('pauses on edits and explicitly resumes with new revision without carrying prior completion', async () => {
    const s = setup()
    await s.runtime.start(s.request())
    s.updateContext({ clientContextRevision: '2', dirty: true })
    s.runtime.contextChanged()
    expect(s.runtime.getSnapshot().reasonCode).toBe('CONTEXT_CHANGED')
    await s.runtime.resume()
    expect(s.runtime.getSnapshot().status).toBe('SHOWING')
    await s.runtime.next()
    expect(s.runtime.getSnapshot().status).toBe('WAITING_INPUT')
  })
  it('does not resume a different selected entity', async () => {
    const s = setup()
    await s.runtime.start(s.request())
    s.runtime.pause()
    s.updateContext({ entityRefs: [{ type: 'PAYMENT', id: 2 }] })
    await s.runtime.resume()
    expect(s.runtime.getSnapshot().reasonCode).toBe('ENTITY_CHANGED_RESTART_REQUIRED')
  })
  it('rejects business results that arrive after a revision change', async () => {
    const s = setup()
    await s.runtime.start(s.request())
    const pending = deferred<State>()
    s.read.mockReturnValueOnce(pending.promise)
    const next = s.runtime.next()
    s.updateContext({ clientContextRevision: '2' })
    pending.resolve({ valid: true, saved: true })
    await next
    expect(s.runtime.getSnapshot()).toMatchObject({ status: 'PAUSED', reasonCode: 'CONTEXT_CHANGED', target: null })
  })
  it('stop cancels even an adapter ignoring abort; its late result cannot restart the guide', async () => {
    const s = setup()
    const pending = deferred<State>()
    s.read.mockReturnValueOnce(pending.promise)
    const start = s.runtime.start(s.request())
    await vi.waitFor(() => expect(s.read).toHaveBeenCalled())
    s.runtime.stop()
    await start
    pending.resolve({ valid: true, saved: true })
    await Promise.resolve()
    await Promise.resolve()
    expect(s.runtime.getSnapshot().status).toBe('IDLE')
  })
  it('serializes repeated Next while business state is loading', async () => {
    const s = setup()
    await s.runtime.start(s.request())
    const pending = deferred<State>()
    s.read.mockReturnValueOnce(pending.promise)
    const next = s.runtime.next()
    await s.runtime.next()
    expect(s.read).toHaveBeenCalledTimes(2)
    pending.resolve({ valid: true, saved: false })
    await next
    expect(s.runtime.getSnapshot().step?.stepId).toBe('save')
  })
  it('does not navigate away from dirty input by default', async () => {
    const s = setup({ screen: 'CONT-W03', dirty: true })
    await s.runtime.start(s.request())
    expect(s.runtime.getSnapshot().reasonCode).toBe('UNSAVED_NAVIGATION_CANCELLED')
    expect(s.open).not.toHaveBeenCalled()
    expect(s.preserve).not.toHaveBeenCalled()
  })
  it('preserves input before approved navigation and retries on resume', async () => {
    const s = setup({ screen: 'CONT-W03', dirty: true })
    await s.runtime.start(s.request())
    s.confirm.mockResolvedValue(true)
    await s.runtime.resume()
    expect(s.preserve).toHaveBeenCalledTimes(1)
    expect(s.open).toHaveBeenCalledWith('TRAN-W02', expect.any(AbortSignal))
    expect(s.runtime.getSnapshot().status).toBe('SHOWING')
  })
  it('restores the initiating button focus on stop', async () => {
    const s = setup()
    s.save.focus()
    await s.runtime.start(s.request())
    s.input!.focus()
    s.runtime.stop()
    expect(document.activeElement).toBe(s.save)
  })
  it('isolates input catalog mutations and rejects unknown condition expressions', () => {
    const s = setup()
    s.guide.steps[0].instruction = 'changed'
    expect(
      () =>
        new GuideRuntime(
          [{ ...s.guide, steps: [{ ...s.guide.steps[0], entryConditionId: 'alert(1)' }] }],
          s.targets,
          s.environment,
        ),
    ).toThrow('UNREGISTERED_GUIDE_CONDITION')
  })
  it('adapts every #736 payment workflow without duplicating its text or target IDs', () => {
    const targets = new GuideTargets()
    const conditions: GuideEnvironment<State>['conditions'] = Object.fromEntries(
      PAYMENT_GUIDES.flatMap((g) =>
        g.steps.flatMap((s) => [s.entryConditionId, s.completionConditionId, 'ALWAYS']),
      ).map((id) => [id, () => 'SATISFIED']),
    )
    const registered = new Set<string>()
    for (const guide of PAYMENT_GUIDES)
      for (const step of guide.steps)
        if (!registered.has(step.targetId)) {
          registered.add(step.targetId)
          targets.register({
            screenId: step.screenId,
            guideVersion: guide.guideVersion,
            targetId: step.targetId,
            root: () => null,
          })
        }
    const s = setup()
    const runtime = new GuideRuntime(PAYMENT_GUIDES, targets, { ...s.environment, conditions })
    runtimes.push(runtime)
    expect(PAYMENT_GUIDES).toHaveLength(3)
    expect(PAYMENT_GUIDES.find((g) => g.workflowId === 'payment.draft')?.steps.at(-1)?.completionConditionId).toBe(
      'CURRENT_DRAFT_SAVED',
    )
  })
})

describe('target preparation and cleanup', () => {
  it('waits for a delayed target and scrolls only after it appears', async () => {
    vi.useFakeTimers()
    const s = setup({ missing: true })
    const start = s.runtime.start(s.request())
    await vi.advanceTimersByTimeAsync(100)
    const input = s.add('payment.amount')
    await vi.advanceTimersByTimeAsync(50)
    await start
    expect(s.runtime.getSnapshot().target).toBe(input)
    expect(input.scrollIntoView).toHaveBeenCalledTimes(1)
  })
  it('pauses after 5 seconds with no arbitrary fallback highlight', async () => {
    vi.useFakeTimers()
    const s = setup({ missing: true })
    const start = s.runtime.start(s.request())
    await vi.advanceTimersByTimeAsync(5000)
    await start
    expect(s.runtime.getSnapshot()).toMatchObject({ status: 'PAUSED', reasonCode: 'PREPARATION_TIMEOUT', target: null })
    expect(vi.getTimerCount()).toBe(0)
  })
  it('bounds an unresolved navigation adapter as well as DOM wait', async () => {
    vi.useFakeTimers()
    const s = setup({ screen: 'CONT-W03' })
    s.open.mockImplementation(() => new Promise(() => {}))
    const start = s.runtime.start(s.request())
    await vi.advanceTimersByTimeAsync(5000)
    await start
    expect(s.runtime.getSnapshot().reasonCode).toBe('PREPARATION_TIMEOUT')
  })
  it('rejects duplicate targets including hidden copies', async () => {
    const s = setup({ duplicate: true })
    await s.runtime.start(s.request())
    expect(s.runtime.getSnapshot().reasonCode).toBe('DUPLICATE_TARGET')
  })
  it('removes the highlight when a target disappears or becomes hidden', async () => {
    const s = setup()
    await s.runtime.start(s.request())
    s.input!.hidden = true
    s.runtime.contextChanged()
    expect(s.runtime.getSnapshot()).toMatchObject({ status: 'PAUSED', target: null, reasonCode: 'TARGET_CHANGED' })
  })
  it('opens only a registered panel preparer', async () => {
    const s = setup()
    s.unregister()
    s.input!.hidden = true
    const prepare = vi.fn(async () => {
      s.input!.hidden = false
    })
    s.targets.register({
      screenId: 'TRAN-W02',
      guideVersion: '1.0',
      targetId: 'payment.amount',
      root: () => s.root,
      prepare,
    })
    await s.runtime.start(s.request())
    expect(prepare).toHaveBeenCalledTimes(1)
    expect(s.runtime.getSnapshot().target).toBe(s.input)
  })
  it('rejects duplicate registrations and fails if registration is removed during a session', async () => {
    const s = setup()
    expect(() =>
      s.targets.register({ screenId: 'TRAN-W02', guideVersion: '1.0', targetId: 'payment.amount', root: () => s.root }),
    ).toThrow('DUPLICATE_TARGET_REGISTRATION')
    await s.runtime.start(s.request())
    s.unregister()
    s.runtime.contextChanged()
    expect(s.runtime.getSnapshot().status).toBe('PAUSED')
  })
  it('maps adapter failure to an error without retaining its potentially sensitive message', async () => {
    const s = setup()
    s.read.mockRejectedValueOnce(new Error('private business data'))
    await s.runtime.start(s.request())
    expect(s.runtime.getSnapshot()).toMatchObject({ status: 'ERROR', target: null, reasonCode: 'ADAPTER_FAILED' })
    expect(JSON.stringify(s.events)).not.toContain('private business data')
  })
})

describe('screen lifecycle binding', () => {
  it('stops on Escape and unsubscribes on unmount', async () => {
    const s = setup(),
      unsubscribe = vi.fn()
    const cleanup = bindGuideRuntime(s.runtime, {
      root: s.root,
      subscribeContext: () => unsubscribe,
      subscribeLogout: () => unsubscribe,
    })
    await s.runtime.start(s.request())
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(s.runtime.getSnapshot().status).toBe('IDLE')
    cleanup()
    cleanup()
    expect(unsubscribe).toHaveBeenCalledTimes(2)
  })
  it('pauses automatically when a registered DOM target is removed', async () => {
    const s = setup()
    const cleanup = bindGuideRuntime(s.runtime, {
      root: s.root,
      subscribeContext: () => () => {},
      subscribeLogout: () => () => {},
    })
    await s.runtime.start(s.request())
    s.input!.remove()
    await vi.waitFor(() => expect(s.runtime.getSnapshot().reasonCode).toBe('TARGET_CHANGED'))
    cleanup()
  })
  it('uses registered context and logout signals without persisting progress', async () => {
    const s = setup()
    let changed = () => {}
    let logout = () => {}
    const cleanup = bindGuideRuntime(s.runtime, {
      root: s.root,
      subscribeContext: (callback) => {
        changed = callback
        return () => {}
      },
      subscribeLogout: (callback) => {
        logout = callback
        return () => {}
      },
    })
    await s.runtime.start(s.request())
    s.updateContext({ clientContextRevision: '2' })
    changed()
    expect(s.runtime.getSnapshot().reasonCode).toBe('CONTEXT_CHANGED')
    logout()
    expect(s.runtime.getSnapshot().status).toBe('IDLE')
    cleanup()
  })
})
