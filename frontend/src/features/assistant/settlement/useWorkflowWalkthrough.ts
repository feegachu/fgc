import { useWalkthroughTarget } from './useWalkthroughTarget'
import { useLayoutEffect, useMemo, useState } from 'react'
import type { SettlementStep } from './state'
import { workflowWalkthrough, type WorkflowExplanation } from './workflow-walkthrough'
import { useGuideSequence } from '../guide/useGuideSequence'

const identify = (step: WorkflowExplanation, index: number) => `${index}:${step.id}`

export function useWorkflowWalkthrough(stage: SettlementStep | undefined, path: string, selectedException: boolean, correctionPending = false, observing = true, reuseContractDetail = true, batchPayment = false) {
  const catalogue = useMemo(() => stage ? workflowWalkthrough(stage, path, selectedException, correctionPending, reuseContractDetail, batchPayment) : [], [stage, path, selectedException, correctionPending, reuseContractDetail, batchPayment])
  const key = String(batchPayment) + ':' + `${stage}:${path}:${selectedException}:${correctionPending}:${reuseContractDetail}`
  const detail = reuseContractDetail && (stage === 'contract.create' && /^\/contracts\/\d+$/.test(path) || stage === 'payment.draft' && path === '/transactions' || stage === 'schedule.review' && path === '/schedules')
  const [available, setAvailable] = useState<{ key: string; steps: WorkflowExplanation[] }>()
  useLayoutEffect(() => {
    if (!detail || !observing || available?.key === key) return
    // Like the page help, explain only the currently visible tab. Wait for the
    // contract to load before taking the snapshot; don't include hidden panels.
    const capture = () => {
      const visible = catalogue.filter(entry => {
        if (entry.waitForTarget) return true
        if (entry.scope === 'shell') return true
        const root = document.getElementById('main-content')
        const nodes = [...(root?.querySelectorAll<HTMLElement>(entry.selector) ?? [])].filter(node => {
          const style = getComputedStyle(node)
          return !node.closest('[hidden], [inert], [aria-hidden="true"]') && node.getClientRects().length > 0
            && style.display !== 'none' && style.visibility !== 'hidden'
        })
        return nodes.length === 1 || !!entry.firstMatch && nodes.length > 0
      })
      if (!visible.length || visible[0] !== catalogue[0]) return
      setAvailable({ key, steps: visible })
      observer.disconnect()
    }
    const observer = new MutationObserver(capture)
    observer.observe(document.body, { childList: true, subtree: true, attributes: true })
    capture()
    return () => observer.disconnect()
  }, [detail, observing, available?.key, key, catalogue])
  const steps = detail ? available?.key === key ? available.steps : [] : catalogue
  const sequence = useGuideSequence(steps, key, identify)
  const { step } = sequence
  const observed = useWalkthroughTarget(observing ? step?.selector : undefined, key, step?.input, step?.requiresSelectedTab, step?.scope, step?.firstMatch)
  const target = observed.target
  return {
    ...sequence, target, status: observed.status, retry: observed.retry,
    ready: !!target && observed.valid,
    message: !target ? observed.message : !observed.valid ? '입력 또는 검색 결과 선택을 완료한 뒤 다음 설명으로 진행하세요.' : '',
  }
}
