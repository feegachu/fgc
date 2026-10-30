import definitions from './workflow-guides.v1.json'
import features from './feature-targets.generated.json'
import registeredScreens from './screens.generated.json'

export interface WorkflowStep {
  stepId: string
  screenId: string
  targetId: string
  title: string
  instruction: string
  completionRuleId: 'USER_REVIEWED' | 'INPUT_VALID'
  next: { conditionId: 'USER_REVIEWED' | 'INPUT_VALID'; stepId: string }[]
}
export interface WorkflowGuide {
  workflowId: string
  guideVersion: string
  title: string
  description: string
  roles: string[]
  route: string
  steps: WorkflowStep[]
}
export const WORKFLOW_GUIDES = definitions as WorkflowGuide[]
const targetIds = new Set(features.map((feature) => feature.id))
for (const guide of WORKFLOW_GUIDES) {
  if (!registeredScreens.some((screen) => screen.path === guide.route) || guide.steps.length === 0)
    throw new Error('Unregistered workflow route')
  for (const step of guide.steps) {
    if (!targetIds.has(step.targetId) || !registeredScreens.some((screen) => screen.id === step.screenId))
      throw new Error('Unregistered workflow target')
    if (step.next.some((edge) => !guide.steps.some((next) => next.stepId === edge.stepId)))
      throw new Error('Unregistered workflow edge')
  }
}
export function findWorkflowGuide(id: string, version: string) {
  return WORKFLOW_GUIDES.find((guide) => guide.workflowId === id && guide.guideVersion === version) ?? null
}
