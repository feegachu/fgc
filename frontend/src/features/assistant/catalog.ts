import definitions from './payment-guides.v1.json'
import type { GuideBranchContext, LocalGuideDefinition, LocalGuideStep } from './contracts'
function freeze<T>(value: T): Readonly<T> {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freeze)
    Object.freeze(value)
  }
  return value
}
// Shared source: Gradle copies this exact JSON to ai/pico for the server catalog.
export const GUIDE_CATALOG: readonly Readonly<LocalGuideDefinition>[] = freeze(definitions as unknown as LocalGuideDefinition[])

/** Only registered IDs are resolved. Routes, selectors and executable conditions never come from model text. */
export function findGuide(workflowId: string, guideVersion: string): Readonly<LocalGuideDefinition> | null {
  return (
    GUIDE_CATALOG.find(
      (guide) => guide.enabled && guide.workflowId === workflowId && guide.guideVersion === guideVersion,
    ) ?? null
  )
}
export function findStep(workflowId: string, guideVersion: string, stepId: string): Readonly<LocalGuideStep> | null {
  return findGuide(workflowId, guideVersion)?.steps.find((entry) => entry.stepId === stepId) ?? null
}

/** Select only a registered edge after the caller has checked this step's completion. */
export function selectGuideTransition(step: Readonly<LocalGuideStep>, context: GuideBranchContext): TargetId | null {
  if (step.next.length === 0) return null
  const currentDraftSaved = Boolean(context.paymentId) && context.saved && context.formValid
  const matches = step.next.filter(({ conditionId }) =>
    conditionId === 'CURRENT_DRAFT_ALREADY_SAVED'
      ? currentDraftSaved
      : conditionId === 'DRAFT_SAVE_REQUIRED'
        ? !currentDraftSaved
        : conditionId === step.completionRuleId,
  )
  if (matches.length !== 1) throw new Error('Guide transition must resolve to exactly one registered step')
  return matches[0].stepId
}
