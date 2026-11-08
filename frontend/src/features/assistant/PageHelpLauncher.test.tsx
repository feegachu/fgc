import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { PageHelpLauncher } from './PageHelpLauncher'
import { PAGE_HELP_TARGETS, pageHelpDestination } from './page-help-targets'
import definitions from './guide/screen-help.json'
import { useWorkflowGuide } from './workflow-state'
import { useWorkspaceStore } from '../../stores/workspace'

const user = { loginId: 'tester', userName: '담당자', roleCode: 'SETTLEMENT', canProcess: true, canViewAuditLog: false }
beforeEach(() => {
  useWorkflowGuide.setState({ request: null, active: false, paymentActive: false, settlementActive: false })
  useWorkspaceStore.setState({ month: '2026-07' })
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
})
afterEach(() => { useWorkflowGuide.getState().clear(); vi.restoreAllMocks() })

it('maps every enabled Pico workflow to a registered page Help entry', () => {
  expect(PAGE_HELP_TARGETS.length).toBeGreaterThan(20)
  for (const target of PAGE_HELP_TARGETS) {
    expect(definitions.some(definition => definition.screenId === target.screenId && !definition.variant), target.workflowId).toBe(true)
    expect(pageHelpDestination(target.workflowId, '/', '', '2026-07'), target.workflowId).not.toBeNull()
  }
})

it('uses the selected contract for detail and editing and otherwise opens the list help', () => {
  expect(pageHelpDestination('contract.edit', '/contracts/19', '?month=2026-07', '2026-07')?.pathname).toBe('/contracts/19/edit')
  expect(pageHelpDestination('schedule.detail', '/schedules/5', '?month=2026-07', '2026-07')?.pathname).toBe('/schedules/5')
  expect(pageHelpDestination('schedule.detail', '/', '', '2026-07')?.pathname).toBe('/schedules')
  expect(pageHelpDestination('payment.confirm', '/transactions/new', '?id=77&month=2026-07', '2026-07')?.href).toContain('id=77')
})

it('navigates and opens the actual page help button without executing a separate guide', async () => {
  const opened = vi.fn()
  const router = createMemoryRouter([{ element: <><main id="main-content"><Outlet /></main><PageHelpLauncher user={user} /></>, children: [
    { path: '/', element: <h1>대시보드</h1> },
    { path: '/contracts', element: <button data-screen-help="CONT-W01" data-screen-help-variant="" onClick={opened}>도움말</button> },
  ] }], { initialEntries: ['/'] })
  render(<RouterProvider router={router} />)
  act(() => useWorkflowGuide.getState().start('contract.find', 'obsolete.pico.step'))
  await waitFor(() => expect(opened).toHaveBeenCalledTimes(1))
  expect(router.state.location.pathname).toBe('/contracts')
  expect(router.state.location.search).toBe('?month=2026-07')
  expect(screen.queryByText('피코의 단계별 가이드')).not.toBeInTheDocument()
  await waitFor(() => expect(useWorkflowGuide.getState().request).toBeNull())
})
