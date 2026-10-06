import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient, ApiError } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import { usePermission } from './useAuth'
import { authMessages } from './messages'

const user = {
  loginId: 'settle01',
  userName: '정산 담당자',
  roleCode: 'SETTLEMENT',
  demoMonth: '2026-07',
  canProcess: true,
  canViewAuditLog: false,
  canHandleException: true,
  canReverseJournal: true,
  canFinalizeValidation: false,
}
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'auth-test' })
const clients: QueryClient[] = []

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  localStorage.clear()
  sessionStorage.clear()
  vi.spyOn(apiClient, 'request').mockResolvedValue(envelope(user))
  vi.spyOn(apiClient, 'login').mockImplementation(async () => {
    useAuthStore.getState().setAccessToken('access')
    return envelope({ accessToken: 'access', tokenType: 'Bearer', expiresIn: 1800 })
  })
  vi.spyOn(apiClient, 'logout').mockImplementation(async () => {
    useAuthStore.getState().setAccessToken(null)
    return envelope(null)
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  for (const client of clients.splice(0)) client.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

function renderAt(path = '/app/login', authenticated = false, base = '/app') {
  useAuthStore.getState().setAccessToken(authenticated ? 'access' : null)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter(routes, { basename: base.replace(/\/?$/, '/'), initialEntries: [path] })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, client, actions: userEvent.setup() }
}
async function enterCredentials(actions: ReturnType<typeof userEvent.setup>, loginId = 'settle01') {
  await actions.type(screen.getByLabelText('아이디', { exact: false }), loginId)
  await actions.type(screen.getByLabelText('비밀번호', { exact: false, selector: 'input' }), 'password')
  await actions.click(screen.getByRole('button', { name: '로그인' }))
}

describe('AUTH-W01 로그인', () => {
  it('필수값을 검사하고 비밀번호 보기·숨기기를 제공한다', async () => {
    const { actions } = renderAt()
    expect(apiClient.request).not.toHaveBeenCalled()
    await actions.click(screen.getByRole('button', { name: '로그인' }))
    expect(screen.getByRole('alert')).toHaveTextContent(authMessages.required)
    expect(screen.getByLabelText('아이디', { exact: false })).toHaveFocus()
    expect(apiClient.login).not.toHaveBeenCalled()
    const password = screen.getByLabelText('비밀번호', { exact: false, selector: 'input' })
    await actions.click(screen.getByRole('button', { name: '비밀번호 표시' }))
    expect(password).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: '비밀번호 숨기기' })).toHaveAttribute('aria-pressed', 'true')
    await actions.click(screen.getByRole('button', { name: '비밀번호 숨기기' }))
    expect(password).toHaveAttribute('type', 'password')
  })

  it.each(['없는 아이디', '틀린 비밀번호'])('%s를 구분하지 않는 실패 문구를 보여 준다', async (reason) => {
    vi.mocked(apiClient.login).mockRejectedValue(new ApiError({ code: 'FGC-AUTH-001', message: reason }, 'r', 401))
    const { actions } = renderAt()
    await enterCredentials(actions)
    expect(await screen.findByRole('alert')).toHaveTextContent(authMessages.invalidCredentials)
    expect(screen.queryByText(reason)).not.toBeInTheDocument()
  })

  it('500의 내부 메시지와 오류 코드 대신 요청 ID만 표시한다', async () => {
    vi.mocked(apiClient.login).mockRejectedValue(
      new ApiError({ code: 'FGC-COMMON-500', message: 'internal details' }, 'trace-404', 500),
    )
    const { actions } = renderAt()
    await enterCredentials(actions)
    expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: trace-404')
    expect(screen.queryByText(/internal details|FGC-COMMON-500/)).toBeNull()
  })

  it.each(['/app', ''])('base=%s: 보호 경로와 query/hash를 보존하여 로그인 후 복귀한다', async (base) => {
    const target = '/transactions/new?month=2026-08&status=ACTIVE#form'
    const { router, actions, client } = renderAt(`${base}${target}`, false, base || '/')
    await screen.findByRole('heading', { name: '로그인' })
    expect(new URLSearchParams(router.state.location.search).get('redirect')).toBe(target)
    client.setQueryData(['private', 'previous-user'], { secret: 'old' })
    sessionStorage.setItem('fgc.other.preference', 'old')
    localStorage.setItem('unrelated', 'keep')
    await enterCredentials(actions)
    await screen.findByRole('heading', { name: '수수료 지급 등록' })
    expect(router.state.location.pathname).toBe(`${base}/transactions/new`)
    expect(router.state.location.search).toBe('?month=2026-08&status=ACTIVE')
    expect(router.state.location.hash).toBe('#form')
    expect(client.getQueryData(['private', 'previous-user'])).toBeUndefined()
    expect(sessionStorage.getItem('fgc.other.preference')).toBeNull()
    expect(localStorage.getItem('unrelated')).toBe('keep')
    expect(localStorage.getItem('accessToken')).toBeNull()
  })

  it.each([
    '//evil.example',
    'https://evil.example',
    '/\\evil.example',
    '/login',
    '/login?redirect=/contracts',
    '/LOGIN/',
    '/%6cogin',
    '/contracts/../login',
  ])('유효하지 않은 redirect=%s는 대시보드로 보낸다', async (target) => {
    const { router, actions } = renderAt(`/app/login?redirect=${encodeURIComponent(target)}`)
    await enterCredentials(actions)
    await screen.findByRole('heading', { name: '업무 대시보드' })
    expect(router.state.location.pathname).toBe('/app/')
  })

  it('이미 인증한 사용자는 redirect 파라미터와 무관하게 대시보드로 보낸다', async () => {
    const { router } = renderAt('/app/login?redirect=%2Fcontracts', true)
    await screen.findByRole('heading', { name: '업무 대시보드' })
    expect(router.state.location.pathname).toBe('/app/')
    expect(apiClient.login).not.toHaveBeenCalled()
  })

  it('중복 로그인 안내를 표시하고 다시 로그인하면 원래 화면으로 돌아간다', async () => {
    const { router, actions } = renderAt('/app/login?reason=duplicate&redirect=%2Fcontracts%3Fmonth%3D2026-07')
    expect(screen.getByRole('status')).toHaveTextContent(authMessages.superseded)
    await enterCredentials(actions)
    await screen.findByRole('heading', { name: '보험계약' })
    expect(router.state.location.pathname).toBe('/app/contracts')
  })

  it('로그아웃은 토큰·캐시·워크스페이스를 지우고 뒤로 가도 보호 화면을 열지 않는다', async () => {
    const { router, client, actions } = renderAt('/app/contracts', true)
    await screen.findByRole('heading', { name: '보험계약' })
    await act(() => router.navigate('/transactions?month=2026-07'))
    await screen.findByRole('heading', { name: '수수료 지급' })
    client.setQueryData(['private'], 'secret')
    await actions.click(screen.getByRole('button', { name: '로그아웃' }))
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(router.state.location.search).toBe('?logout')
    expect(screen.getByRole('status')).toHaveTextContent(authMessages.loggedOut)
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(client.getQueryData(['private'])).toBeUndefined()
    expect(useWorkspaceStore.getState().owner).toBeNull()
    await act(() => router.navigate(-1))
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/login'))
    expect(screen.queryByRole('navigation', { name: '주요 업무 메뉴' })).toBeNull()
  })

  it('서버 로그아웃 실패 시 보호 화면을 닫고 재시도를 제공한다', async () => {
    vi.mocked(apiClient.logout).mockImplementation(async () => {
      useAuthStore.getState().setAccessToken(null)
      throw new Error('network')
    })
    const { actions } = renderAt('/app/contracts', true)
    await screen.findByRole('heading', { name: '보험계약' })
    await actions.click(screen.getByRole('button', { name: '로그아웃' }))
    expect(await screen.findByRole('button', { name: '로그아웃 다시 시도' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(authMessages.logoutFailed)
    vi.mocked(apiClient.logout).mockResolvedValue(envelope(null))
    await actions.click(screen.getByRole('button', { name: '로그아웃 다시 시도' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(authMessages.loggedOut))
  })

  it('인증이 사라지면 캐시를 비우고 보호 화면 경로를 보존한다', async () => {
    const { router, client } = renderAt('/app/contracts?month=2026-07', true)
    await screen.findByRole('heading', { name: '보험계약' })
    client.setQueryData(['private'], 'secret')
    act(() => useAuthStore.getState().setAccessToken(null))
    await screen.findByRole('heading', { name: '로그인' })
    expect(new URLSearchParams(router.state.location.search).get('redirect')).toBe('/contracts?month=2026-07')
    expect(client.getQueryData(['private'])).toBeUndefined()
  })
})

describe('서버 권한 플래그', () => {
  it('COMPLIANCE는 감사로그를 열고 지급 등록은 403으로 차단한다', async () => {
    vi.mocked(apiClient.request).mockResolvedValue(
      envelope({ ...user, roleCode: 'COMPLIANCE', canProcess: false, canViewAuditLog: true }),
    )
    const { router } = renderAt('/app/audit-logs', true)
    await screen.findByRole('heading', { name: '감사로그' })
    await act(() => router.navigate('/transactions/new'))
    expect(await screen.findByRole('alert')).toHaveTextContent(authMessages.forbidden)
    expect(screen.queryByRole('link', { name: '기존 화면으로 이동' })).toBeNull()
  })

  it('SYSTEM_ADMIN이라는 역할명만으로 누락된 플래그를 허용하지 않는다', async () => {
    vi.mocked(apiClient.request).mockResolvedValue(
      envelope({ ...user, roleCode: 'SYSTEM_ADMIN', canProcess: undefined }),
    )
    renderAt('/app/transactions/new', true)
    expect(await screen.findByRole('alert')).toHaveTextContent(authMessages.forbidden)
  })

  it('버튼 훅은 서버의 5개 권한 플래그를 공유하고 미인증이면 모두 거부한다', async () => {
    function Buttons() {
      const process = usePermission('canProcess')
      const audit = usePermission('canViewAuditLog')
      const exception = usePermission('canHandleException')
      const reverse = usePermission('canReverseJournal')
      const finalize = usePermission('canFinalizeValidation')
      return <output>{JSON.stringify([process, audit, exception, reverse, finalize])}</output>
    }
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    clients.push(client)
    useAuthStore.getState().setAccessToken('access')
    render(
      <QueryClientProvider client={client}>
        <Buttons />
      </QueryClientProvider>,
    )
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('[true,false,true,true,false]'))
    act(() => useAuthStore.getState().setAccessToken(null))
    expect(screen.getByRole('status')).toHaveTextContent('[false,false,false,false,false]')
  })
})
