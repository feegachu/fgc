import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import type { ClosingNotification } from './api'

const notification = (closingNotificationId: number, overrides: Partial<ClosingNotification> = {}): ClosingNotification => ({
  closingNotificationId,
  eventType: 'FINALIZE_READY',
  validationRunId: 11,
  validationMonth: '2026-10-01',
  recipientName: '김관리',
  recipientLoginId: 'gaadmin',
  recipientPhone: '010-****-0102',
  messageText: '[FGC] 2026년 10월 통합검증이 확정 조건을 모두 채웠습니다. 확정해 주세요.',
  status: 'SENT',
  attemptCount: 1,
  createdAt: '2026-11-17T10:00:07+09:00',
  sentAt: '2026-11-17T10:00:12+09:00',
  ...overrides,
})
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'notification-test' })
let requested: { url: string; method?: string }[]

function renderAt(
  path: string,
  roleCode: string,
  rows: ClosingNotification[] | (() => ClosingNotification[]),
  totalPages = 1,
) {
  const currentRows = typeof rows === 'function' ? rows : () => rows
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    requested.push({ url, method: options?.method })
    if (url === '/api/v1/auth/me')
      return envelope({
        loginId: 'gaadmin',
        userName: '김관리',
        roleCode,
        demoMonth: '2026-10',
        canFinalizeValidation: ['GA_ADMIN', 'SYSTEM_ADMIN'].includes(roleCode),
      })
    if (url.startsWith('/api/v1/notifications?')) {
      const content = currentRows()
      return envelope({ content, page: 1, size: 20, totalElements: content.length, totalPages })
    }
    if (/^\/api\/v1\/notifications\/\d+\/resend$/.test(url)) return envelope(null)
    throw new Error(`unexpected ${url}`)
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup() }
}
const listRequests = () => requested.filter((request) => request.url.startsWith('/api/v1/notifications?'))

