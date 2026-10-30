import { validateCatalog } from './catalog'
import { GuideFailure, GuideTargets } from './targets'
import type {
  EntityRef,
  GuideContext,
  GuideDefinition,
  GuideEnvironment,
  GuideEvent,
  GuideSnapshot,
  GuideStart,
  GuideStep,
} from './types'

const entityTypes = new Set(['CONTRACT', 'PAYMENT', 'SCHEDULE', 'VALIDATION_RUN', 'RECONCILIATION_RUN', 'EXCEPTION'])
function entitiesKey(refs: readonly EntityRef[]): string {
  if (
    refs.some((ref) => !entityTypes.has(ref.type) || !Number.isSafeInteger(ref.id) || ref.id <= 0) ||
    new Set(refs.map((ref) => ref.type)).size !== refs.length
  )
    throw new GuideFailure('INVALID_ENTITY_REFS')
  return refs
    .map((ref) => `${ref.type}:${ref.id}`)
    .sort()
    .join('|')
}
function contextKey(context: GuideContext): string {
  return JSON.stringify([
    context.role,
    entitiesKey(context.entityRefs),
    context.clientContextRevision,
    context.serverStateVersion,
    context.dirty,
  ])
}
function freezeGuide(guide: GuideDefinition): GuideDefinition {
  const copy = structuredClone(guide)
  copy.steps.forEach((step) => {
    step.next.forEach(Object.freeze)
    Object.freeze(step.next)
    Object.freeze(step)
  })
  Object.freeze(copy.steps)
  Object.freeze(copy.roles)
  Object.freeze(copy.requiredEntities)
  Object.freeze(copy.keywords)
  Object.freeze(copy.exampleQuestions)
  return Object.freeze(copy)
}

/** Read/navigation orchestration only. No business-write callback, persistence or AI client. */
export class GuideRuntime<State> {
  private readonly guides: readonly GuideDefinition[]
  private readonly targets: GuideTargets
  private readonly environment: GuideEnvironment<State>
  private readonly timeoutMs: number
  private readonly listeners = new Set<(event: GuideEvent) => void>()
  private readonly observers = new Set<() => void>()
  private snapshot: GuideSnapshot = Object.freeze({
    status: 'IDLE',
    workflowId: null,
    step: null,
    target: null,
    reasonCode: null,
  })
  private guide: GuideDefinition | null = null
  private context: GuideContext | null = null
  private history: string[] = []
  private sequence = 0
  private operation: AbortController | null = null
  private returnFocus: HTMLElement | null = null
  private disposed = false

  constructor(
    guides: readonly GuideDefinition[],
    targets: GuideTargets,
    environment: GuideEnvironment<State>,
    timeoutMs = 5000,
  ) {
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('INVALID_GUIDE_TIMEOUT')
    this.targets = targets
    this.timeoutMs = timeoutMs
    this.environment = { ...environment, conditions: Object.freeze({ ...environment.conditions }) }
    this.guides = Object.freeze(guides.map(freezeGuide))
    validateCatalog(this.guides, this.environment.conditions, targets)
  }
  getSnapshot = (): GuideSnapshot => this.snapshot
  subscribe(listener: (event: GuideEvent) => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }
  observe(listener: () => void): () => void {
    this.observers.add(listener)
    return () => {
      this.observers.delete(listener)
    }
  }
  private set(patch: Partial<GuideSnapshot>) {
    this.snapshot = Object.freeze({ ...this.snapshot, ...patch })
    this.observers.forEach((listener) => listener())
  }
  private emit(kind: GuideEvent['kind'], reasonCode: string | null = null) {
    if (!this.guide) return
    const event: GuideEvent = Object.freeze({
      kind,
      workflowId: this.guide.workflowId,
      stepId: this.snapshot.step?.stepId ?? null,
      reasonCode,
    })
    this.listeners.forEach((listener) => listener(event))
  }
  private cancel() {
    ++this.sequence
    this.operation?.abort()
    this.operation = null
  }
  private active() {
    return !!this.guide && !!this.context && !['IDLE', 'COMPLETED', 'ERROR'].includes(this.snapshot.status)
  }
  private capture(): GuideContext {
    return structuredClone(this.environment.readContext())
  }
  private assertContext(expected: GuideContext, screen?: string) {
    const current = this.capture()
    if (!this.guide?.roles.includes(current.role)) throw new GuideFailure('PERMISSION_DENIED')
    if (contextKey(expected) !== contextKey(current)) throw new GuideFailure('CONTEXT_CHANGED')
    if (screen && current.screenId !== screen) throw new GuideFailure('SCREEN_CHANGED')
  }

