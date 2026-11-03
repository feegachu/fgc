import { useWorkflowGuide } from '../workflow-state'
import { toast } from '../../../stores/toasts'

// A shell-local launch signal. Progress and business data are never persisted.
let requested = false
const listeners = new Set<() => void>()
export const subscribeSettlementEntry = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
export const settlementEntry = () => requested
export function openSettlementGuide() {
  const { request, active, paymentActive } = useWorkflowGuide.getState()
  if (request || active || paymentActive) {
    toast('진행 중인 안내를 닫은 뒤 전체 업무 가이드를 시작해주세요.', 'warning')
    return
  }
  useWorkflowGuide.setState({ settlementActive: true })
  requested = true
  listeners.forEach((listener) => listener())
}
export function closeSettlementGuide() {
  requested = false
  useWorkflowGuide.setState({ settlementActive: false })
  listeners.forEach((listener) => listener())
}
