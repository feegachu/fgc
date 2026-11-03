import { afterEach, expect, it, vi } from 'vitest'
import { useWorkflowGuide } from './workflow-state'
import { closeSettlementGuide, openSettlementGuide, settlementEntry } from './settlement/entry'
import { connectPaymentGuideLaunch, launchPaymentGuide } from '../transaction/payment-guide-launch'

afterEach(() => {
  closeSettlementGuide()
  useWorkflowGuide.getState().clear()
  useWorkflowGuide.setState({ paymentActive: false })
})
it('keeps settlement and workflow/payment guides mutually exclusive at every launch entry', () => {
  const launch = vi.fn()
  const disconnect = connectPaymentGuideLaunch(launch)
  try {
    openSettlementGuide()
    expect(settlementEntry()).toBe(true)
    useWorkflowGuide.getState().start('contract.register')
    expect(useWorkflowGuide.getState().request).toBeNull()
    expect(launchPaymentGuide('payment.draft')).toBe(false)
    expect(launch).not.toHaveBeenCalled()
    closeSettlementGuide()
    useWorkflowGuide.getState().start('contract.register')
    openSettlementGuide()
    expect(settlementEntry()).toBe(false)
    useWorkflowGuide.getState().clear()
    expect(launchPaymentGuide('payment.draft')).toBe(true)
    openSettlementGuide()
    expect(settlementEntry()).toBe(false)
    useWorkflowGuide.getState().start('contract.register')
    expect(useWorkflowGuide.getState().request).toBeNull()
  } finally { disconnect() }
})
