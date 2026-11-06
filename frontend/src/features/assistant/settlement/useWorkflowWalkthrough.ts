import { useWalkthroughTarget } from './useWalkthroughTarget'
import { useMemo } from 'react'
import type { SettlementStep } from './state'
import { workflowWalkthrough, type WorkflowExplanation } from './workflow-walkthrough'
import { useGuideSequence } from '../guide/useGuideSequence'

const identify = (step: WorkflowExplanation, index: number) => `${index}:${step.id}`

export function useWorkflowWalkthrough(stage: SettlementStep | undefined, path: string, selectedException: boolean, correctionPending = false, observing = true) {
  const steps = useMemo(() => stage ? workflowWalkthrough(stage, path, selectedException, correctionPending) : [], [stage, path, selectedException, correctionPending])
  const key = `${stage}:${path}:${selectedException}:${correctionPending}`
  const sequence = useGuideSequence(steps, key, identify)
  const { step } = sequence
  const observed = useWalkthroughTarget(observing ? step?.selector : undefined, key, step?.input)
  const target = observed.target
  return {
    ...sequence, target, status: observed.status, retry: observed.retry,
    ready: !!target && observed.valid,
    message: !target ? observed.message : !observed.valid ? '입력 또는 검색 결과 선택을 완료한 뒤 다음 설명으로 진행하세요.' : '',
  }
}
