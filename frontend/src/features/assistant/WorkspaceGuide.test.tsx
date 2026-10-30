import { StrictMode, useState } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryRouter, Outlet, RouterProvider, useLocation } from 'react-router'
import { workflowHelpSteps } from './workflow-catalog'
import { WorkspaceGuide } from './WorkspaceGuide'
import { useWorkflowGuide } from './workflow-state'
import { useWorkspaceStore } from '../../stores/workspace'
import { DraftNavigationGuard } from '../../app/shell/DraftNavigationGuard'
import { useUnsavedWork, useUnsavedWorkStore } from '../../stores/unsavedWork'
import { screenFor } from '../../app/screens'

const user = { loginId: 'test', userName: '담당자', roleCode: 'SETTLEMENT', canProcess: true, canViewAuditLog: false }
function ContractFields() {
  const [value, setValue] = useState('')
  useUnsavedWork(value !== '')
  return <>
    <input aria-label="보험회사" data-guide-feature="features.contract.contract-form-page.insurer-id" value={value} onChange={(event) => setValue(event.target.value)} />
    <input aria-label="계약번호" data-guide-feature="features.contract.contract-form-page.contract-no" />
    <input aria-label="계약일" data-guide-feature="features.contract.contract-form-page.contract-date" />
    <input data-guide-feature="features.contract.contract-form-page.product-offering-id" />
    <input data-guide-feature="features.contract.contract-form-page.agent-id" />
    <section data-guide-feature="features.contract.contract-form-page.section-2">금액</section>
    <form data-guide-feature="features.contract.contract-form-page.form" />
  </>
}
function Layout() {
  const location = useLocation()
  return <><main data-guide-page={screenFor(location.pathname)?.id} data-guide-pathname={location.pathname}><Outlet /></main><WorkspaceGuide user={user} /><DraftNavigationGuard /></>
}
function setup() {
  const router = createMemoryRouter([{ element: <Layout />, children: [
    { path: '/', element: <p>대시보드</p> },
    { path: '/contracts/new', element: <ContractFields /> },
    { path: '/schedules', element: <p>스케줄 목록</p> },
    { path: '/audit-logs', element: <p>감사로그</p> },
    { path: '/policies', element: <>
      <input data-guide-feature="features.policies.policies-page.input" type="date" />
      <input data-guide-feature="features.policies.policies-page.policy-version" type="radio" name="policy" aria-label="정책 A" />
      <input data-guide-feature="features.policies.policies-page.policy-version" type="radio" name="policy" aria-label="정책 B" />
      <button data-guide-feature="features.policies.policies-page.button-6">선택 정책 관리</button>
    </> },
  ] }])
  render(<StrictMode><RouterProvider router={router} /></StrictMode>)
  return router
}
beforeEach(() => {
  vi.stubEnv('VITE_ASSISTANT_AI', 'true')
  useWorkflowGuide.getState().clear()
  useUnsavedWorkStore.setState({ owners: {} })
  useWorkspaceStore.setState({ month: '2026-07' })
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{ left: 10, top: 10, width: 100, height: 30, right: 110, bottom: 40 }] as unknown as DOMRectList)
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} })
})
afterEach(async () => {
  cleanup()
  await Promise.resolve()
  useWorkflowGuide.getState().clear()
  useUnsavedWorkStore.setState({ owners: {} })
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})
it('uses the registered route and targets, survives StrictMode and never performs business writes', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch')
  const router = setup()
  act(() => useWorkflowGuide.getState().start('contract.register'))
  await screen.findByRole('textbox', { name: '보험회사' })
  await waitFor(() => expect(screen.getByRole('button', { name: '확인했어요 · 다음 안내' })).toBeEnabled())
  expect(router.state.location.pathname).toBe('/contracts/new')
  await userEvent.click(screen.getByRole('button', { name: '확인했어요 · 다음 안내' }))
  expect(await screen.findByText(/현재 항목을 입력하거나/)).toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox', { name: '보험회사' }), '선택한 보험회사')
  await userEvent.click(screen.getByRole('button', { name: '확인했어요 · 다음 안내' }))
  expect(await screen.findByRole('heading', { name: '계약일 입력' })).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})