  /** Deadline includes panel/navigation and API reads. Late adapters cannot publish stale state. */
  private async run(action: (signal: AbortSignal, assertCurrent: () => void) => Promise<void>) {
    if (this.disposed) return
    this.cancel()
    const id = this.sequence,
      controller = new AbortController()
    this.operation = controller
    let timer: ReturnType<typeof setTimeout> | undefined
    let abortListener: (() => void) | undefined
    const assertCurrent = () => {
      if (id !== this.sequence || controller.signal.aborted) throw new GuideFailure('CANCELLED')
    }
    try {
      await Promise.race([
        action(controller.signal, assertCurrent),
        new Promise<never>((_, reject) => {
          abortListener = () => reject(new GuideFailure('CANCELLED'))
          controller.signal.addEventListener('abort', abortListener, { once: true })
          timer = setTimeout(() => reject(new GuideFailure('PREPARATION_TIMEOUT')), this.timeoutMs)
        }),
      ])
    } catch (error) {
      if (id !== this.sequence || this.disposed) return
      const reasonCode = error instanceof GuideFailure ? error.reasonCode : 'ADAPTER_FAILED'
      this.set({ status: error instanceof GuideFailure ? 'PAUSED' : 'ERROR', target: null, reasonCode })
      this.emit(error instanceof GuideFailure ? 'PAUSED' : 'FAILED', reasonCode)
    } finally {
      if (timer) clearTimeout(timer)
      if (abortListener) controller.signal.removeEventListener('abort', abortListener)
      controller.abort()
      if (this.operation === controller) this.operation = null
    }
  }

  async start(request: GuideStart): Promise<void> {
    if (this.disposed) return
    if (this.active()) {
      this.pause('GUIDE_ALREADY_ACTIVE')
      return
    }
    this.guide =
      this.guides.find(
        (guide) => guide.workflowId === request.workflowId && guide.guideVersion === request.guideVersion,
      ) ?? null
    this.context = null
    this.history = []
    this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    this.set({ status: 'PREPARING', workflowId: request.workflowId, step: null, target: null, reasonCode: null })
    await this.run(async (signal, assertCurrent) => {
      if (!this.guide) throw new GuideFailure('GUIDE_VERSION_OR_ID_UNKNOWN')
      const step = this.guide.steps.find((entry) => entry.stepId === request.stepId)
      if (!step) throw new GuideFailure('STEP_UNKNOWN')
      const context = this.capture()
      if (
        entitiesKey(request.entityRefs) !== entitiesKey(context.entityRefs) ||
        request.clientContextRevision !== context.clientContextRevision ||
        request.serverStateVersion !== context.serverStateVersion
      )
        throw new GuideFailure('CONTEXT_CHANGED')
      if (this.guide.requiredEntities.some((type) => !context.entityRefs.some((ref) => ref.type === type)))
        throw new GuideFailure('REQUIRED_ENTITY_MISSING')
      this.context = context
      this.assertContext(context)
      this.emit('STARTED')
      await this.enter(step, signal, assertCurrent)
    })
  }

  private async enter(step: GuideStep, signal: AbortSignal, assertCurrent: () => void) {
    const context = this.context!
    this.assertContext(context)
    this.set({ status: 'PREPARING', step, target: null, reasonCode: null })
    if (this.environment.readContext().screenId !== step.screenId) {
      if (context.dirty) {
        const approved = await this.environment.confirmLeave()
        assertCurrent()
        this.assertContext(context)
        if (!approved) throw new GuideFailure('UNSAVED_NAVIGATION_CANCELLED')
        this.environment.preserveInput?.()
      }
      await this.environment.openScreen(step.screenId, signal)
      assertCurrent()
      this.assertContext(context, step.screenId)
    }
    await this.targets.prepare(step.screenId, this.guide!.guideVersion, step.targetId, signal)
    assertCurrent()
    this.assertContext(context, step.screenId)
    const target = await this.targets.wait(step.screenId, this.guide!.guideVersion, step.targetId, signal)
    assertCurrent()
    this.assertContext(context, step.screenId)
    const state = await this.environment.readBusinessState(structuredClone(context), signal)
    assertCurrent()
    this.assertContext(context, step.screenId)
    const entry = this.environment.conditions[step.entryConditionId](this.capture(), state)
    if (entry !== 'SATISFIED') {
      this.set({ status: entry, target: null, reasonCode: 'ENTRY_NOT_READY' })
      return
    }
    if (this.targets.find(step.screenId, this.guide!.guideVersion, step.targetId) !== target)
      throw new GuideFailure('TARGET_CHANGED')
    const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
    target.scrollIntoView?.({ block: 'center', behavior: reduced ? 'instant' : 'smooth' })
    this.set({ status: 'SHOWING', target, reasonCode: null })
    this.emit('STEP_CHANGED')
  }

