import { create } from 'zustand'

export const useWorkflowGuide = create<{
  request: { id: string; stepId?: string; sequence: number } | null
  active: boolean
  paymentActive: boolean
  guideContext: { workflowId: string; stepId: string } | null
  start: (id: string, stepId?: string) => void
  clear: () => void
}>((set) => ({
  request: null,
  active: false,
  paymentActive: false,
  guideContext: null,
  start: (id, stepId) => set((state) => ({ request: { id, ...(stepId ? { stepId } : {}), sequence: (state.request?.sequence ?? 0) + 1 }, active: true })),
  clear: () => set({ request: null, active: false, guideContext: null }),
}))
