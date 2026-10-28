import { beforeEach, expect, it, vi } from 'vitest'
import { completion, resolvePaymentTarget, subscribeGuideEvents, useGuideRuntime } from './guide-runtime'
import type { PaymentObservation } from './guide-runtime'
const observation: PaymentObservation = {
  owner: 'page-1',
  scopeKey: 'scope-1',
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
}
const runtime = () => useGuideRuntime.getState()
function show() {
  runtime().targetReady()
}
function next() {
  show()
  runtime().next()
}
beforeEach(() => {
  useGuideRuntime.setState({ session: null, observation: null })
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})
it('requires a ready payment screen and denies processing guides without capability', () => {
  expect(runtime().start('payment.draft')).toBe(false)
  runtime().publish({ ...observation, ready: false })
  expect(runtime().start('payment.draft')).toBe(false)
  runtime().publish({ ...observation, canProcess: false })
  expect(runtime().start('payment.draft')).toBe(false)
  expect(runtime().start('payment.amount.review')).toBe(true)
  expect(runtime().start('unknown' as 'payment.draft')).toBe(false)
})
it('moves only after explicit acknowledgement, and cannot skip invalid input or a failed save', () => {
  const fetch = vi.spyOn(globalThis, 'fetch')
  runtime().publish({ ...observation, formValid: false, amountValid: false })
  runtime().start('payment.draft')
  runtime().next()
  expect(runtime().session?.index).toBe(0) // target not yet ready
  next()
  expect(runtime().session?.index).toBe(1)
  next()
  expect(runtime().session?.status).toBe('WAITING_INPUT')
  expect(runtime().session?.index).toBe(1)
  runtime().publish(observation)
  next()
  next()
  next()
  expect(runtime().session?.index).toBe(4)
  next()
  expect(runtime().session?.status).toBe('WAITING_INPUT')
  runtime().publish({ ...observation, busy: true })
  next()
  expect(runtime().session?.status).toBe('WAITING_RESULT')
  runtime().publish({ ...observation, saved: true })
  next()
  expect(runtime().session?.status).toBe('COMPLETED')
  expect(fetch).not.toHaveBeenCalled()
})
it('invalidates all acknowledgements on edit and pauses on changed scope/page/permission', () => {
  runtime().publish(observation)
  runtime().start('payment.draft')
  next()
  next()
  runtime().publish({ ...observation, revision: 1 })
  expect(runtime().session).toMatchObject({ index: 0, acknowledgements: [], revision: 1 })
  runtime().publish({ ...observation, revision: 2, scopeKey: 'scope-2' })
  expect(runtime().session).toMatchObject({ status: 'PAUSED', restartRequired: true })
  runtime().resume()
  expect(runtime().session?.status).toBe('PAUSED')
  runtime().start('payment.draft')
  expect(runtime().session?.scopeKey).toBe('scope-2')
  runtime().publish({ ...observation, scopeKey: 'scope-2', canProcess: false })
  expect(runtime().session?.status).toBe('PAUSED')
})
it('pause/resume/previous/stop keep observations intact and detached pages require restart', () => {
  runtime().publish(observation)
  runtime().start('payment.draft')
  next()
  runtime().pause()
  runtime().next()
  expect(runtime().session?.index).toBe(1)
  runtime().resume()
  expect(runtime().session?.status).toBe('PREPARING')
  show()
  runtime().previous()
  expect(runtime().session?.index).toBe(0)
  runtime().detach('wrong-page')
  expect(runtime().observation).not.toBeNull()
  runtime().detach('page-1')
  expect(runtime().session?.restartRequired).toBe(true)
  runtime().stop()
  expect(runtime().session?.status).toBe('CANCELLED')
  expect(runtime().observation).toBeNull()
})
it('does not accept confirmable without current server token, nor mark confirmed from click', () => {
  runtime().publish({ ...observation, saved: true })
  runtime().start('payment.confirm')
  next()
  next()
  show()
  runtime().publish({ ...observation, saved: true, precheck: 'REJECTED' })
  runtime().next()
  expect(runtime().session).toMatchObject({ index: 2, status: 'BLOCKED' })
  expect(runtime().session?.reason).toContain('차단')
  runtime().publish({ ...observation, saved: true, precheck: 'PASSED_WITHOUT_TOKEN' })
  runtime().next()
  expect(runtime().session).toMatchObject({ index: 2, status: 'BLOCKED' })
  expect(runtime().session?.reason).toContain('증표')
  const session = runtime().session!
  expect(completion({ ...session, index: 4 }, observation).ok).toBe(false)
})
it('reset clears session and restarts get distinct local generations', () => {
  runtime().publish(observation)
  runtime().start('payment.draft')
  const first = runtime().session!.generation
  runtime().start('payment.draft')
  expect(runtime().session!.generation).toBeGreaterThan(first)
  runtime().reset()
  expect(runtime().session).toBeNull()
})
function targets(html: string) {
  document.body.innerHTML = `<main data-guide-screen="TRAN-W02" data-guide-version="1.0">${html}</main>`
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([
    { width: 30, height: 30 },
  ] as unknown as DOMRectList)
}
it('resolves exactly one visible registered target and rejects duplicates, wrong versions and screen mismatch', () => {
  targets('<input data-guide-target="payment.amount" />')
  expect(resolvePaymentTarget(document, 'payment.amount').element).not.toBeNull()
  document.body.querySelector('main')!.innerHTML += '<input data-guide-target="payment.amount" />'
  expect(resolvePaymentTarget(document, 'payment.amount').error).toContain('중복')
  targets('<input data-guide-target="payment.amount" hidden />')
  expect(resolvePaymentTarget(document, 'payment.amount').element).toBeNull()
  document.querySelector('main')!.setAttribute('data-guide-version', '2.0')
  expect(resolvePaymentTarget(document, 'payment.amount').error).toContain('버전')
  targets('')
  document.body.innerHTML += '<input data-guide-target="payment.amount" />'
  expect(resolvePaymentTarget(document, 'payment.amount').element).toBeNull()
  expect(resolvePaymentTarget(document, 'body' as 'payment.amount').error).toContain('등록되지')
})
it('uses the registered modal target only when visible; never falls back to main', () => {
  targets('')
  expect(resolvePaymentTarget(document, 'payment.confirm.dialog').element).toBeNull()
  document.body.innerHTML +=
    '<div role="dialog" aria-modal="true"><div data-guide-target="payment.confirm.dialog"></div></div>'
  expect(resolvePaymentTarget(document, 'payment.confirm.dialog').element).not.toBeNull()
  document.querySelector('[role="dialog"]')!.setAttribute('hidden', '')
  expect(resolvePaymentTarget(document, 'payment.confirm.dialog').element).toBeNull()
})

