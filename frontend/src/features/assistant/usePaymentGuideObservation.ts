import { useEffect, useId } from 'react'
import { useGuideRuntime } from './guide-runtime'
import type { PaymentObservation } from './guide-runtime'

export function usePaymentGuideObservation(value: Omit<PaymentObservation, 'owner'>) {
  const owner = useId()
  const encoded = JSON.stringify(value)
  useEffect(() => {
    if (import.meta.env.VITE_ASSISTANT_DEMO !== 'true') return
    useGuideRuntime.getState().publish({ ...JSON.parse(encoded), owner } as PaymentObservation)
  }, [encoded, owner])
  useEffect(() => () => useGuideRuntime.getState().detach(owner), [owner])
}
