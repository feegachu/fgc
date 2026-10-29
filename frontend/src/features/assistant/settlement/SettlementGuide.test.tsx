import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { apiClient } from '../../../lib/api/client'
import { SettlementGuide } from './SettlementGuide'
import { openSettlementGuide, closeSettlementGuide } from './entry'
const user = {
  loginId: 'tester',
  userName: '정산 담당',
  roleCode: 'SETTLEMENT',
  canProcess: true,
  canViewAuditLog: false,
}
let scheduleRegime = 'CURRENT'
let pending: (() => void) | undefined
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  scheduleRegime = 'CURRENT'
  pending = undefined
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([
    { width: 100, height: 100 },
  ] as unknown as DOMRectList)
  vi.spyOn(apiClient, 'request').mockImplementation(async (path) => {
    if (pending)
      await new Promise<void>((resolve) => {
        pending = resolve
      })
    const header = {
      scheduleHeaderId: 2,
      contractNo: 'C1',
      paymentStage: 'GA_TO_FC',
      activeYn: true,
      scheduleRegime,
      schedulePurpose: 'OPERATIONAL',
    }
    const data = path.startsWith('/api/v1/contracts?')
      ? { content: [{ contractId: 1, contractNo: 'C1' }] }
      : path === '/api/v1/contracts/1'
        ? { contractId: 1, contractNo: 'C1' }
        : path.includes('/schedules?')
          ? { headers: [header] }
          : path === '/api/v1/schedules/2'
            ? { header, lines: [{ lineNo: 1 }] }
            : null
    if (!data) throw new Error(`Unexpected ${path}`)
    return { data, error: null, requestId: 'FLOW' } as never
  })
})
afterEach(() => {
  closeSettlementGuide()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function mount(role = 'SETTLEMENT') {
  function Shell() {
    return (
      <>
        <button onClick={openSettlementGuide}>전체 업무 가이드</button>
        <main id="main-content">
          <Outlet />
        </main>
        <SettlementGuide user={{ ...user, roleCode: role, canProcess: role !== 'COMPLIANCE' }} month="2026-07" />
      </>
    )
  }
  const router = createMemoryRouter([
    {
      element: <Shell />,
      children: [
        { path: '/', element: <h1>대시보드</h1> },
        {
          path: '/contracts/new',
          element: (
            <form data-guide-target="contract.form">
              <input aria-label="계약 입력" />
            </form>
          ),
        },
        { path: '/contracts/1', element: <section data-guide-target="contract.schedules">스케줄 없음</section> },
        { path: '/schedules/2', element: <section data-guide-target="schedule.lines">실제 스케줄</section> },
        { path: '/transactions/new', element: <section data-guide-target="payment.form">지급 입력</section> },
      ],
    },
  ])
  render(<RouterProvider router={router} />)
  return { router, actor: userEvent.setup() }
}
describe('FUN-088 shell guide', () => {
  it('opens a registered contract form without writing, preserves input on exit and restores focus', async () => {
    const { actor } = mount()
    const launch = screen.getByRole('button', { name: '전체 업무 가이드' })
    await actor.click(launch)
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    await actor.type(await screen.findByLabelText('계약 입력'), '보존할 값')
    await actor.keyboard('{Escape}')
    expect(screen.getByLabelText('계약 입력')).toHaveValue('보존할 값')
    expect(launch).toHaveFocus()
    expect(apiClient.request).not.toHaveBeenCalled()
  })
  it('reads an existing contract and requires schedule acknowledgement before the payment form', async () => {
    const { actor, router } = mount()
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.type(screen.getByLabelText(/기존 계약번호/), 'C1')
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    await screen.findByText('실제 스케줄')
    await waitFor(() => expect(screen.getByRole('button', { name: '스케줄 확인 완료' })).toBeEnabled())
    await actor.click(screen.getByRole('button', { name: '스케줄 확인 완료' }))
    await screen.findByText('지급 입력')
    expect(router.state.location.pathname).toBe('/transactions/new')
    expect(
      vi.mocked(apiClient.request).mock.calls.every(([, options]) => !options?.method || options.method === 'GET'),
    ).toBe(true)
  })
  it('refuses new business writes for the read-only role', async () => {
    const { actor, router } = mount('COMPLIANCE')
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('조회 안내만')
    expect(router.state.location.pathname).toBe('/')
  })
  it('ignores a late response after exit', async () => {
    const { actor, router } = mount()
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.type(screen.getByLabelText(/기존 계약번호/), 'C1')
    pending = () => {}
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    await actor.click(screen.getByRole('button', { name: '종료' }))
    await act(async () => {
      const resolve = pending
      pending = undefined
      resolve?.()
    })
    expect(router.state.location.pathname).toBe('/')
  })
})
