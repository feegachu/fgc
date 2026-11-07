import { useLayoutEffect, useState } from 'react'

type Observation = { key: string; target: HTMLElement | null; valid: boolean; status: 'WAITING' | 'READY' | 'PAUSED'; message: string }
/** A paused explanation only resumes on explicit retry; no business action is executed. */
export function useWalkthroughTarget(selector: string | undefined, context: string, requiresInput = false) {
  const [attempt, setAttempt] = useState(0)
  const key = `${context}:${selector}:${attempt}`
  const [observed, setObserved] = useState<Observation>()
  useLayoutEffect(() => {
    if (!selector) return
    let paused = false
    let found = false
    const pause = (message: string) => {
      paused = true
      clearTimeout(timer)
      setObserved({ key, target: null, valid: false, status: 'PAUSED', message })
    }
    const update = () => {
      if (paused) return
      const nodes = document.getElementById('main-content')?.querySelectorAll<HTMLElement>(selector)
      if (nodes && nodes.length > 1) { pause('안내 대상이 중복되어 중단했습니다. 화면을 확인하고 다시 찾기를 눌러주세요.'); return }
      const candidate = nodes?.[0]
      const style = candidate ? getComputedStyle(candidate) : null
      const target = candidate && !candidate.closest('[hidden], [inert], [aria-hidden="true"]') && candidate.getClientRects().length && style?.display !== 'none' && style?.visibility !== 'hidden' ? candidate : null
      if (!target && found) { pause('안내 대상이 사라지거나 숨겨져 중단했습니다. 화면을 확인하고 다시 찾기를 눌러주세요.'); return }
      if (target) { found = true; clearTimeout(timer) }
      const input = target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | null
      const value = input?.getAttribute('role') === 'combobox' && input instanceof HTMLInputElement ? input.dataset.selectedValue : input?.value
      const valid = !!target && (!requiresInput || (!!value?.trim() && (!input?.validity || input.validity.valid)))
      const status = target ? 'READY' : 'WAITING'
      const message = target ? '' : '안내 대상을 찾고 있습니다. 5초 안에 준비되지 않으면 안내를 중단합니다.'
      setObserved(previous => previous?.key === key && previous.target === target && previous.valid === valid && previous.status === status ? previous : { key, target, valid, status, message })
    }
    const timer = setTimeout(() => pause('안내 대상이 5초 안에 준비되지 않아 중단했습니다. 화면을 확인하고 다시 찾기를 눌러주세요.'), 5000)
    update()
    const observer = new MutationObserver(update)
    observer.observe(document.body, { subtree: true, childList: true, attributes: true })
    document.addEventListener('input', update, true)
    document.addEventListener('change', update, true)
    window.addEventListener('resize', update)
    return () => {
      clearTimeout(timer)
      observer.disconnect()
      document.removeEventListener('input', update, true)
      document.removeEventListener('change', update, true)
      window.removeEventListener('resize', update)
    }
  }, [selector, key, requiresInput])
  const current = observed?.key === key ? observed : undefined
  return { target: current?.target ?? null, valid: current?.valid ?? false, status: current?.status ?? 'WAITING', message: current?.message ?? '', retry: () => setAttempt(value => value + 1) }
}
