import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { ValidationRestartPanel } from './ValidationRestartPanel'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
const clients: QueryClient[] = []
afterEach(() => {
  vi.restoreAllMocks()
  clients.splice(0).forEach((c) => c.clear())
})
function setup(canProcess = true, fail = false, rows: unknown[] = []) {
  const accepted = vi.fn()
  const requests: unknown[] = []
  vi.spyOn(apiClient, 'request').mockImplementation((async (
    path: string,
    options?: { body?: unknown; idempotencyKey?: string },
  ) => {
    if (path.endsWith('/restartability'))
      return {
        data: { allowed: true, expectedStateVersion: 'failed-101', lastFailedBatchExecutionId: 101, failedStep: 6 },
        requestId: null,
      }
    if (path.endsWith('/retries')) return { data: rows, requestId: null }
    requests.push(options)
    if (fail && requests.length === 1) throw new ApiError(null, 'request', 500)
    return { data: { retryId: 9, status: 'ACCEPTED' }, requestId: null }
  }) as typeof apiClient.request)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <ValidationRestartPanel id="7" status="FAILED" waiting={false} canProcess={canProcess} onAccepted={accepted} />
    </QueryClientProvider>,
  )
  return { actions: userEvent.setup(), accepted, requests }
}
it('FGC-FUN-045 requires a reason and sends the observed execution with one idempotency key', async () => {
  const { actions, requests, accepted } = setup()
  const button = await screen.findByRole('button', { name: '실패 지점부터 재시작' })
  await waitFor(() => expect(button).toBeEnabled())
  await actions.click(button)
  expect(screen.getByRole('button', { name: '재시작 요청' })).toBeDisabled()
  await actions.type(screen.getByRole('textbox', { name: '재시작 사유' }), '원인 수정')
  await actions.click(screen.getByRole('button', { name: '재시작 요청' }))
  await waitFor(() => expect(accepted).toHaveBeenCalledTimes(1))
  expect(requests[0]).toMatchObject({
    body: { expectedBatchExecutionId: 101, expectedStateVersion: 'failed-101', reason: '원인 수정' },
    idempotencyKey: expect.any(String),
  })
})
it('FGC-FUN-045 preserves the same request after an ambiguous response failure', async () => {
  const { actions, requests } = setup(true, true)
  const button = await screen.findByRole('button', { name: '실패 지점부터 재시작' })
  await waitFor(() => expect(button).toBeEnabled())
  await actions.click(button)
  await actions.type(screen.getByRole('textbox', { name: '재시작 사유' }), '원인 수정')
  await actions.click(screen.getByRole('button', { name: '재시작 요청' }))
  await screen.findByRole('alert')
  expect(screen.getByRole('textbox', { name: '재시작 사유' })).toHaveValue('원인 수정')
  await actions.click(screen.getByRole('button', { name: '재시작 요청' }))
  await waitFor(() => expect(requests).toHaveLength(2))
  expect(requests[1]).toEqual(requests[0])
})
it('FGC-FUN-045 keeps read-only users from issuing restart requests', async () => {
  const { requests } = setup(false)
  expect(await screen.findByRole('button', { name: '실패 지점부터 재시작' })).toBeDisabled()
  expect(requests).toHaveLength(0)
})

it('shows the original target step and does not call a failed unstarted retry waiting', async () => {
  setup(true, false, [
    {
      retryId: 9,
      failedStep: 6,
      previousBatchExecutionId: 101,
      batchExecutionId: null,
      requestedBy: 1,
      reason: '기동 실패',
      state: 'FAILED',
      requestedAt: '2026-11-04T00:00:00Z',
      errorCode: 'LAUNCH_TIMEOUT',
    },
  ])
  expect(await screen.findByText('6단계')).toBeInTheDocument()
  expect(screen.getByText('기동 안 됨')).toBeInTheDocument()
  expect(screen.queryByText('기동 대기')).not.toBeInTheDocument()
})
