let launch: ((workflowId: string) => void) | null = null

export function connectPaymentGuideLaunch(listener: (workflowId: string) => void) {
  launch = listener
  return () => { if (launch === listener) launch = null }
}

export function launchPaymentGuide(workflowId: string): boolean {
  if (!launch) return false
  launch(workflowId)
  return true
}
