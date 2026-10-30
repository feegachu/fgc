import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import { AssistantDemoMount, PicoAssistant } from './PicoAssistant'
import { useGuideRuntime } from './guide-runtime'
import type { ShellUser } from '../../app/shell/Sidebar'
const reply = {
  decision: 'ANSWER_ONLY',
  message: '초안 저장은 지급 확정과 구분됩니다.',
  guideVersion: '1.0',
  contextRevision: 'REV',
  evidenceRefs: [
    { chunkId: 'payment.draft:overview', workflowId: 'payment.draft', stepId: null, title: '지급 초안 저장' },
  ],
  validatedGuide: null,
  source: 'AI',
  fallbackReason: null,
}
const envelope = (data: unknown) => ({ data, error: null, requestId: 'QA' })
const user: ShellUser = {
  loginId: 'reader',
  userName: '담당자',
  roleCode: 'SETTLEMENT',
  canProcess: true,
  canViewAuditLog: false,
}
beforeEach(() => {
  vi.stubEnv('VITE_ASSISTANT_AI', 'true')
  vi.stubEnv('VITE_ASSISTANT_DEMO', '')
  useGuideRuntime.setState({ session: null, observation: null })
})
afterEach(() => {
  cleanup()
  useGuideRuntime.setState({ session: null, observation: null })
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
})
async function open() {
  await userEvent.click(screen.getByRole('button', { name: '피코 업무 비서 열기' }))
}
async function send() {
  await userEvent.type(screen.getByRole('textbox', { name: '피코에게 질문' }), '초안 저장하면 지급 끝인가요?')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
}
function mockResponse(source = 'AI') {
  return vi.spyOn(apiClient, 'request').mockImplementation((async (_path, options) =>
    envelope({
      ...reply,
      source,
      contextRevision: (options?.body as { clientContextRevision: string }).clientContextRevision,
    })) as typeof apiClient.request)
}
it('mounts with AI opt-in, calls the server and renders AI evidence distinct from synthetic buttons', async () => {
  const request = mockResponse()
  render(<AssistantDemoMount user={user} />)
  await open()
  await send()
  expect(await screen.findByText(reply.message)).toBeInTheDocument()
  expect(screen.getByText('AI 가이드 답변')).toBeInTheDocument()
  expect(screen.getByText('지급 초안 저장 · v1.0')).toBeInTheDocument()
  expect(screen.queryByText('시연 질문 바꾸기')).not.toBeInTheDocument()
  expect(request).toHaveBeenCalledTimes(1)
  expect(request).toHaveBeenCalledWith(
    '/api/v1/ai/pico/ask',
    expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({
        question: '초안 저장하면 지급 끝인가요?',
        entityRefs: [],
        conversation: [],
        guideVersion: '1.0',
      }),
    }),
  )
})
it('shows fixed source explicitly when model is unavailable', async () => {
  mockResponse('FIXED')
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  expect(await screen.findByText('고정 가이드')).toBeInTheDocument()
  expect(screen.queryByText('AI 가이드 답변')).not.toBeInTheDocument()
})
it('locks duplicate sends and aborts/discards old response after reset', async () => {
  let finish!: (value: ReturnType<typeof envelope>) => void
  const request = vi.spyOn(apiClient, 'request').mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  await userEvent.type(screen.getByRole('textbox', { name: '피코에게 질문' }), '다음')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  await userEvent.keyboard('{Enter}')
  expect(request).toHaveBeenCalledTimes(1)
  const options = request.mock.calls[0][1]!,
    revision = (options.body as { clientContextRevision: string }).clientContextRevision
  await userEvent.click(screen.getByRole('button', { name: '새 대화' }))
  expect(options.signal?.aborted).toBe(true)
  await act(async () => finish(envelope({ ...reply, contextRevision: revision })))
  expect(screen.queryByText(reply.message)).not.toBeInTheDocument()
  expect(screen.queryByText('초안 저장하면 지급 끝인가요?')).not.toBeInTheDocument()
})
it('rejects executable guide and unknown evidence instead of rendering or navigating', async () => {
  vi.spyOn(apiClient, 'request').mockImplementation((async (_path, options) =>
    envelope({
      ...reply,
      contextRevision: (options?.body as { clientContextRevision: string }).clientContextRevision,
      validatedGuide: { workflowId: 'evil' },
    })) as typeof apiClient.request)
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  expect(await screen.findByText(/답변 서버에 연결하지 못했어요/)).toBeInTheDocument()
  expect(screen.queryByText(reply.message)).not.toBeInTheDocument()
})
it('closes safely and clears account conversation without keeping late replies', async () => {
  let finish!: (value: ReturnType<typeof envelope>) => void
  const request = vi.spyOn(apiClient, 'request').mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const view = render(<AssistantDemoMount user={user} />)
  await open()
  await send()
  const options = request.mock.calls[0][1]!,
    revision = (options.body as { clientContextRevision: string }).clientContextRevision
  await userEvent.click(screen.getByRole('button', { name: '비서 닫기' }))
  expect(options.signal?.aborted).toBe(true)
  await act(async () => finish(envelope({ ...reply, contextRevision: revision })))
  view.rerender(<AssistantDemoMount user={{ ...user, loginId: 'other' }} />)
  await open()
  expect(screen.queryByText('초안 저장하면 지급 끝인가요?')).not.toBeInTheDocument()
  expect(screen.queryByText(reply.message)).not.toBeInTheDocument()
})
it('rejects stale response revisions even when transport ignores abort', async () => {
  vi.spyOn(apiClient, 'request').mockResolvedValue(envelope({ ...reply, contextRevision: 'older' }))
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  await waitFor(() => expect(screen.getByText(/답변 서버에 연결하지 못했어요/)).toBeInTheDocument())
  expect(screen.queryByText(reply.message)).not.toBeInTheDocument()
})

