import { create } from 'zustand'
export type ToastTone = 'info' | 'success' | 'warning' | 'error' | 'loading'
export interface ToastProgress {
  steps: readonly string[]
  completed: number
  running: number | null
  buffering: boolean
}
export interface ToastMessage {
  id: number
  message: string
  tone: ToastTone
  duration: number
  progress?: ToastProgress
}
let sequence = 0
export const useToastStore = create<{
  messages: ToastMessage[]
  show: (message: string, tone?: ToastTone, duration?: number, progress?: ToastProgress) => number
  close: (id: number) => void
  updateMessage: (id: number, message: string, progress?: ToastProgress) => void
}>((set) => ({
  messages: [],
  show: (message, tone = 'info', duration = tone === 'error' || tone === 'loading' ? 0 : 5000, progress) => {
    const id = ++sequence
    set((state) => ({ messages: [...state.messages, { id, message, tone, duration, progress }] }))
    return id
  },
  close: (id) => set((state) => ({ messages: state.messages.filter((item) => item.id !== id) })),
  updateMessage: (id, message, progress) => set((state) => ({
    messages: state.messages.map((item) => item.id === id ? { ...item, message, progress } : item),
  })),
}))
export const toast = (message: string, tone?: ToastTone, duration?: number) =>
  useToastStore.getState().show(message, tone, duration)