// 라우트는 화면을 lazy 로 불러온다. 첫 변환 시간이 findBy 기본 대기(1초)를 넘지 않도록 미리 받아 둔다.
beforeAll(async () => {
  await import('./NotificationHistoryPage')
})

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'notification-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('NOTI-W01 알림 발송 이력', () => {
  it('사건, 상태, 사유를 코드가 아니라 우리말로 보여 준다', async () => {
    renderAt('/notifications', 'GA_ADMIN', [
      notification(3, { status: 'SKIPPED', errorCode: 'NO_API_KEY', sentAt: undefined }),
      notification(2, { eventType: 'RUN_FINALIZED', recipientName: '손설계', recipientPhone: '010-****-0201' }),
    ])
    const table = await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })
    await within(table).findByText('확정 대기')
    const [, skipped, sent] = within(table).getAllByRole('row')
    expect(within(skipped).getByText('확정 대기')).toBeInTheDocument()
    expect(within(skipped).getByText('건너뜀')).toBeInTheDocument()
    expect(within(skipped).getByText('문자 발송이 꺼져 있음')).toBeInTheDocument()
    expect(within(skipped).getByText('010-****-0102')).toBeInTheDocument()
    expect(within(sent).getByText('확정 통지')).toBeInTheDocument()
    expect(within(sent).getByText('보냄')).toBeInTheDocument()
    expect(within(sent).getByText('손설계')).toBeInTheDocument()
    for (const code of ['FINALIZE_READY', 'RUN_FINALIZED', 'SKIPPED', 'SENT', 'NO_API_KEY'])
      expect(table).not.toHaveTextContent(code)
    expect(listRequests()[0].url).toBe('/api/v1/notifications?page=1&size=20')
  })

  it('그사이 확정돼 보내지 않은 확정 대기 알림은 이미 확정됨으로 보여 준다', async () => {
    renderAt('/notifications', 'GA_ADMIN', [
      notification(6, { status: 'SKIPPED', errorCode: 'ALREADY_FINALIZED', sentAt: undefined }),
    ])
    const table = await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })
    expect(await within(table).findByText('이미 확정됨')).toBeInTheDocument()
    expect(table).not.toHaveTextContent('ALREADY_FINALIZED')
  })

  it('알린 뒤 확정 조건이 깨져 보내지 않은 확정 대기 알림은 지금은 확정할 수 없다고 보여 준다', async () => {
    renderAt('/notifications', 'GA_ADMIN', [
      notification(7, { status: 'SKIPPED', errorCode: 'NO_LONGER_READY', sentAt: undefined }),
    ])
    const table = await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })
    expect(await within(table).findByText('지금은 확정할 수 없음')).toBeInTheDocument()
    expect(table).not.toHaveTextContent('NO_LONGER_READY')
  })

  it('실패했거나 건너뛴 알림만 다시 보낼 수 있고, 다시 보내면 목록을 새로 읽는다', async () => {
    const { actions } = renderAt('/notifications', 'GA_ADMIN', [
      notification(5, { status: 'FAILED', errorCode: 'TIMEOUT', sentAt: undefined }),
      notification(4),
      notification(3, { status: 'PENDING', sentAt: undefined }),
    ])
    const table = await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })
    expect(await within(table).findAllByRole('button', { name: '다시 보내기' })).toHaveLength(1)
    expect(within(table).getByText('응답 없음')).toBeInTheDocument()

    await actions.click(within(table).getByRole('button', { name: '다시 보내기' }))

    await waitFor(() =>
      expect(requested).toContainEqual({ url: '/api/v1/notifications/5/resend', method: 'POST' }),
    )
    await waitFor(() => expect(listRequests()).toHaveLength(2))
    expect(await screen.findByText('다시 보냅니다. 잠시 뒤 상태를 확인하세요.')).toBeInTheDocument()
  })

  it('대기 중인 알림이 있는 동안만 스스로 다시 읽어 바뀐 상태를 보여 준다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let status = 'PENDING'
    renderAt('/notifications', 'GA_ADMIN', () => [notification(7, { status, sentAt: undefined })])
    const table = await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })
    expect(await within(table).findByText('대기')).toBeInTheDocument()
    expect(listRequests()).toHaveLength(1)

    status = 'SENT'
    await act(() => vi.advanceTimersByTimeAsync(10_000))

    expect(await within(table).findByText('보냄')).toBeInTheDocument()
    expect(listRequests()).toHaveLength(2)
    await act(() => vi.advanceTimersByTimeAsync(30_000))
    expect(listRequests()).toHaveLength(2)
  })

  it('예상하지 못한 오류로 끝난 알림도 사유를 우리말로 보여 준다', async () => {
    renderAt('/notifications', 'GA_ADMIN', [
      notification(8, { status: 'FAILED', errorCode: 'INTERNAL', sentAt: undefined }),
    ])
    const table = await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })
    expect(await within(table).findByText('처리 중 오류')).toBeInTheDocument()
    expect(table).not.toHaveTextContent('INTERNAL')
  })

  it('받는 사람이 없던 사건은 그렇게 적고 다시 보내기를 두지 않는다', async () => {
    renderAt('/notifications', 'GA_ADMIN', [
      notification(6, {
        status: 'NO_RECIPIENT',
        recipientName: undefined,
        recipientLoginId: undefined,
        recipientPhone: undefined,
        attemptCount: 0,
        sentAt: undefined,
      }),
    ])
    const table = await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })
    expect(await within(table).findByText('받는 사람 없음')).toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: '다시 보내기' })).not.toBeInTheDocument()
  })

  it('여러 쪽이면 고른 쪽을 서버에 요청한다', async () => {
    const { actions } = renderAt('/notifications', 'GA_ADMIN', [notification(1)], 3)
    await screen.findByRole('table', { name: '마감 알림 문자 발송 이력' })

    await actions.click(await screen.findByRole('button', { name: '2' }))

    await waitFor(() => expect(listRequests().at(-1)?.url).toBe('/api/v1/notifications?page=2&size=20'))
  })

  it('확정 권한이 없는 역할에는 화면을 열지 않고 이력도 요청하지 않는다', async () => {
    renderAt('/notifications', 'SETTLEMENT', [notification(1)])
    expect(await screen.findByText('권한 없음')).toBeInTheDocument()
    expect(listRequests()).toHaveLength(0)
  })
})
