import { useEffect, useState } from 'react'

export const RESTART_ATTEMPT_WAIT_MS = 120_000

/** A UI wait limit, never evidence that a server attempt succeeded or failed. */
export function useRestartAttemptDeadline(enabled: boolean, generation: number) {
  const [expiredGeneration, setExpiredGeneration] = useState<number | null>(null)
  useEffect(() => {
    if (!enabled) return
    const timer = window.setTimeout(() => setExpiredGeneration(generation), RESTART_ATTEMPT_WAIT_MS)
    return () => window.clearTimeout(timer)
  }, [enabled, generation])
  return enabled && expiredGeneration === generation
}
