import { useWorkflowGuide } from '../assistant/workflow-state'

let launch: ((workflowId: string, stepId?: string) => void) | null = null

export function connectPaymentGuideLaunch(listener: (workflowId: string, stepId?: string) => void) {
  launch = listener
  return () => { if (launch === listener) launch = null }
}

export function launchPaymentGuide(workflowId: string, stepId?: string): boolean {
  if (!launch || useWorkflowGuide.getState().settlementActive) return false
  useWorkflowGuide.setState({ paymentActive: true })
  if (stepId === undefined) launch(workflowId)
  else launch(workflowId, stepId)
  return true
}
