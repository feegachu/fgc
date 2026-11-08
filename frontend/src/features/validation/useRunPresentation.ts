import { useEffect, useState } from 'react'
import type { StepState } from './steps'

/** Only pace confirmed work; never advance a running server step on a timer. */
export function useRunPresentation(status: string | undefined, current: number, generation: number, enabled: boolean, waiting: boolean) {
  const confirmed = waiting || status === 'CREATED' ? 0 : status === 'FINALIZED' ? 10
    : status === 'FAILED' ? Math.max(0, current - 1) : Math.min(10, current)
  const [frame, setFrame] = useState(() => ({ generation, completed: status === 'RUNNING' ? 0 : confirmed }))
  const completed = enabled ? Math.min(confirmed, frame.generation === generation ? frame.completed : 0) : confirmed
  const active = enabled && !waiting && (completed < confirmed || (status === 'RUNNING' && completed < 8))
  const [arrival, setArrival] = useState<{ generation: number; completed: number } | null>(null)
  const buffering = active && arrival?.generation === generation && arrival.completed === completed
  useEffect(() => {
    if (!active) return
    const reduced = (window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false)
    // Match the line's 300ms delay + 950ms travel, then visibly buffer.
    const timer = window.setTimeout(() => setArrival({ generation, completed }), reduced || completed === 0 ? 0 : 1250)
    return () => window.clearTimeout(timer)
  }, [active, generation, completed])
  useEffect(() => {
    if (!enabled || !buffering || completed >= confirmed) return
    const reduced = (window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false)
    const timer = window.setTimeout(() => setFrame({ generation, completed: reduced ? confirmed : completed + 1 }), reduced ? 0 : 1200)
    return () => window.clearTimeout(timer)
  }, [enabled, buffering, completed, confirmed, generation])
  const state = (step: number): StepState => {
    if (!waiting && status === 'FAILED' && step === current) return 'failed'
    if (step <= completed) return 'done'
    if (step === completed + 1 && (completed < confirmed || (status === 'RUNNING' && step <= 8))) return 'running'
    return 'pending'
  }
  return { completed, state, buffering, catchingUp: completed < confirmed }
}