it('allows amount review before later fields and pauses covered steps until modal closes', () => {
  runtime().publish({ ...observation, formValid: false, attributionsValid: false })
  runtime().start('payment.draft')
  next()
  next()
  expect(runtime().session?.index).toBe(2)
  runtime().publish({ ...observation, modalOpen: true })
  expect(runtime().session?.status).toBe('PAUSED')
  runtime().resume()
  expect(runtime().session?.status).toBe('PAUSED')
  runtime().publish(observation)
  runtime().resume()
  expect(runtime().session?.status).toBe('PREPARING')
})

it('publishes minimal execution events, reports system failure and unsubscribes cleanly', () => {
  const listener = vi.fn(),
    unsubscribe = subscribeGuideEvents(listener)
  runtime().publish(observation)
  runtime().start('payment.draft')
  next()
  runtime().pause()
  runtime().resume()
  runtime().fail()
  expect(runtime().session?.status).toBe('ERROR')
  runtime().resume()
  expect(runtime().session?.status).toBe('PREPARING')
  expect(listener.mock.calls.map(([event]) => event.kind)).toEqual(['STARTED', 'STEP_CHANGED', 'PAUSED', 'FAILED'])
  expect(Object.keys(listener.mock.calls[0][0])).toEqual(['kind', 'workflowId', 'stepId', 'reasonCode'])
  unsubscribe()
  runtime().stop()
  expect(listener).toHaveBeenCalledTimes(4)
})