  async next(): Promise<void> {
    if (
      !this.active() ||
      !this.snapshot.step ||
      ['PAUSED', 'PREPARING'].includes(this.snapshot.status) ||
      this.operation
    )
      return
    await this.run(async (signal, assertCurrent) => {
      const step = this.snapshot.step!,
        expected = this.context!
      this.assertContext(expected, step.screenId)
      const target = this.targets.find(step.screenId, this.guide!.guideVersion, step.targetId)
      if (!target || (this.snapshot.target && target !== this.snapshot.target)) throw new GuideFailure('TARGET_CHANGED')
      this.set({ status: 'WAITING_RESULT', reasonCode: null })
      const state = await this.environment.readBusinessState(structuredClone(expected), signal)
      assertCurrent()
      this.assertContext(expected, step.screenId)
      if (this.targets.find(step.screenId, this.guide!.guideVersion, step.targetId) !== target)
        throw new GuideFailure('TARGET_CHANGED')
      const entry = this.environment.conditions[step.entryConditionId](this.capture(), state)
      const completion =
        entry === 'SATISFIED' ? this.environment.conditions[step.completionConditionId](this.capture(), state) : entry
      if (completion !== 'SATISFIED') {
        this.set({ status: completion, reasonCode: 'CONDITION_NOT_READY' })
        return
      }
      const branches = step.next.filter(
        (next) => this.environment.conditions[next.conditionId](this.capture(), state) === 'SATISFIED',
      )
      if (branches.length > 1) throw new GuideFailure('AMBIGUOUS_BRANCH')
      if (!branches.length && step.next.length) {
        this.set({ status: 'WAITING_RESULT', reasonCode: 'BRANCH_NOT_READY' })
        return
      }
      if (!branches.length) {
        this.set({ status: 'COMPLETED', target: null, reasonCode: null })
        this.emit('FINISHED')
        this.restoreFocus()
        return
      }
      this.history.push(step.stepId)
      await this.enter(
        this.guide!.steps.find((entry) => entry.stepId === branches[0].stepId)!,
        signal,
        assertCurrent,
      )
    })
  }
  async previous(): Promise<void> {
    if (!this.active() || !this.history.length || this.snapshot.status === 'PREPARING' || this.operation) return
    const previous = this.history.pop()!
    await this.run((signal, assertCurrent) =>
      this.enter(
        this.guide!.steps.find((step) => step.stepId === previous)!,
        signal,
        assertCurrent,
      ),
    )
  }
  pause(reasonCode = 'USER_PAUSED'): void {
    if (!this.active()) return
    this.cancel()
    this.set({ status: 'PAUSED', target: null, reasonCode })
    this.emit('PAUSED', reasonCode)
  }
  /** Called by screen context subscriptions/layout changes; stale highlights disappear immediately. */
  contextChanged(): void {
    if (!this.active() || !this.context || this.snapshot.status === 'PAUSED') return
    try {
      this.assertContext(this.context, this.snapshot.status === 'PREPARING' ? undefined : this.snapshot.step?.screenId)
      if (
        this.snapshot.target &&
        this.snapshot.step &&
        this.targets.find(this.snapshot.step.screenId, this.guide!.guideVersion, this.snapshot.step.targetId) !==
          this.snapshot.target
      )
        throw new GuideFailure('TARGET_CHANGED')
    } catch (error) {
      this.pause(error instanceof GuideFailure ? error.reasonCode : 'CONTEXT_UNAVAILABLE')
    }
  }
  /** Explicit resume refreshes revision; changing entities requires a new guide start. */
  async resume(): Promise<void> {
    if (!this.active() || this.snapshot.status !== 'PAUSED' || !this.snapshot.step) return
    await this.run(async (signal, assertCurrent) => {
      const current = this.capture()
      if (entitiesKey(current.entityRefs) !== entitiesKey(this.context!.entityRefs))
        throw new GuideFailure('ENTITY_CHANGED_RESTART_REQUIRED')
      if (contextKey(current) !== contextKey(this.context!)) this.history = []
      this.context = current
      await this.enter(this.snapshot.step!, signal, assertCurrent)
    })
  }
  private restoreFocus() {
    if (this.returnFocus?.isConnected) this.returnFocus.focus()
    this.returnFocus = null
  }
  stop(): void {
    this.cancel()
    this.emit('STOPPED')
    this.guide = null
    this.context = null
    this.history = []
    this.set({ status: 'IDLE', workflowId: null, step: null, target: null, reasonCode: null })
    this.restoreFocus()
  }
  dispose(): void {
    this.stop()
    this.disposed = true
    this.listeners.clear()
    this.observers.clear()
  }
}
