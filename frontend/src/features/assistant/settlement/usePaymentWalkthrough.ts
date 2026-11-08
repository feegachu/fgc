import { useWalkthroughTarget } from './useWalkthroughTarget'
import { useRef, useSyncExternalStore } from 'react'
import { getPaymentGuideObserver } from '../../transaction/payment-guide'
import { paymentWalkthrough, type PaymentWalkthroughStep } from './payment-walkthrough'
import { useGuideSequence } from '../guide/useGuideSequence'

const identify = (step: PaymentWalkthroughStep) => step.id

const noopSubscribe = () => () => {}
const zero = () => 0
export function usePaymentWalkthrough(enabled: boolean, month: string, stage?: string, contractId?: number) {
  const observer = getPaymentGuideObserver()
  useSyncExternalStore(observer?.subscribe ?? noopSubscribe, observer?.getViewRevision ?? zero)
  const reviewed = useRef(new Set<string>())
  const input = enabled ? observer?.readWalkthroughInput() ?? null : null
  const steps = paymentWalkthrough(input, contractId)
  const sequence = useGuideSequence(steps, `${month}:${stage}:${contractId}`, identify)
  const { index, step } = sequence
  const observed = useWalkthroughTarget(enabled ? step?.selector : undefined, `${enabled}:${month}:${stage}:${contractId}`)
  const target = observed.target
  const scopeMatches = input?.form.settlementMonth === month && input?.form.paymentStage === stage
  const ready = !!target && !!input?.ready && !!step?.ready && scopeMatches
  return {
    target: enabled ? target : null, step, index, total: steps.length, ready, status: observed.status, retry: observed.retry,
    saved: !!input?.paymentId && !input.dirty,
    previous: sequence.previous,
    next: () => {
      // Revisit newly inserted/conditional rows before leaving the input walkthrough.
      reviewed.current.add(step.id)
      const missing = steps.findIndex((entry, i) => i < index && (!entry.ready || !reviewed.current.has(entry.id)))
      sequence.go(missing >= 0 ? missing : index + 1)
    },
    message: !scopeMatches ? '지급단계와 정산월을 전체 가이드의 선택과 맞춰주세요.' : !target ? observed.message : !step?.ready ? '해당 입력을 완료하면 다음 안내로 넘어갈 수 있습니다.' : '',
  }
}
