/** FUN-087 v3.0 in-memory execution contract. Not assistant.v1's proposed server session. */
export type EntityType = 'CONTRACT' | 'PAYMENT' | 'SCHEDULE' | 'VALIDATION_RUN' | 'RECONCILIATION_RUN' | 'EXCEPTION'
export interface EntityRef {
  type: EntityType
  id: number
}
export type GuideRuntimeStatus =
  'IDLE' | 'PREPARING' | 'SHOWING' | 'WAITING_INPUT' | 'WAITING_RESULT' | 'PAUSED' | 'COMPLETED' | 'ERROR'
export interface GuideStep {
  stepId: string
  screenId: string
  targetId: string
  instruction: string
  entryConditionId: string
  completionConditionId: string
  next: readonly { conditionId: string; stepId: string }[]
}
export interface GuideDefinition {
  workflowId: string
  guideVersion: string
  title: string
  keywords: readonly string[]
  exampleQuestions: readonly string[]
  roles: readonly string[]
  requiredEntities: readonly EntityType[]
  steps: readonly GuideStep[]
}
export interface GuideStart {
  workflowId: string
  stepId: string
  guideVersion: string
  entityRefs: readonly EntityRef[]
  clientContextRevision: string
  serverStateVersion: string | null
}
export interface GuideContext {
  screenId: string
  role: string
  entityRefs: readonly EntityRef[]
  clientContextRevision: string
  serverStateVersion: string | null
  dirty: boolean
}
export interface GuideEvent {
  kind: 'STARTED' | 'STEP_CHANGED' | 'PAUSED' | 'FINISHED' | 'STOPPED' | 'FAILED'
  workflowId: string
  stepId: string | null
  reasonCode: string | null
}
export interface GuideSnapshot {
  status: GuideRuntimeStatus
  workflowId: string | null
  step: GuideStep | null
  target: HTMLElement | null
  reasonCode: string | null
}
export type ConditionResult = 'SATISFIED' | 'WAITING_INPUT' | 'WAITING_RESULT'
export type GuideCondition<State> = (context: Readonly<GuideContext>, state: Readonly<State>) => ConditionResult

/** Authenticated, read-only adapters. UI conditions never replace the business API's authorization. */
export interface GuideEnvironment<State> {
  readContext(): GuideContext
  readBusinessState(context: GuideContext, signal: AbortSignal): Promise<State>
  conditions: Readonly<Record<string, GuideCondition<State>>>
  /** Only registered navigation/tab actions; must not click a business button or perform a write. */
  openScreen(screenId: string, signal: AbortSignal): Promise<void>
  confirmLeave(): Promise<boolean>
  /** Optional memory-only form adapter, used only after the user agrees to navigate. */
  preserveInput?(): void
}