it('distinguishes stale guide conflicts from connection failures without executing a guide', async () => {
  vi.spyOn(apiClient, 'request').mockRejectedValue(
    new ApiError({ code: 'FGC-IMPL-GUIDE_VERSION_MISMATCH', message: 'stale' }, 'qa', 409),
  )
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  expect(await screen.findByText(/가이드 버전이 변경됐어요/)).toBeInTheDocument()
  expect(useGuideRuntime.getState().session).toBeNull()
})

function observePayment(
  overrides: Partial<NonNullable<ReturnType<typeof useGuideRuntime.getState>['observation']>> = {},
) {
  useGuideRuntime.getState().publish({
    owner: 'payment-page',
    paymentId: '77',
    scopeKey: 'scope-77',
    revision: 0,
    canProcess: true,
    ready: true,
    amountValid: true,
    attributionsValid: true,
    formValid: true,
    saved: true,
    busy: false,
    precheck: 'NONE',
    modalOpen: false,
    ...overrides,
  })
}
it('sends only the current saved payment reference without form values or local status claims', async () => {
  observePayment()
  const request = mockResponse()
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  expect(await screen.findByText(reply.message)).toBeInTheDocument()
  const body = request.mock.calls[0][1]!.body as Record<string, unknown>
  expect(body).toMatchObject({ screenId: 'TRAN-W02', entityRefs: [{ type: 'PAYMENT', id: 77 }], conversation: [] })
  expect(body).not.toHaveProperty('amount')
  expect(body).not.toHaveProperty('paymentStatus')
  expect(body).not.toHaveProperty('formDirty')
  expect(screen.queryByText('현재 보고 있는 업무')).not.toBeInTheDocument()
})
it.each([{ saved: false }, { ready: false }, { busy: true }, { paymentId: '9007199254740993' }])(
  'uses a general question instead of sending an unsafe or unsaved payment context %j',
  async (overrides) => {
    observePayment(overrides)
    const request = mockResponse()
    render(<PicoAssistant canProcess />)
    await open()
    await send()
    expect(await screen.findByText(reply.message)).toBeInTheDocument()
    expect((request.mock.calls[0][1]!.body as { entityRefs: unknown[] }).entityRefs).toEqual([])
  },
)
it('aborts and discards a state-aware reply when only the payment id changes', async () => {
  observePayment()
  let finish!: (value: ReturnType<typeof envelope>) => void
  const request = vi.spyOn(apiClient, 'request').mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  const options = request.mock.calls[0][1]!
  const revision = (options.body as { clientContextRevision: string }).clientContextRevision
  act(() => observePayment({ paymentId: '88' }))
  expect(options.signal?.aborted).toBe(true)
  await act(async () => finish(envelope({ ...reply, contextRevision: revision })))
  expect(screen.queryByText(reply.message)).not.toBeInTheDocument()
  expect(useGuideRuntime.getState().session).toBeNull()
})

