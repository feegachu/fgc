import { useState } from 'react'
import { useUnsavedWork, useUnsavedWorkStore } from '../../../stores/unsavedWork'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { apiClient } from '../../../lib/api/client'
import * as evidenceApi from './api'
import type { Evidence } from './state'
import { SettlementGuide } from './SettlementGuide'
import { openSettlementGuide, closeSettlementGuide } from './entry'
function DirtyInput({ label }: { label: string }) {
  const [value, setValue] = useState('')
  useUnsavedWork(value !== '')
  return <input aria-label={label} value={value} onChange={(event) => setValue(event.target.value)} />
}
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
  useUnsavedWorkStore.setState({ owners: {} })
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
        <SettlementGuide
          user={{ ...user, roleCode: role, canProcess: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(role) }}
          month="2026-07"
        />
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
              <DirtyInput label="계약 입력" />
            </form>
          ),
        },
        { path: '/contracts/1', element: <section data-guide-target="contract.schedules">스케줄 없음</section> },
        { path: '/contracts/9', element: <section>다른 계약</section> },
        {
          path: '/schedules/2',
          element: (
            <section data-guide-target="schedule.lines">
              실제 스케줄
              <form>
                <DirtyInput label="스케줄 메모" />
              </form>
            </section>
          ),
        },
        { path: '/exceptions', element: <section data-guide-target="exception.action">예외 처리</section> },
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
  it('shows a business message instead of the browser text when the evidence read times out', async () => {
    vi.spyOn(evidenceApi, 'readEvidence').mockRejectedValue(new DOMException('signal timed out', 'TimeoutError'))
    const { actor } = mount()
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('5초 안에 끝나지 않았습니다')
    expect(alert).not.toHaveTextContent('signal timed out')
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

describe('unsaved input and changed targets', () => {
  it('cancels the guide move while a form has unsaved input and keeps the current step', async () => {
    const { actor, router } = mount()
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.type(screen.getByLabelText(/기존 계약번호/), 'C1')
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '스케줄 확인 완료' })).toBeEnabled())
    await actor.type(screen.getByLabelText('스케줄 메모'), '저장 전 메모')
    await actor.click(screen.getByRole('button', { name: '펼치기' }))
    await actor.click(screen.getByRole('button', { name: '스케줄 확인 완료' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('안내 이동은 취소했습니다')
    expect(router.state.location.pathname).toBe('/schedules/2')
    expect(screen.getByLabelText('스케줄 메모')).toHaveValue('저장 전 메모')
    const card = screen.getByRole('complementary', { name: '전체 업무 가이드' })
    expect(card).toHaveTextContent('2/9')
    expect(card).not.toHaveTextContent('3/9')
  })
  it('cancels a guide shortcut while a form has unsaved input', async () => {
    vi.mocked(apiClient.request).mockResolvedValue({
      data: { commissionTransactionId: 3, attributions: [{ contractId: 1 }] },
      error: null,
      requestId: 'test',
    } as never)
    vi.spyOn(evidenceApi, 'readEvidence').mockResolvedValue({
      contract: { contractId: 1, contractNo: 'C1' },
      schedule: {
        header: {
          scheduleHeaderId: 2,
          contractNo: 'C1',
          paymentStage: 'GA_TO_FC',
          activeYn: true,
          scheduleRegime: 'CURRENT',
          schedulePurpose: 'OPERATIONAL',
        },
        lines: [{ lineNo: 1 }],
      },
      payment: {
        commissionTransactionId: 3,
        status: 'CONFIRMED',
        settlementMonth: '2026-07-01',
        paymentStage: 'GA_TO_FC',
        attributions: [{ contractId: 1 }],
      },
      scheduleAcknowledged: true,
    } satisfies Evidence)
    const { actor, router } = mount('GA_ADMIN')
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.type(screen.getByLabelText(/기존 지급 번호/), '3')
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('인계')
    await act(async () => {
      await router.navigate('/contracts/new')
    })
    await actor.type(await screen.findByLabelText('계약 입력'), '저장 전 값')
    await actor.click(screen.getByRole('button', { name: '펼치기' }))
    const card = screen.getByRole('complementary', { name: '전체 업무 가이드' })
    expect(card).toHaveTextContent('6/9')
    await actor.click(screen.getByRole('button', { name: '월 검증 결과 선택' }))
    expect(screen.getByRole('alert')).toHaveTextContent('이동을 취소했습니다')
    expect(card).toHaveTextContent('6/9')
    expect(router.state.location.pathname).toBe('/contracts/new')
    expect(screen.getByLabelText('계약 입력')).toHaveValue('저장 전 값')
  })
  it('stops when the screen shows a different contract than the guided one', async () => {
    const { actor, router } = mount()
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.type(screen.getByLabelText(/기존 계약번호/), 'C1')
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '스케줄 확인 완료' })).toBeEnabled())
    await act(async () => {
      await router.navigate('/contracts/9')
    })
    await actor.click(screen.getByRole('button', { name: '현재 결과로 이어서 안내' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('다른 계약을 선택했습니다')
    expect(vi.mocked(apiClient.request).mock.calls.some(([path]) => String(path).startsWith('/api/v1/contracts/9'))).toBe(
      false,
    )
  })
})

