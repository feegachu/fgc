import { create } from 'zustand'
export type ToastTone = 'info' | 'success' | 'warning' | 'error' | 'loading'
export interface ToastMessage { id: number; message: string; tone: ToastTone; duration: number }
let sequence = 0
export const useToastStore = create<{ messages: ToastMessage[]; show: (message: string, tone?: ToastTone, duration?: number) => number; close: (id: number) => void }>((set) => ({
  messages: [],
  show: (message, tone = 'info', duration = tone === 'error' || tone === 'loading' ? 0 : 5000) => {
    const id = ++sequence
    set((state) => ({ messages: [...state.messages, { id, message, tone, duration }] }))
    return id
  },
  close: (id) => set((state) => ({ messages: state.messages.filter((item) => item.id !== id) })),
}))
export const toast = (message: string, tone?: ToastTone, duration?: number) => useToastStore.getState().show(message, tone, duration)