it('connects the latest answer evidence to an explicitly selected and server-revalidated current guide', async () => {
  observePayment()
  const request = vi.spyOn(apiClient, 'request').mockImplementation((async (path, options) => {
    const body = options!.body as Record<string, unknown>
    if (path.endsWith('/ask')) return envelope({ ...reply, contextRevision: body.clientContextRevision })
    return envelope({
      status: path.endsWith('/prepare') ? 'PREPARED' : 'STARTED',
      workflowId: body.workflowId,
      stepId: 'payment.scope',
      guideVersion: '1.0',
      paymentId: body.paymentId,
      clientContextRevision: body.clientContextRevision,
      expiresAt: '2099-01-01T00:00:00Z',
      prepareToken: path.endsWith('/prepare') ? 'x'.repeat(43) : null,
    })
  }) as typeof apiClient.request)
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  const button = await screen.findByRole('button', { name: /이 업무 안내 시작/ })
  expect(request).toHaveBeenCalledTimes(1)
  await userEvent.click(button)
  await waitFor(() =>
    expect(useGuideRuntime.getState().session).toMatchObject({
      workflowId: 'payment.draft',
      paymentId: '77',
      index: 0,
      status: 'PREPARING',
    }),
  )
  expect(request.mock.calls.map(([path]) => path)).toEqual([
    '/api/v1/ai/pico/ask',
    '/api/v1/ai/pico/guides/prepare',
    '/api/v1/ai/pico/guides/start',
  ])
  expect(screen.queryByRole('textbox', { name: '피코에게 질문' })).not.toBeInTheDocument()
})

it('keeps a completed answer actionable after a temporary ready refresh finishes', async () => {
  observePayment()
  mockResponse()
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  await screen.findByText(reply.message)
  expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeEnabled()
  act(() => observePayment({ ready: false }))
  expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('기준정보')
  act(() => observePayment({ ready: true }))
  expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeEnabled()
})

it('keeps a completed guide offer after closing and reopening the assistant', async () => {
  observePayment()
  const request = mockResponse()
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  await screen.findByText(reply.message)
  await userEvent.click(screen.getByRole('button', { name: '비서 닫기' }))
  await open()
  expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeEnabled()
  expect(request).toHaveBeenCalledTimes(1)
})
it.each([{ paymentId: '88' }, { revision: 1 }, { scopeKey: 'other-scope' }, { saved: false }])(
  'invalidates completed guide offers after an actual context change, including return to the old context %j',
  async (changed) => {
    observePayment()
    const request = mockResponse()
    render(<PicoAssistant canProcess />)
    await open()
    await send()
    await screen.findByText(reply.message)
    act(() => observePayment(changed))
    expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toHaveAccessibleDescription(/다시 질문/)
    act(() => observePayment())
    expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeDisabled()
    expect(request).toHaveBeenCalledTimes(1)
    await send()
    await waitFor(() => expect(screen.getAllByRole('button', { name: /이 업무 안내 시작/ }).at(-1)).toBeEnabled())
    expect(screen.getAllByRole('button', { name: /이 업무 안내 시작/ })[0]).toBeDisabled()
  },
)
it('explains a missing payment context instead of silently removing the guide action', async () => {
  mockResponse()
  render(<PicoAssistant canProcess />)
  await open()
  await send()
  await screen.findByText(reply.message)
  expect(screen.getByRole('button', { name: /이 업무 안내 시작/ })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('저장된 지급 초안')
})
