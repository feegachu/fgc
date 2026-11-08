import { useEffect } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it } from 'vitest'
import { createMemoryRouter, Link, Outlet, RouterProvider, useLocation } from 'react-router'
import { DraftNavigationGuard } from './DraftNavigationGuard'
import { WorkspaceTabs } from './WorkspaceTabs'
import { screenFor } from '../screens'
import { useUnsavedWork, useUnsavedWorkStore } from '../../stores/unsavedWork'
import { useWorkspaceStore } from '../../stores/workspace'
function Dirty() { useUnsavedWork(true); return <><input defaultValue="미저장" aria-label="입력" /><Link to="/contracts?month=2026-07">목록으로</Link></> }
function Shell() {
  const location = useLocation()
  useEffect(() => {
    const current = screenFor(location.pathname)
    if (current) useWorkspaceStore.getState().openTab({
      id: current.id, title: current.title, icon: current.icon, href: location.pathname + location.search,
    })
  }, [location.pathname, location.search])
  return <><WorkspaceTabs activeId={screenFor(location.pathname)?.id} /><Outlet /><DraftNavigationGuard /></>
}
function setup(path = '/contracts/new') {
  useWorkspaceStore.getState().clear()
  useWorkspaceStore.setState({ month: '2026-07' })
  const router = createMemoryRouter([{ element: <Shell />, children: [
    { path: '/', element: <p>홈</p> }, { path: '/contracts/new', element: <Dirty /> },
    { path: '/contracts/21/edit', element: <Dirty /> }, { path: '/transactions/new', element: <Dirty /> },
    { path: '/contracts', element: <p>계약 목록</p> },
  ] }], { initialEntries: ['/', `${path}?month=2026-07`] })
  render(<RouterProvider router={router} />)
  return router
}
afterEach(() => { useUnsavedWorkStore.setState({ owners: {} }); useWorkspaceStore.getState().clear() })
it('restores the active tab when closing it is cancelled without losing the draft', async () => {
  const router = setup()
  await userEvent.click(screen.getByRole('button', { name: '보험계약 등록/수정 탭 닫기' }))
  await userEvent.click(screen.getByRole('button', { name: '돌아가기' }))
  expect(router.state.location.pathname).toBe('/contracts/new')
  expect(useWorkspaceStore.getState().tabs.some((tab) => tab.id === 'CONT-W03')).toBe(true)
  expect(screen.getByRole('textbox', { name: '입력' })).toHaveValue('미저장')
})
it.each([
  ['/contracts/new', '목록으로', '/contracts'],
  ['/contracts/21/edit', '목록으로', '/contracts'],
  ['/transactions/new', '목록으로', '/contracts'],
  ['/contracts/new', '업무 대시보드', '/'],
  ['/contracts/21/edit', '업무 대시보드', '/'],
  ['/transactions/new', '업무 대시보드', '/'],
])('%s에서 %s 이동을 취소하면 입력을 유지하고 이동을 확정하면 편집 탭을 닫는다', async (path, link, destination) => {
  const router = setup(path)
  const editorId = screenFor(path)!.id
  await userEvent.click(screen.getByRole('link', { name: link }))
  expect(screen.getByRole('dialog', { name: '저장하지 않은 입력이 있습니다' })).toBeInTheDocument()
  expect(useWorkspaceStore.getState().tabs.some(tab => tab.id === editorId)).toBe(true)
  await userEvent.click(screen.getByRole('button', { name: '돌아가기' }))
  expect(router.state.location.pathname).toBe(path)
  expect(screen.getByRole('textbox', { name: '입력' })).toHaveValue('미저장')
  await userEvent.click(screen.getByRole('link', { name: link }))
  await userEvent.click(screen.getByRole('button', { name: '이동' }))
  await waitFor(() => expect(router.state.location.pathname).toBe(destination))
  expect(useWorkspaceStore.getState().tabs.some(tab => tab.id === editorId)).toBe(false)
  expect(useWorkspaceStore.getState().tabs[0].id).toBe('DASH-W01')
})
it('guards browser back, month changes and browser unload', async () => {
  const router = setup()
  await act(() => router.navigate(-1))
  expect(screen.getByRole('dialog', { name: '저장하지 않은 입력이 있습니다' })).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '돌아가기' }))
  await act(() => router.navigate('/contracts/new?month=2026-08'))
  expect(screen.getByRole('dialog', { name: '저장하지 않은 입력이 있습니다' })).toBeInTheDocument()
  const event = new Event('beforeunload', { cancelable: true })
  fireEvent(window, event)
  expect(event.defaultPrevented).toBe(true)
  await userEvent.click(screen.getByRole('button', { name: '돌아가기' }))
  expect(router.state.location.search).toBe('?month=2026-07')
})
