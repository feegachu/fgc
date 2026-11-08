import { useEffect, useRef, useState } from 'react'

/** Observe server-backed presentation state; the guide never starts a run. */
export function useValidationPlaybackGuide(enabled: boolean, step: string | undefined, next: () => void) {
  const [state, setState] = useState({ status: '', error: '' })
  const advance = useRef(next)
  useEffect(() => { advance.current = next }, [next])
  useEffect(() => {
    if (!enabled) return
    const read = () => {
      const results = document.querySelector<HTMLElement>('[data-guide-validation-state]')
      const waitingForChecklist = results?.querySelector('[data-guide-checklist-ready="false"]')
      const status = results?.dataset.guideValidationState === 'ready' && waitingForChecklist ? 'running' : results?.dataset.guideValidationState ?? ''
      const error = results?.dataset.guideValidationError ?? ''
      setState(previous => previous.status === status && previous.error === error ? previous : { status, error })
    }
    const observer = new MutationObserver(read)
    observer.observe(document.getElementById('main-content') ?? document.body, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['data-guide-validation-state', 'data-guide-validation-error', 'data-guide-checklist-ready'] })
    read()
    return () => observer.disconnect()
  }, [enabled])
  useEffect(() => {
    if (!enabled || step !== 'validation.playback' || state.status !== 'ready') return
    // Let the final card's staggered reveal finish before bringing the coach back.
    const timer = window.setTimeout(() => advance.current(), 900)
    return () => window.clearTimeout(timer)
  }, [enabled, step, state.status])
  const watching = step === 'validation.start' || step === 'validation.playback'
  return {
    hidden: enabled && watching && (state.status === 'running' || step === 'validation.playback' && state.status === 'ready'),
    error: enabled && watching ? state.error : '',
  }
}
