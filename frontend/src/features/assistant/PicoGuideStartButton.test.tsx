import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import { PicoGuideStartButton } from './PicoGuideStartButton'
import { useGuideRuntime } from './guide-runtime'
import type { PaymentObservation } from './guide-runtime'
const observation: PaymentObservation = {
  owner: 'page',
  paymentId: '77',
  scopeKey: 'scope',
  revision: 0,
  canProcess: true,
  ready: true,
  saved: true,
  busy: false,
  amountValid: true,
  attributionsValid: true,
  formValid: true,
  precheck: 'NONE',
  modalOpen: false,
}
const token = 'a'.repeat(43)
function response(action: string, body: Record<string, unknown>) {
  return {
    data: {
      status: action === 'prepare' ? 'PREPARED' : 'STARTED',
      workflowId: body.workflowId,
      stepId: 'payment.scope',
      guideVersion: '1.0',
      paymentId: body.paymentId,
      clientContextRevision: body.clientContextRevision,
      expiresAt: '2099-01-01T00:00:00Z',
      prepareToken: action === 'prepare' ? token : null,
    },
    error: null,
    requestId: 'test',
  }
}
beforeEach(() => {
  useGuideRuntime.setState({ session: null, observation })
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  useGuideRuntime.setState({ session: null, observation: null })
})
function mount(canProcess = true, workflowId = 'payment.draft') {
  return render(<PicoGuideStartButton workflowId={workflowId} contextRevision="r1" canProcess={canProcess} />)
}
it('waits for consent and revalidation before starting only the first highlight without business writes', async () => {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockImplementation((async (path, options) =>
      response(
        path.endsWith('prepare') ? 'prepare' : 'start',
        options!.body as Record<string, unknown>,
      )) as typeof apiClient.request)
  mount()
  expect(request).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: /이 업무 안내 시작/ }))
  await waitFor(() =>
    expect(useGuideRuntime.getState().session).toMatchObject({
      workflowId: 'payment.draft',
      paymentId: '77',
      index: 0,
      status: 'PREPARING',
    }),
  )
  expect(request.mock.calls.map(([path]) => path)).toEqual([
    '/api/v1/ai/pico/guides/prepare',
    '/api/v1/ai/pico/guides/start',
  ])
  expect(request.mock.calls[1][1]?.body).toEqual({
    workflowId: 'payment.draft',
    guideVersion: '1.0',
    paymentId: '77',
    clientContextRevision: 'r1',
    prepareToken: token,
  })
  expect(useGuideRuntime.getState().observation?.precheck).toBe('NONE')
})
it.each([{ saved: false }, { ready: false }, { busy: true }, { paymentId: null }])(
  'disables start and explains invalid screen context %j',
  (changed) => {
    useGuideRuntime.setState({ observation: { ...observation, ...changed } })
    mount()
    expect(screen.getByRole('button')).toBeDisabled()
    expect(screen.getByRole('status')).toBeInTheDocument()
  },
)
it('does not offer processing to a read role but can offer a read guide', () => {
  const view = mount(false)
  expect(screen.getByRole('button')).toBeDisabled()
  expect(screen.getByRole('status')).toBeInTheDocument()
  view.rerender(<PicoGuideStartButton workflowId="payment.amount.review" contextRevision="r1" canProcess={false} />)
  expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeInTheDocument()
})
it('keeps the form and stops on a stale server response instead of starting locally', async () => {
  vi.spyOn(apiClient, 'request').mockRejectedValue(
    new ApiError({ code: 'FGC-IMPL-GUIDE_STATE_STALE', message: 'stale' }, 'test', 409),
  )
  mount()
  await userEvent.click(screen.getByRole('button'))
  expect(await screen.findByRole('status')).toHaveTextContent('현재 화면에서 안내 시작을 다시 선택')
  expect(useGuideRuntime.getState().session).toBeNull()
  expect(useGuideRuntime.getState().observation).toEqual(observation)
})
it('rejects a mismatched prepared target without calling start', async () => {
  const request = vi.spyOn(apiClient, 'request').mockImplementation((async (_path, options) =>
    response('prepare', {
      ...(options!.body as Record<string, unknown>),
      paymentId: '88',
    })) as typeof apiClient.request)
  mount()
  await userEvent.click(screen.getByRole('button'))
  await screen.findByRole('status')
  expect(request).toHaveBeenCalledTimes(1)
  expect(useGuideRuntime.getState().session).toBeNull()
})
it('aborts and discards late start replies after a payment change, then permits a new explicit attempt', async () => {
  let finish!: (value: ReturnType<typeof response>) => void
  const request = vi.spyOn(apiClient, 'request').mockImplementation((async (path, options) => {
    if (path.endsWith('start') && (options!.body as { paymentId: string }).paymentId === '77')
      return new Promise<ReturnType<typeof response>>((resolve) => {
        finish = resolve
      })
    return response(path.endsWith('prepare') ? 'prepare' : 'start', options!.body as Record<string, unknown>)
  }) as typeof apiClient.request)
  mount()
  await userEvent.click(screen.getByRole('button'))
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2))
  const oldOptions = request.mock.calls[1][1]!
  act(() => useGuideRuntime.getState().publish({ ...observation, paymentId: '88' }))
  expect(oldOptions.signal?.aborted).toBe(true)
  await act(async () => finish(response('start', oldOptions.body as Record<string, unknown>)))
  expect(useGuideRuntime.getState().session).toBeNull()
  await userEvent.click(screen.getByRole('button'))
  await waitFor(() => expect(useGuideRuntime.getState().session?.paymentId).toBe('88'))
})
it('locks duplicate clicks while preparation is pending', () => {
  const request = vi.spyOn(apiClient, 'request').mockReturnValue(new Promise(() => {}))
  mount()
  const button = screen.getByRole('button')
  fireEvent.click(button)
  fireEvent.click(button)
  expect(request).toHaveBeenCalledTimes(1)
  expect(useGuideRuntime.getState().session).toBeNull()
})
