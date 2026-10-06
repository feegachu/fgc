import { errorText } from '../format'

type ErrorListener = (message: string, error: unknown) => void
const listeners = new Set<ErrorListener>()
// #403 Toast subscribes here. Hooks can opt out of a global notification via meta.errorToast=false.
export function subscribeApiErrors(listener: ErrorListener) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
export function notifyApiError(error: unknown) {
  const message = errorText(error instanceof Error ? error : null)
  listeners.forEach((listener) => listener(message, error))
}
