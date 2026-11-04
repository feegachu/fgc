import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { initialForm, newRow } from '../../transaction/form-model'
import type { PaymentGuideInput } from '../../transaction/payment-guide'
import { usePaymentWalkthrough } from './usePaymentWalkthrough'
const state = vi.hoisted(() => ({ input: null as PaymentGuideInput | null, revision: 0, listeners: new Set<() => void>() }))
vi.mock('../../transaction/payment-guide', () => ({
  getPaymentGuideObserver: () => observer,
}))
const observer = {
  subscribe: (fn: () => void) => { state.listeners.add(fn); return () => { state.listeners.delete(fn) } },
  getViewRevision: () => state.revision,
  readWalkthroughInput: () => state.input,
}
beforeEach(() => {
  state.input = { role: 'SETTLEMENT', canProcess: true, paymentId: null, ready: true, valid: false, dirty: true,
    form: initialForm('2026-07'), rows: [newRow(1, '')] }
  state.revision = 0
  document.body.innerHTML = '<main id="main-content"><input id="stage"><input id="settlementMonth"><input id="recipient"></main>'
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
})
afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })
it('keeps the current explanation on input changes and unlocks only the selected recipient', async () => {
  const { result } = renderHook(() => usePaymentWalkthrough(true, '2026-07', 'GA_TO_FC'))
  await waitFor(() => expect(result.current.ready).toBe(true))
  act(() => result.current.next())
  await waitFor(() => expect(result.current.step?.id).toBe('month'))
  act(() => result.current.next())
  await waitFor(() => expect(result.current.ready).toBe(false))
  act(() => { state.input!.form.agentId = '1'; state.revision++; state.listeners.forEach(fn => fn()) })
  await waitFor(() => expect(result.current.ready).toBe(true))
  expect(result.current.step?.id).toBe('recipient')
  expect(result.current.saved).toBe(false)
})
it('blocks missing and duplicate targets instead of advancing', async () => {
  const { result } = renderHook(() => usePaymentWalkthrough(true, '2026-07', 'GA_TO_FC'))
  await waitFor(() => expect(result.current.ready).toBe(true))
  act(() => document.querySelector('main')!.insertAdjacentHTML('beforeend', '<input id="stage">'))
  await waitFor(() => expect(result.current.target).toBeNull())
  expect(result.current.ready).toBe(false)
})
it('blocks a changed month and preserves the explanation when temporarily disabled', async () => {
  const { result, rerender } = renderHook(({ enabled }) => usePaymentWalkthrough(enabled, '2026-07', 'GA_TO_FC'), { initialProps: { enabled: true } })
  await waitFor(() => expect(result.current.ready).toBe(true))
  act(() => result.current.next())
  rerender({ enabled: false })
  rerender({ enabled: true })
  expect(result.current.step?.id).toBe('month')
  act(() => { state.input!.form.settlementMonth = '2026-08'; state.revision++; state.listeners.forEach(fn => fn()) })
  await waitFor(() => expect(result.current.ready).toBe(false))
})
