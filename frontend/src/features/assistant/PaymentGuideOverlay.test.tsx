import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { PaymentGuideOverlay } from './PaymentGuide'
import { useGuideRuntime } from './guide-runtime'
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  useGuideRuntime.setState({ session: null, observation: null })
})
function startPaused() {
  const observation = {
    owner: 'qa',
    scopeKey: 'payment',
    revision: 0,
    paymentId: null,
    canProcess: true,
    ready: true,
    amountValid: true,
    attributionsValid: true,
    formValid: true,
    saved: false,
    busy: false,
    precheck: 'NONE' as const,
    modalOpen: false,
  }
  useGuideRuntime.getState().publish(observation)
  useGuideRuntime.getState().start('payment.draft')
  useGuideRuntime.getState().pause()
}
it('Escape terminates the guide and restores the launch button focus', () => {
  render(
    <>
      <button>도움말 시작</button>
      <PaymentGuideOverlay />
    </>,
  )
  const launcher = screen.getByRole('button', { name: '도움말 시작' })
  launcher.focus()
  act(startPaused)
  expect(useGuideRuntime.getState().session).not.toBeNull()
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(useGuideRuntime.getState().session).toBeNull()
  expect(launcher).toHaveFocus()
})
it('Escape belongs to an open business modal before the guide', () => {
  render(
    <>
      <button>도움말 시작</button>
      <div role="dialog" aria-modal="true">
        업무 확인
      </div>
      <PaymentGuideOverlay />
    </>,
  )
  act(startPaused)
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(useGuideRuntime.getState().session).not.toBeNull()
})

it('keeps the spotlight node across steps and hides stale geometry until the next measurement', () => {
  const frames = new Map<number, FrameRequestCallback>()
  let frameId = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.set(++frameId, callback); return frameId })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => { frames.delete(id) })
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([new DOMRect(0, 0, 100, 100)] as unknown as DOMRectList)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    return this.dataset.guideTarget === 'payment.scope' ? new DOMRect(20, 30, 100, 80) : new DOMRect(300, 250, 140, 60)
  })
  const flush = () => act(() => {
    const callbacks = [...frames.values()]
    frames.clear()
    callbacks.forEach((callback) => callback(0))
  })
  render(<><main data-guide-screen="TRAN-W02" data-guide-version="1.0"><div data-guide-target="payment.scope" /><input data-guide-target="payment.amount" /></main><PaymentGuideOverlay /></>)
  act(() => { startPaused(); useGuideRuntime.getState().resume() })
  flush()
  const spotlight = document.querySelector('.payment-guide-spotlight') as HTMLElement
  expect(spotlight).toBeVisible()
  expect(spotlight.style.left).toBe('10px')
  fireEvent.click(screen.getByRole('button', { name: '확인 후 다음' }))
  expect(document.querySelector('.payment-guide-spotlight')).toBe(spotlight)
  expect(spotlight).not.toBeVisible()
  flush()
  expect(document.querySelector('.payment-guide-spotlight')).toBe(spotlight)
  expect(spotlight).toBeVisible()
  expect(spotlight.style.left).toBe('290px')
  act(() => useGuideRuntime.getState().publish({ ...useGuideRuntime.getState().observation!, revision: 1 }))
  expect(spotlight).not.toBeVisible()
  document.querySelector('[data-guide-target="payment.scope"]')!.remove()
  flush()
  expect(spotlight).not.toBeVisible()
  act(() => useGuideRuntime.getState().pause())
  expect(document.querySelector('.payment-guide-spotlight')).toBeNull()
})
