import { useEffect, useState } from 'react'
import type { SettlementStep } from './state'
import { workflowWalkthrough } from './workflow-walkthrough'

export function useWorkflowWalkthrough(stage: SettlementStep | undefined, path: string, selectedException: boolean, correctionPending = false) {
  const steps = stage ? workflowWalkthrough(stage, path, selectedException, correctionPending) : []
  const key = `${stage}:${path}:${selectedException}:${correctionPending}`
  const [positions, setPositions] = useState<Record<string, number>>({})
  const index = Math.min(positions[key] ?? 0, Math.max(0, steps.length - 1))
  const step = steps[index]
  const [observed, setObserved] = useState<{ selector?: string; target: HTMLElement | null; valid: boolean }>({ target: null, valid: false })
  const selector = step?.selector
  const requiresInput = step?.input
  useEffect(() => {
    if (!selector) return
    let frame = 0
    const update = () => {
      const root = document.getElementById('main-content')
      const nodes = root?.querySelectorAll<HTMLElement>(selector)
      const candidate = nodes?.length === 1 ? nodes[0] : null
      const style = candidate ? getComputedStyle(candidate) : null
      const target = candidate && !candidate.closest('[hidden], [inert], [aria-hidden="true"]') && candidate.getClientRects().length && style?.display !== 'none' && style?.visibility !== 'hidden' ? candidate : null
      const input = target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | null
      const value = input?.getAttribute('role') === 'combobox' && input instanceof HTMLInputElement
        ? input.dataset.selectedValue : input?.value
      const valid = !!target && (!requiresInput || (!!value?.trim() && (!input?.validity || input.validity.valid)))
      setObserved(previous => previous.selector === selector && previous.target === target && previous.valid === valid ? previous : { selector, target, valid })
    }
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(update) }
    const root = document.getElementById('main-content')
    update()
    const mutation = new MutationObserver(schedule)
    if (root) mutation.observe(root, { subtree: true, childList: true, attributes: true })
    document.addEventListener('input', schedule, true)
    document.addEventListener('change', schedule, true)
    window.addEventListener('resize', schedule)
    return () => {
      mutation.disconnect()
      cancelAnimationFrame(frame)
      document.removeEventListener('input', schedule, true)
      document.removeEventListener('change', schedule, true)
      window.removeEventListener('resize', schedule)
    }
  }, [selector, requiresInput, key])
  const target = observed.selector === selector ? observed.target : null
  return {
    step, index, total: steps.length, target,
    ready: !!target && observed.valid,
    last: index === steps.length - 1,
    previous: () => setPositions(previous => ({ ...previous, [key]: Math.max(0, index - 1) })),
    next: () => setPositions(previous => ({ ...previous, [key]: Math.min(steps.length - 1, index + 1) })),
    message: !target ? '안내 대상이 아직 보이지 않습니다. 해당 결과를 조회하거나 항목을 선택하세요. 대상을 건너뛰어 완료 처리하지 않습니다.' : !observed.valid ? '입력 또는 검색 결과 선택을 완료한 뒤 다음 설명으로 진행하세요.' : '',
  }
}
