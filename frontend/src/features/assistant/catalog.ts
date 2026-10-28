import definitions from './payment-guides.v1.json'
import type { GuideDefinition, GuideStep } from './contracts'
function freeze<T>(value: T): Readonly<T> {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freeze)
    Object.freeze(value)
  }
  return value
}
// Shared source: Gradle copies this exact JSON to ai/pico for the server catalog.
export const GUIDE_CATALOG: readonly Readonly<GuideDefinition>[] = freeze(definitions as unknown as GuideDefinition[])

/** Only registered IDs are resolved. Routes, selectors and executable conditions never come from model text. */
export function findGuide(workflowId: string, guideVersion: string): Readonly<GuideDefinition> | null {
  return (
    GUIDE_CATALOG.find(
      (guide) => guide.enabled && guide.workflowId === workflowId && guide.guideVersion === guideVersion,
    ) ?? null
  )
}
export function findStep(workflowId: string, guideVersion: string, stepId: string): Readonly<GuideStep> | null {
  return findGuide(workflowId, guideVersion)?.steps.find((entry) => entry.stepId === stepId) ?? null
}
