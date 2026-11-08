import { useEffect, useRef, useState } from 'react'
import { useToastStore } from '../../stores/toasts'
import type { ToastProgress } from '../../stores/toasts'

/** Keep one notification per run; a manually dismissed notification stays dismissed. */
export function useRunProgressToast(active: boolean, message: string, progress: ToastProgress) {
  const toastId = useRef<number | null>(null)
  const [painted, setPainted] = useState(false)
  if (!active && painted) setPainted(false)
  useEffect(() => {
    if (!active) return
    let second = 0
    // Allow the results layout to commit and paint before adding its progress toast.
    const first = requestAnimationFrame(() => { second = requestAnimationFrame(() => setPainted(true)) })
    return () => { cancelAnimationFrame(first); cancelAnimationFrame(second) }
  }, [active])
  const { steps, completed, running, buffering } = progress
  useEffect(() => {
    const store = useToastStore.getState()
    if (!active) {
      if (toastId.current !== null) store.close(toastId.current)
      toastId.current = null
      return
    }
    if (!painted) return
    const nextProgress = { steps, completed, running, buffering }
    if (toastId.current === null) toastId.current = store.show(message, 'loading', 0, nextProgress)
    else store.updateMessage(toastId.current, message, nextProgress)
  }, [active, painted, message, steps, completed, running, buffering])

  useEffect(() => () => {
    if (toastId.current !== null) useToastStore.getState().close(toastId.current)
    toastId.current = null
  }, [])
}