it('pauses after free navigation and returns to the guide without issuing a business action', async () => {
  const router = setup()
  act(() => useWorkflowGuide.getState().start('contract.register'))
  await screen.findByRole('textbox', { name: '보험회사' })
  await act(() => router.navigate('/schedules?month=2026-07'))
  expect(await screen.findByText(/다른 화면으로 이동했습니다/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '돌아와 이어가기' }))
  await screen.findByRole('textbox', { name: '보험회사' })
  expect(router.state.location.pathname).toBe('/contracts/new')
})
it('preserves dirty input when navigation is cancelled and only discards after an explicit choice', async () => {
  const router = setup()
  await act(() => router.navigate('/contracts/new?month=2026-07'))
  await userEvent.type(screen.getByRole('textbox', { name: '보험회사' }), '입력중')
  await act(() => router.navigate('/schedules?month=2026-07'))
  await screen.findByRole('dialog', { name: '저장하지 않은 입력이 있어요' })
  await userEvent.click(screen.getByRole('button', { name: '현재 입력 유지 · 돌아가기' }))
  expect(screen.getByRole('textbox', { name: '보험회사' })).toHaveValue('입력중')
  await act(() => router.navigate('/schedules?month=2026-07'))
  await userEvent.click(screen.getByRole('button', { name: '입력을 버리고 이동' }))
  expect(await screen.findByText('스케줄 목록')).toBeInTheDocument()
})
it('does not start a guide outside the authenticated role', async () => {
  const router = setup()
  act(() => useWorkflowGuide.getState().start('audit.review'))
  await waitFor(() => expect(useWorkflowGuide.getState().request).toBeNull())
  expect(router.state.location.pathname).toBe('/')
  expect(screen.queryByRole('region', { name: '업무 튜토리얼' })).not.toBeInTheDocument()
})
it('waits for the user to select a repeated policy row instead of guessing the first row', async () => {
  setup()
  act(() => useWorkflowGuide.getState().start('policy.review'))
  await screen.findByRole('radio', { name: '정책 B' })
  await waitFor(() => expect(screen.getByRole('button', { name: '확인했어요 · 다음 안내' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '확인했어요 · 다음 안내' }))
  expect(await screen.findByText(/같은 기능이 여러 행/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('radio', { name: '정책 B' }))
  await userEvent.click(screen.getByRole('button', { name: '돌아와 이어가기' }))
  await screen.findByRole('button', { name: '확인했어요 · 다음 안내' })
  await userEvent.click(screen.getByRole('button', { name: '확인했어요 · 다음 안내' }))
  expect(await screen.findByRole('heading', { name: '선택 정책 관리로 이동' })).toBeInTheDocument()
  expect(screen.getByRole('radio', { name: '정책 A' })).not.toBeChecked()
  expect(screen.getByRole('radio', { name: '정책 B' })).toBeChecked()
})

it('opens the exact registered source location cited by an answer without stepping through unrelated fields', async () => {
  const router = setup()
  const help = workflowHelpSteps('contract.register').find((step) => step.targetId === 'features.contract.contract-form-page.contract-no')!
  expect(help).toBeDefined()
  act(() => useWorkflowGuide.getState().start('contract.register', help.stepId))
  await screen.findByRole('textbox', { name: '계약번호' })
  expect(await screen.findByRole('heading', { name: help.title })).toBeInTheDocument()
  expect(router.state.location.pathname).toBe('/contracts/new')
  await userEvent.click(screen.getByRole('button', { name: '확인했어요 · 다음 안내' }))
  expect(await screen.findByRole('heading', { name: '안내를 마쳤어요' })).toBeInTheDocument()
  expect(screen.getByText(/실제 저장·검증·확정 결과/)).toBeInTheDocument()
})
