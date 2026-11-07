import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it } from 'vitest'
import { createMemoryRouter, Outlet, RouterProvider, useLocation } from 'react-router'
import { DraftNavigationGuard } from './DraftNavigationGuard'
import { WorkspaceTabs } from './WorkspaceTabs'
import { screenFor } from '../screens'
import { useUnsavedWork, useUnsavedWorkStore } from '../../stores/unsavedWork'
import { useWorkspaceStore } from '../../stores/workspace'
function Dirty() { useUnsavedWork(true); return <input defaultValue="미저장" aria-label="입력" /> }
function Shell() {
  const location = useLocation()
  return <><WorkspaceTabs activeId={screenFor(location.pathname)?.id} /><Outlet /><DraftNavigationGuard /></>
}
function setup() {
  useWorkspaceStore.getState().clear()
  useWorkspaceStore.setState({ month: '2026-07' })
  useWorkspaceStore.getState().openTab({ id: 'CONT-W03', title: '계약 등록', href: '/contracts/new?month=2026-07', icon: 'description' })
  const router = createMemoryRouter([{ element: <Shell />, children: [
    { path: '/', element: <p>홈</p> }, { path: '/contracts/new', element: <Dirty /> },
  ] }], { initialEntries: ['/', '/contracts/new?month=2026-07'] })
  render(<RouterProvider router={router} />)
  return router
}
afterEach(() => { useUnsavedWorkStore.setState({ owners: {} }); useWorkspaceStore.getState().clear() })
it('restores the active tab when closing it is cancelled without losing the draft', async () => {
  const router = setup()
  await userEvent.click(screen.getByRole('button', { name: '계약 등록 탭 닫기' }))
  await userEvent.click(screen.getByRole('button', { name: '현재 입력 유지하고 돌아가기' }))
  expect(router.state.location.pathname).toBe('/contracts/new')
  expect(useWorkspaceStore.getState().tabs.some((tab) => tab.id === 'CONT-W03')).toBe(true)
  expect(screen.getByRole('textbox', { name: '입력' })).toHaveValue('미저장')
})
it('guards browser back, month changes and browser unload', async () => {
  const router = setup()
  await act(() => router.navigate(-1))
  expect(screen.getByRole('dialog', { name: '저장하지 않은 입력이 있습니다' })).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '현재 입력 유지하고 돌아가기' }))
  await act(() => router.navigate('/contracts/new?month=2026-08'))
  expect(screen.getByRole('dialog', { name: '저장하지 않은 입력이 있습니다' })).toBeInTheDocument()
  const event = new Event('beforeunload', { cancelable: true })
  fireEvent(window, event)
  expect(event.defaultPrevented).toBe(true)
  await userEvent.click(screen.getByRole('button', { name: '현재 입력 유지하고 돌아가기' }))
  expect(router.state.location.search).toBe('?month=2026-07')
})