describe('role handoff in later stages', () => {
  it.each([
    ['GA_ADMIN', 'validation.execute', true],
    ['COMPLIANCE', 'validation.execute', true],
    ['COMPLIANCE', 'exception.resolve', true],
    ['GA_ADMIN', 'exception.resolve', false],
  ] as const)('%s reaching %s has handoff=%s', async (role, step, handoff) => {
    const cancelled = step === 'exception.resolve'
    vi.mocked(apiClient.request).mockResolvedValue({
      data: { commissionTransactionId: 3, attributions: [{ contractId: 1 }] },
      error: null,
      requestId: 'test',
    } as never)
    vi.spyOn(evidenceApi, 'readEvidence').mockResolvedValue({
      contract: { contractId: 1, contractNo: 'C1' },
      schedule: {
        header: {
          scheduleHeaderId: 2,
          contractNo: 'C1',
          paymentStage: 'GA_TO_FC',
          activeYn: true,
          scheduleRegime: 'CURRENT',
          schedulePurpose: 'OPERATIONAL',
        },
        lines: [{ lineNo: 1 }],
      },
      payment: {
        commissionTransactionId: 3,
        status: cancelled ? 'CANCELLED' : 'CONFIRMED',
        settlementMonth: '2026-07-01',
        paymentStage: 'GA_TO_FC',
        attributions: [{ contractId: 1 }],
      },
      scheduleAcknowledged: true,
    } satisfies Evidence)
    const { actor } = mount(role)
    await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
    await actor.type(screen.getByLabelText(/기존 지급 번호/), '3')
    await actor.click(screen.getByRole('button', { name: '안내 시작' }))
    if (cancelled) {
      await waitFor(() => expect(screen.getByRole('button', { name: '스케줄 확인 완료' })).toBeEnabled())
      await actor.click(screen.getByRole('button', { name: '스케줄 확인 완료' }))
    }
    if (handoff) {
      expect(await screen.findByRole('alert')).toHaveTextContent('인계')
      expect(screen.queryByLabelText('정정 후 지급 번호')).not.toBeInTheDocument()
    } else {
      expect(await screen.findByLabelText('정정 후 지급 번호')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    }
    expect(
      vi.mocked(apiClient.request).mock.calls.every(([, options]) => !options?.method || options.method === 'GET'),
    ).toBe(true)
  })
})

it('uses shared unsaved state for portal inputs and permits navigation after reverting a form', async () => {
  const { actor, router } = mount()
  await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
  await actor.type(screen.getByLabelText(/기존 계약번호/), 'C1')
  await actor.click(screen.getByRole('button', { name: '안내 시작' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '스케줄 확인 완료' })).toBeEnabled())
  act(() => useUnsavedWorkStore.getState().mark('portal-dialog', true))
  await actor.click(screen.getByRole('button', { name: '스케줄 확인 완료' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('안내 이동은 취소했습니다')
  expect(router.state.location.pathname).toBe('/schedules/2')
  act(() => useUnsavedWorkStore.getState().mark('portal-dialog', false))
  await actor.type(screen.getByLabelText('스케줄 메모'), '수정했다 복원')
  await actor.clear(screen.getByLabelText('스케줄 메모'))
  await actor.click(screen.getByRole('button', { name: '펼치기' }))
  await actor.click(screen.getByRole('button', { name: '스케줄 확인 완료' }))
  await screen.findByText('지급 입력')
  expect(router.state.location.pathname).toBe('/transactions/new')
})

it.each([
  ['/contracts/1?month=2026-07', true],
  ['/contracts/1?month=2026-08', false],
  ['/contracts/9?month=2026-07', false],
] as const)('handles location changes during evidence lookup: %s', async (destination, allowed) => {
  const { actor, router } = mount()
  await actor.click(screen.getByRole('button', { name: '전체 업무 가이드' }))
  await actor.click(screen.getByRole('button', { name: '안내 시작' }))
  await screen.findByLabelText('계약 입력')
  await waitFor(() => expect(screen.getByRole('button', { name: '현재 결과로 이어서 안내' })).toBeEnabled())
  await act(async () => { await router.navigate('/contracts/1') })
  const original = evidenceApi.readEvidence
  let finish: (() => void) | undefined
  vi.spyOn(evidenceApi, 'readEvidence').mockImplementationOnce(async (...args) => {
    await new Promise<void>(resolve => { finish = resolve })
    return original(...args)
  })
  await actor.click(screen.getByRole('button', { name: '현재 결과로 이어서 안내' }))
  await waitFor(() => expect(finish).toBeDefined())
  await act(async () => { await router.navigate(destination) })
  await act(async () => { finish?.() })
  if (allowed) {
    await waitFor(() => expect(router.state.location.pathname).toBe('/schedules/2'))
    expect(screen.queryByText('조회 중 화면이 바뀌었습니다. 현재 대상으로 다시 확인하세요.')).not.toBeInTheDocument()
  } else {
    await screen.findByText('조회 중 화면이 바뀌었습니다. 현재 대상으로 다시 확인하세요.')
    expect(router.state.location.pathname + router.state.location.search).toBe(destination)
  }
})
