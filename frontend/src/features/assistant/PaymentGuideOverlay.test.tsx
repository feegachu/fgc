import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { PaymentGuideOverlay } from './PaymentGuide'
import { useGuideRuntime } from './guide-runtime'
afterEach(() => {
  cleanup()
  useGuideRuntime.setState({ session: null, observation: null })
})
function startPaused() {
  useGuideRuntime
    .getState()
    .publish({
      owner: 'qa',
      scopeKey: 'payment',
      revision: 0,
      canProcess: true,
      ready: true,
      amountValid: true,
      attributionsValid: true,
      formValid: true,
      saved: false,
      busy: false,
      precheck: 'NONE',
      modalOpen: false,
    })
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
