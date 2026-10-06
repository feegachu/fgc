import { render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from './routes'
import { apiClient } from '../lib/api/client'
import { useWorkspaceStore } from '../stores/workspace'
import { useSidebarStore } from '../stores/sidebar'
import { screens } from './screens'
import { queryClient } from './queryClient'
import { subscribeApiErrors } from '../lib/api/errorNotifications'
beforeEach(() => {
  useWorkspaceStore.getState().clear()
  useSidebarStore.setState({ collapsed: false, openSections: [] })
})
afterEach(() => vi.restoreAllMocks())
function renderAt(path: string, roleCode = 'SETTLEMENT') {
  vi.spyOn(apiClient, 'request').mockResolvedValue({
    data: {
      loginId: 'test',
      userName: '테스트 담당자',
      roleCode,
      canProcess: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
      canViewAuditLog: ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(roleCode),
      demoMonth: '2026-07',
    },
    error: null,
    requestId: 'r',
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}
describe('AppShell·라우트', () => {
  it.each(['SETTLEMENT', 'COMPLIANCE', 'GA_ADMIN', 'SYSTEM_ADMIN'])(
    '%s 역할의 감사로그 메뉴는 서버 권한 플래그를 따른다',
    async (role) => {
      renderAt('/contracts', role)
      await screen.findByRole('heading', { name: '보험계약' })
      const audit = screen.queryByRole('link', { name: '감사로그', hidden: true })
      expect(Boolean(audit)).toBe(['COMPLIANCE', 'SYSTEM_ADMIN'].includes(role))
      const labels = [...screen.getByRole('navigation', { name: '주요 업무 메뉴' }).querySelectorAll('a')].map(
        (link) => link.querySelector('.sidebar-nav-label')?.textContent ?? link.textContent,
      )
      expect(labels.slice(0, 12)).toEqual([
        '업무 대시보드',
        '보험계약',
        '수수료 지급',
        '예상 스케줄',
        '1,200% 한도 검증',
        '차익거래 검증',
        '월 통합검증',
        '검증원장',
        '대사 실행·결과',
        '예외',
        '기준정보',
        '정책·룰셋',
      ])
    },
  )
  it('서버 기본월을 적용하며 상세 기존 경로와 검색 조건을 유지한다', async () => {
    const router = renderAt('/contracts/12?status=ACTIVE&page=2')
    await screen.findByRole('heading', { name: '계약 상세' })
    await waitFor(() =>
      expect(screen.getByRole('link', { name: '기존 화면으로 이동' })).toHaveAttribute(
        'href',
        '/contracts/12?status=ACTIVE&month=2026-07',
      ),
    )
    expect(router.state.location.search).toContain('month=2026-07')
    expect(router.state.location.search).not.toContain('page=')
  })
  it('미등록 경로를 404 연결 화면으로 보낸다', async () => {
    renderAt('/xyz')
    expect(await screen.findByRole('heading', { name: '페이지를 찾을 수 없습니다' })).toBeInTheDocument()
  })
  it('수정 라우트 메타데이터를 등록하고 쓰기 권한이 없으면 기존 링크도 숨긴다', async () => {
    renderAt('/contracts/12/edit', 'COMPLIANCE')
    expect(await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '기존 화면으로 이동' })).toBeNull()
    expect(screens.find((route) => route.path === '/contracts/:id/edit')?.permission).toBe('canProcess')
  })
})
describe('AppShell /auth/me 실패', () => {
  it('셸 오류 화면만 보여 주고 전역 Toast 를 띄우지 않는다', async () => {
    vi.spyOn(apiClient, 'request').mockRejectedValue(new Error('사용자 정보를 불러오지 못했습니다.'))
    const notify = vi.fn()
    const unsubscribe = subscribeApiErrors(notify)
    // 전역 QueryCache onError 가 붙은 실제 queryClient 로 그려야 중복 알림을 잡는다.
    const defaults = queryClient.getDefaultOptions()
    queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retry: false } })
    try {
      render(
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/contracts'] })} />
        </QueryClientProvider>,
      )
      expect(await screen.findByRole('button', { name: '다시 시도' })).toBeInTheDocument()
      expect(screen.getAllByRole('alert')).toHaveLength(1)
      expect(notify).not.toHaveBeenCalled()
    } finally {
      unsubscribe()
      queryClient.clear()
      queryClient.setDefaultOptions(defaults)
    }
  })
})
