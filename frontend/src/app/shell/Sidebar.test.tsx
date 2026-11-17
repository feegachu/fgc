import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Sidebar } from './Sidebar'
import { apiClient } from '../../lib/api/client'
import { useSidebarStore } from '../../stores/sidebar'
import { screenFor } from '../screens'
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  useSidebarStore.setState({ collapsed: false, openSections: [] })
})
it('사이드바 접힘 상태는 localStorage, 열린 섹션은 sessionStorage에 기억한다', async () => {
  render(
    <MemoryRouter>
      <Sidebar
        user={{ loginId: 'u', userName: '담당자', roleCode: 'SETTLEMENT', canProcess: true, canViewAuditLog: false }}
        current={screenFor('/contracts')}
        hrefFor={(path) => path}
        onLogout={() => {}}
        loggingOut={false}
      />
    </MemoryRouter>,
  )
  expect(JSON.parse(sessionStorage.getItem('fgc.sidebar.open-sections.v1')!)).toContain('contracts')
  await userEvent.click(screen.getByRole('button', { name: '사이드바 접기' }))
  expect(localStorage.getItem('fgc.sidebar.collapsed.v1')).toBe('true')
  await userEvent.click(screen.getByRole('button', { name: '사이드바 펼치기' }))
  expect(localStorage.getItem('fgc.sidebar.collapsed.v1')).toBe('false')
  await userEvent.click(screen.getByText('관리'))
  expect(JSON.parse(sessionStorage.getItem('fgc.sidebar.open-sections.v1')!)).toContain('admin')
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeEnabled()
})

it('알림 설정과 발송 이력 메뉴는 마감 알림을 받는 역할에게만 보인다 (FUN-089)', async () => {
  vi.spyOn(apiClient, 'request').mockResolvedValue({ data: { smsConfigured: true }, error: null, requestId: 'sidebar' })
  const shell = (roleCode: string, canFinalizeValidation: boolean) =>
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <Sidebar
            user={{ loginId: 'u', userName: '담당자', roleCode, canProcess: false, canViewAuditLog: false, canFinalizeValidation }}
            current={screenFor('/contracts')}
            hrefFor={(path) => path}
            onLogout={() => {}}
            loggingOut={false}
          />
        </MemoryRouter>
      </QueryClientProvider>,
    )

  const settlement = shell('SETTLEMENT', false)
  expect(screen.queryByRole('button', { name: '알림 설정' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '알림 발송 이력', hidden: true })).not.toBeInTheDocument()
  settlement.unmount()

  const agent = shell('AGENT', false)
  expect(screen.getByRole('button', { name: '알림 설정' })).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '알림 발송 이력', hidden: true })).not.toBeInTheDocument()
  agent.unmount()

  shell('GA_ADMIN', true)
  expect(screen.getByRole('link', { name: '알림 발송 이력', hidden: true })).toHaveAttribute('href', '/notifications')
  await userEvent.click(screen.getByRole('button', { name: '알림 설정' }))
  expect(await screen.findByRole('dialog', { name: '알림 설정' })).toBeInTheDocument()
  vi.restoreAllMocks()
})
