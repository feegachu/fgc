import { create } from 'zustand'
import { toast } from '../../stores/toasts'

export const useWorkflowGuide = create<{
  request: { id: string; stepId?: string; sequence: number } | null
  active: boolean
  paymentActive: boolean
  settlementActive: boolean
  guideContext: { workflowId: string; stepId: string } | null
  start: (id: string, stepId?: string) => void
  clear: () => void
}>((set) => ({
  request: null,
  active: false,
  paymentActive: false,
  settlementActive: false,
  guideContext: null,
  start: (id, stepId) => set((state) => {
    if (state.settlementActive || state.paymentActive) {
      toast('진행 중인 안내를 닫은 뒤 새 업무 안내를 시작해주세요.', 'warning')
      return state
    }
    return { request: { id, ...(stepId ? { stepId } : {}), sequence: (state.request?.sequence ?? 0) + 1 }, active: true }
  }),
  clear: () => set({ request: null, active: false, guideContext: null }),
}))
