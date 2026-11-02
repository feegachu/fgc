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
  requested = true
  listeners.forEach((listener) => listener())
}
export function closeSettlementGuide() {
  requested = false
  listeners.forEach((listener) => listener())
}
