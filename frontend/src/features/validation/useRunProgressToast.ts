import { useEffect, useRef } from 'react'
import { useToastStore } from '../../stores/toasts'
import type { ToastProgress } from '../../stores/toasts'

/** Keep one notification per run; a manually dismissed notification stays dismissed. */
export function useRunProgressToast(active: boolean, message: string, progress: ToastProgress) {
  const toastId = useRef<number | null>(null)
  const { steps, completed, running, buffering } = progress
  useEffect(() => {
    const store = useToastStore.getState()
    if (!active) {
      if (toastId.current !== null) store.close(toastId.current)
      toastId.current = null
      return
    }
    const nextProgress = { steps, completed, running, buffering }
    if (toastId.current === null) toastId.current = store.show(message, 'loading', 0, nextProgress)
    else store.updateMessage(toastId.current, message, nextProgress)
  }, [active, message, steps, completed, running, buffering])

  useEffect(() => () => {
    if (toastId.current !== null) useToastStore.getState().close(toastId.current)
    toastId.current = null
  }, [])
}
