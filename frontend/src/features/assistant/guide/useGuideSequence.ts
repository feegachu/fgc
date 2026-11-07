import { useCallback, useEffect, useRef, useState } from 'react'
import type { ScreenHelpStep } from './ScreenHelp'

/** One sequence engine for a page tour and the workflow that calls those tours. */
export function useGuideSequence<T>(steps: readonly T[], key: string, identify: (step: T, index: number) => string) {
  const [positions, setPositions] = useState<Record<string, string>>({})
  const index = Math.max(0, steps.findIndex((step, index) => identify(step, index) === positions[key]))
  const go = useCallback((position: number) => {
    const destination = Math.max(0, Math.min(steps.length - 1, position))
    const step = steps[destination]
    if (step) setPositions(previous => ({ ...previous, [key]: identify(step, destination) }))
  }, [steps, key, identify])
  return { index, step: steps[index], total: steps.length, last: index === steps.length - 1,
    go, next: () => go(index + 1), previous: () => go(index - 1) }
}

type Behavior = Pick<ScreenHelpStep, 'onEnter' | 'openControl' | 'advanceOnSelection' | 'advanceOnEvent'>
/** Presentation and selection only; never clicks save/execute/confirm controls. */
export function useGuideStep(step: Behavior | undefined, target: HTMLElement | null, next: () => void, enabled: boolean) {
  const advance = useRef(next)
  useEffect(() => { advance.current = next }, [next])
  useEffect(() => {
    if (!enabled || !target || !step?.advanceOnEvent) return
    let timer = 0
    const completed = () => { clearTimeout(timer); timer = window.setTimeout(() => advance.current(), 350) }
    target.addEventListener(step.advanceOnEvent, completed)
    return () => { clearTimeout(timer); target.removeEventListener(step.advanceOnEvent!, completed) }
  }, [enabled, target, step?.advanceOnEvent])
  useEffect(() => {
    if (enabled) return step?.onEnter?.()
  }, [step, enabled])
  useEffect(() => {
    if (!enabled || !target || !step?.openControl) return
    const timer = window.setTimeout(() => {
      if (!target.isConnected || target.matches(':disabled')) return
      target.focus({ preventScroll: true })
      target.dispatchEvent(new Event('fgc-guide-open'))
    }, window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 0 : 750)
    return () => {
      clearTimeout(timer)
      target.dispatchEvent(new Event('fgc-guide-close'))
      if (document.activeElement === target) target.blur()
    }
  }, [target, step?.openControl, enabled])
  useEffect(() => {
    if (!enabled || !target || !step?.advanceOnSelection) return
    const field = target as HTMLInputElement | HTMLSelectElement
    const value = () => field.getAttribute('role') === 'combobox' ? field.dataset.selectedValue ?? '' : field.value
    const initial = value()
    let timer = 0
    const selected = (event?: Event | MutationRecord[]) => {
      clearTimeout(timer)
      const explicit = event instanceof Event && event.type === 'fgc-guide-selected'
      if (!value() || !explicit && value() === initial || field.matches(':disabled') || field.validity?.valid === false) return
      timer = window.setTimeout(() => advance.current(), 250)
    }
    const observer = new MutationObserver(selected)
    observer.observe(target, { attributes: true, attributeFilter: ['data-selected-value'] })
    target.addEventListener('change', selected)
    target.addEventListener('fgc-guide-selected', selected)
    return () => { clearTimeout(timer); observer.disconnect(); target.removeEventListener('change', selected); target.removeEventListener('fgc-guide-selected', selected) }
  }, [target, step?.advanceOnSelection, enabled])
}
