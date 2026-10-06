import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Sidebar } from './Sidebar'
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
