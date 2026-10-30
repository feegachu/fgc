import { GUIDE_CATALOG } from '../catalog'
import type { GuideDefinition } from './types'

const readers = ['SYSTEM_ADMIN', 'GA_ADMIN', 'SETTLEMENT', 'COMPLIANCE']
const processors = ['SYSTEM_ADMIN', 'SETTLEMENT']

/** Reuse #736's text and identifiers without pretending its proposed wire session exists. */
export const PAYMENT_GUIDES: readonly GuideDefinition[] = GUIDE_CATALOG.map((guide) => ({
  workflowId: guide.workflowId,
  guideVersion: guide.guideVersion,
  title: guide.title,
  keywords: [guide.workflowId],
  exampleQuestions: guide.utterances,
  roles: guide.capability === 'READ' ? readers : processors,
  requiredEntities: guide.requiredSlots.flatMap((slot) =>
    slot === 'contractId' ? ['CONTRACT' as const] : slot === 'paymentId' ? ['PAYMENT' as const] : [],
  ),
  steps: guide.steps.map((step, index) => ({
    stepId: step.stepId,
    screenId: step.screenId,
    targetId: step.targetId,
    instruction: step.instruction,
    entryConditionId: step.entryRuleId,
    completionConditionId: step.completionRuleId,
    next: guide.steps[index + 1] ? [{ conditionId: 'ALWAYS', stepId: guide.steps[index + 1].stepId }] : [],
  })),
}))

export const GUIDE_ID = /^[a-z][a-z0-9._-]{0,79}$/

/** Fail at registration rather than evaluating arbitrary condition expressions at runtime. */
export function validateCatalog(
  guides: readonly GuideDefinition[],
  conditions: Readonly<Record<string, unknown>>,
  targets: { has(screenId: string, guideVersion: string, targetId: string): boolean },
): void {
  const keys = new Set<string>()
  for (const guide of guides) {
    const key = `${guide.workflowId}:${guide.guideVersion}`
    if (
      !GUIDE_ID.test(guide.workflowId) ||
      !guide.guideVersion ||
      guide.guideVersion.length > 40 ||
      keys.has(key) ||
      !guide.steps.length ||
      !guide.roles.length
    ) {
      throw new Error('INVALID_GUIDE_CATALOG')
    }
    keys.add(key)
    const stepIds = new Set(guide.steps.map((step) => step.stepId))
    if (stepIds.size !== guide.steps.length) throw new Error('DUPLICATE_GUIDE_STEP')
    for (const step of guide.steps) {
      if (
        !GUIDE_ID.test(step.stepId) ||
        !GUIDE_ID.test(step.targetId) ||
        !targets.has(step.screenId, guide.guideVersion, step.targetId)
      )
        throw new Error('UNREGISTERED_GUIDE_TARGET')
      for (const id of [
        step.entryConditionId,
        step.completionConditionId,
        ...step.next.map((next) => next.conditionId),
      ]) {
        if (!Object.hasOwn(conditions, id) || typeof conditions[id] !== 'function')
          throw new Error('UNREGISTERED_GUIDE_CONDITION')
      }
      if (step.next.some((next) => !stepIds.has(next.stepId))) throw new Error('UNREGISTERED_GUIDE_STEP')
    }
  }
}
