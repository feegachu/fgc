import { useWalkthroughTarget } from './useWalkthroughTarget'
import { useState } from 'react'
import type { SettlementStep } from './state'
import { workflowWalkthrough } from './workflow-walkthrough'

export function useWorkflowWalkthrough(stage: SettlementStep | undefined, path: string, selectedException: boolean, correctionPending = false, enabled = true) {
  const steps = stage ? workflowWalkthrough(stage, path, selectedException, correctionPending) : []
  const key = `${stage}:${path}:${selectedException}:${correctionPending}`
  const [positions, setPositions] = useState<Record<string, number>>({})
  const index = Math.min(positions[key] ?? 0, Math.max(0, steps.length - 1))
  const step = steps[index]
  const observed = useWalkthroughTarget(enabled ? step?.selector : undefined, key, step?.input)
  const target = observed.target
  return {
    step, index, total: steps.length, target, status: observed.status, retry: observed.retry,
    ready: !!target && observed.valid,
    last: index === steps.length - 1,
    previous: () => setPositions(previous => ({ ...previous, [key]: Math.max(0, index - 1) })),
    next: () => setPositions(previous => ({ ...previous, [key]: Math.min(steps.length - 1, index + 1) })),
    message: !target ? observed.message : !observed.valid ? '입력 또는 검색 결과 선택을 완료한 뒤 다음 설명으로 진행하세요.' : '',
  }
}
