import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient } from '../../lib/api/client'
import { readEnvelope } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'

// 대시보드(/) 화면이 렌더링 중에 던질 값. 실제 routes·AppShell 안에서 에러 바운더리를 검사한다.
const page = vi.hoisted(() => ({ thrown: null as unknown }))
vi.mock('../dashboard/DashboardPage', () => ({
  DashboardPage: () => {
    if (page.thrown) throw page.thrown
    return <h1>업무 대시보드</h1>
  },
}))

beforeEach(() => {
  page.thrown = null
  useAuthStore.setState({ accessToken: 'error-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

function renderAt(path: string, roleCode = 'SETTLEMENT') {
  vi.spyOn(apiClient, 'request').mockResolvedValue({
    data: {
      loginId: 'test',
      userName: '테스트 담당자',
      roleCode,
      canProcess: roleCode === 'SETTLEMENT',
      canViewAuditLog: false,
      demoMonth: '2026-07',
    },
    error: null,
    requestId: 'me',
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}
// client.ts가 응답을 ApiError로 바꾸는 실제 경로(readEnvelope)를 거친다.
async function apiErrorFrom(response: Response) {
  return readEnvelope(response).then(() => { throw new Error('ApiError expected') }, (error: unknown) => error)
}
// 셸은 기준월이 없는 주소를 ?month= 를 붙인 주소로 바꾼다. 위치가 바뀌면 바운더리가 의도대로 초기화되어
// 오류 화면을 다시 그리므로, 그 사이에 찾은 요소가 문서에서 떨어진다. 오류 화면 검사는 기준월이 붙은 주소에서 연다.
const HOME = '/?month=2026-07'
function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('오류 화면 (#417)', () => {
  it('1. 없는 경로는 셸 안에서 404 화면과 대시보드 링크를 보인다', async () => {
    renderAt('/no-such')
    expect(await screen.findByRole('heading', { name: '404 · 찾을 수 없음' })).toBeInTheDocument()
    expect(screen.getByText('요청한 화면이 없습니다.')).toBeInTheDocument()
    expect(screen.getByText(/2차 범위라 아직 만들어지지 않았습니다/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '대시보드로' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('navigation', { name: '주요 업무 메뉴' })).toBeInTheDocument()
    await waitFor(() => expect(document.title).toBe('찾을 수 없음 (FGC-UI-ERR-404) · FGC'))
  })

  it('2. 권한 없는 라우트는 1차와 같은 403 문구를 보인다', async () => {
    renderAt('/contracts/new', 'COMPLIANCE')
    expect(await screen.findByRole('heading', { name: '403 · 권한 없음' })).toBeInTheDocument()
    expect(screen.getByText('이 작업을 할 권한이 없습니다.')).toBeInTheDocument()
    expect(screen.getByText('FGC-AUTH-003')).toBeInTheDocument()
    expect(screen.getByText(/필요한 권한은 GA관리자에게 요청하세요/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '대시보드로' })).toBeInTheDocument()
    await waitFor(() => expect(document.title).toBe('권한 없음 (FGC-UI-ERR-403) · FGC'))
  })

  it('3. API 500은 서버 문구 대신 요청번호를 보이고 복사할 수 있다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    page.thrown = await apiErrorFrom(json(500, {
      data: null,
      error: { code: 'FGC-COMMON-500', message: 'SQLException: 내부 정보' },
      requestId: 'req-500',
    }))
    renderAt(HOME)
    expect(await screen.findByRole('heading', { name: '500 · 처리 오류' })).toBeInTheDocument()
    expect(screen.getByText('처리 중 오류가 발생했습니다. 요청번호 req-500를 담당자에게 알려주세요.')).toBeInTheDocument()
    expect(screen.getByText('FGC-COMMON-500')).toBeInTheDocument()
    expect(screen.queryByText(/SQLException/)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '요청 ID 복사' }))
    expect(writeText).toHaveBeenCalledWith('req-500')
    expect(await screen.findByRole('button', { name: '복사했습니다' })).toBeInTheDocument()
  })

  it('4. 렌더링 예외는 셸 안의 오류 화면으로 받고 다른 탭으로 이동할 수 있다', async () => {
    page.thrown = new TypeError('Cannot read properties of undefined (secret.stack)')
    // 이동하는 렌더에서 옛 화면이 한 번 더 던지면 React 19가 동기 렌더로 복구하고 window error 이벤트로 알린다.
    const reported: unknown[] = []
    const onError = (event: ErrorEvent) => { event.preventDefault(); reported.push(event.error) }
    window.addEventListener('error', onError)
    onTestFinished(() => window.removeEventListener('error', onError))
    const router = renderAt(HOME)
    expect(await screen.findByRole('heading', { name: '500 · 처리 오류' })).toBeInTheDocument()
    expect(screen.getByText('처리 중 오류가 발생했습니다. 요청번호 -를 담당자에게 알려주세요.')).toBeInTheDocument()
    expect(screen.queryByText(/secret\.stack/)).toBeNull()
    await userEvent.click(screen.getByRole('link', { name: '보험계약' }))
    expect(await screen.findByRole('heading', { name: '보험계약' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/contracts')
    expect(screen.queryByRole('heading', { name: '500 · 처리 오류' })).toBeNull()
    expect(reported.every((error) => (error as Error).cause === page.thrown)).toBe(true)
  })

  it('5. 비JSON 502는 헤더의 요청 ID로 오류를 보인다', async () => {
    page.thrown = await apiErrorFrom(new Response('<html>Bad Gateway</html>', {
      status: 502,
      headers: { 'Content-Type': 'text/html', 'X-Request-Id': 'req-502' },
    }))
    renderAt(HOME)
    expect(await screen.findByRole('heading', { name: '500 · 처리 오류' })).toBeInTheDocument()
    expect(screen.getByText('처리 중 오류가 발생했습니다. 요청번호 req-502를 담당자에게 알려주세요.')).toBeInTheDocument()
    expect(screen.queryByText(/Bad Gateway/)).toBeNull()
  })

  it('6. 상세 API 404는 서버 문구로 404를 보이며 경로 없음·빈 목록과 구분한다', async () => {
    page.thrown = await apiErrorFrom(json(404, {
      data: null,
      error: { code: 'FGC-COMMON-004', message: '요청한 데이터를 찾을 수 없습니다. (12)' },
      requestId: 'req-404',
    }))
    renderAt(HOME)
    expect(await screen.findByRole('heading', { name: '404 · 찾을 수 없음' })).toBeInTheDocument()
    expect(screen.getByText('요청한 데이터를 찾을 수 없습니다. (12)')).toBeInTheDocument()
    expect(screen.getByText('FGC-COMMON-004 · 요청 ID: req-404')).toBeInTheDocument()
    expect(screen.queryByText('요청한 화면이 없습니다.')).toBeNull()
  })

  it('업무 오류(409)는 1차 business 화면처럼 상태 · 처리 오류로 문구를 그대로 보인다', async () => {
    page.thrown = await apiErrorFrom(json(409, {
      data: null,
      error: { code: 'FGC-SCHE-001', message: '되돌릴 수 없습니다 — 확정된 스케줄은 새 버전으로만 바꿉니다.' },
      requestId: 'req-409',
    }))
    renderAt(HOME)
    expect(await screen.findByRole('heading', { name: '409 · 처리 오류' })).toBeInTheDocument()
    expect(screen.getByText('FGC-SCHE-001 · 요청 ID: req-409')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '요청 ID 복사' })).toBeNull()
  })
})
