import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import { useToastStore } from '../../stores/toasts'
import { criteriaFromParams } from './api'
import type { Detail, Run } from './api'

const run: Run = {
  reconciliationRunId: 41,
  validationRunId: 3,
  settlementMonth: '2026-07-01',
  paymentStage: 'GA_TO_FC',
  paymentStageLabel: 'GA→설계사',
  insurerId: 1,
  insurerName: '테스트생명',
  status: 'COMPLETED',
  statusLabel: '계산완료',
  targetCount: 2,
  matchedCount: 1,
  exceptionCount: 1,
  matchRatePct: 50,
  differenceTotal: -1000,
  startedAt: '2026-07-01T00:00:00Z',
  completedAt: '2026-07-01T00:01:00Z',
}
const result: Detail = {
  reconciliationResultId: 7,
  reconciliationRunId: 41,
  contractId: 1,
  contractNo: 'C-001',
  commissionItemName: '초회 수수료',
  installmentNo: 1,
  expectedAgent: { agentName: '김설계', agentCode: 'A-001' },
  actualAgent: { agentName: '김설계', agentCode: 'A-001' },
  resultType: 'AMOUNT_DIFFERENCE',
  resultTypeLabel: '금액 차이',
  primaryReason: { code: 'AMOUNT_DIFF', label: '금액 불일치' },
  expectedTotalAmount: 12000,
  actualTotalAmount: 11000,
  differenceAmount: -1000,
  detailSnapshot: { paymentStage: 'GA_TO_FC' },
  matches: [
    {
      matchSeq: 1,
      matchRole: 'EXPECTED',
      scheduleHeaderId: 11,
      scheduleLineId: 12,
      matchedAmount: 12000,
      basisAmount: 1000,
      ratePct: 123.123456,
      installmentNo: 1,
      dueDate: '2026-07-01',
    },
    {
      matchSeq: 2,
      matchRole: 'ACTUAL',
      commissionTransactionId: 8,
      transactionAttributionId: 9,
      matchedAmount: 11000,
      settlementMonth: '2026-07-01',
      referenceDate: '2026-07-02',
      installmentNo: 1,
    },
  ],
}
const matched = {
  ...result,
  reconciliationResultId: 8,
  contractNo: 'C-002',
  resultType: 'MATCHED',
  resultTypeLabel: '일치',
  differenceAmount: 0,
}
const history = { content: [run], page: 1, size: 20, totalElements: 21, totalPages: 2, sort: 'createdAt,desc' }
const results = {
  summary: {
    resultCount: 2,
    matchedCount: 1,
    exceptionCount: 1,
    expectedTotal: 24000,
    actualTotal: 23000,
    differenceTotal: -1000,
  },
  items: { content: [result, matched], page: 1, size: 20, totalElements: 2, totalPages: 1, sort: 'createdAt,desc' },
}
let calls: { url: string; options?: RequestOptions }[]
type Handler = (url: string, options?: RequestOptions) => unknown
function renderAt(path = '/reconciliations', handler?: Handler, role = 'SETTLEMENT') {
  calls = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    calls.push({ url, options })
    let data: unknown
    if (url === '/api/v1/auth/me')
      data = {
        loginId: 'fixture',
        userName: '담당자',
        demoMonth: '2026-07',
        roleCode: role,
        canProcess: role === 'SETTLEMENT' || role === 'SYSTEM_ADMIN',
      }
    else {
      data = await handler?.(url, options)
      if (data === undefined) {
        if (url.startsWith('/api/v1/base/insurers'))
          data = {
            content: [{ insurerId: 1, insurerCode: 'LIFE', insurerName: '테스트생명', activeYn: true }],
            totalPages: 1,
          }
        else if (url.startsWith('/api/v1/reconciliations?')) data = history
        else if (/\/reconciliations\/\d+\/results/.test(url)) data = results
        else if (url === '/api/v1/reconciliations/results/7') data = result
        else if (url.startsWith('/api/v1/validation-runs?')) data = { content: [{ validationRunId: 3 }] }
        else throw new Error(`unexpected ${url}`)
      }
    }
    return { data, error: null, requestId: 'reco-test' }
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, client, user: userEvent.setup() }
}
beforeAll(async () => {
  await import('./ReconciliationPage')
})
beforeEach(() => {
  useAuthStore.setState({ accessToken: 'reco-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
  useToastStore.setState({ messages: [] })
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null })
})
const bodyPosts = () =>
  calls.filter((call) => call.url === '/api/v1/reconciliations' && call.options?.method === 'POST')
describe('RECO-W01·W02 React 전환', () => {
  it('BOTH 원천은 예상·실제 양쪽의 올바른 원천 ID로 표시한다', async () => {
    renderAt('/reconciliations?month=2026-07&resultId=7', (url) =>
      url.endsWith('/results/7')
        ? { ...result, matches: [{ ...result.matches![0], ...result.matches![1], matchRole: 'BOTH' }] }
        : undefined,
    )
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('예상 원천 1 · schedule_line #12')).toBeVisible()
    expect(within(dialog).getByText('실제 원천 1 · commission_transaction #8')).toBeVisible()
    expect(within(dialog).getAllByText('1개 원천')).toHaveLength(2)
    expect(within(dialog).queryByText('예상 없음')).not.toBeInTheDocument()
    expect(within(dialog).queryByText('실제 없음')).not.toBeInTheDocument()
  })
  it('이력에 없는 실행 상태는 일괄 예외 요청을 허용하지 않는다', async () => {
    renderAt('/reconciliations?month=2026-07&runId=999', (url) =>
      url.startsWith('/api/v1/reconciliations?') ? { ...history, content: [] } : undefined,
    )
    await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })
    const button = screen.getByRole('button', { name: '불일치 예외 일괄 생성' })
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(calls.some((call) => call.options?.method === 'POST')).toBe(false)
  })
  it('완료 실행의 최초 조회는 완료 전이 무효화를 하지 않는다', async () => {
    const { client } = renderAt('/reconciliations?month=2026-07&runId=41')
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })
    expect(invalidate).not.toHaveBeenCalled()
    expect(calls.filter((call) => call.url.includes('/41/results'))).toHaveLength(1)
  })
  it('캐시가 있는 완료 실행을 재선택해도 별도의 완료 무효화를 하지 않는다', async () => {
    const { client, user } = renderAt('/reconciliations?month=2026-07&runId=41', (url) =>
      url.startsWith('/api/v1/reconciliations?')
        ? { ...history, content: [run, { ...run, reconciliationRunId: 42 }] }
        : undefined,
    )
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })
    await user.click(screen.getByRole('button', { name: '42' }))
    await waitFor(() => expect(calls.some((call) => call.url.includes('/42/results'))).toBe(true))
    await user.click(screen.getByRole('button', { name: '41' }))
    await waitFor(() => expect(calls.filter((call) => call.url.includes('/41/results'))).toHaveLength(2))
    expect(invalidate).not.toHaveBeenCalled()
  })
  it('신규 실행이 첫 이력 조회 전에 완료돼도 진행 중 결과 응답 뒤 최종 결과를 받는다', async () => {
    let created = false
    let reads = 0
    let finishInitial: ((value: unknown) => void) | undefined
    const { user } = renderAt('/reconciliations?month=2026-07&stage=GA_TO_FC&insurer=1', (url, options) => {
      if (url === '/api/v1/reconciliations' && options?.method === 'POST') {
        created = true
        return { reconciliationRunId: 42, status: 'RUNNING' }
      }
      if (url.startsWith('/api/v1/reconciliations?'))
        return { ...history, content: created ? [{ ...run, reconciliationRunId: 42 }] : [] }
      if (url.includes('/42/results')) {
        if (++reads === 1)
          return new Promise((resolve) => {
            finishInitial = resolve
          })
        return results
      }
    })
    await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
    await user.click(screen.getByRole('button', { name: '대사 실행' }))
    await waitFor(() => expect(finishInitial).toBeDefined())
    finishInitial!({ ...results, items: { ...results.items, content: [] } })
    await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })
    expect(reads).toBe(2)
  })
  it('현재 페이지가 전부 일치여도 전체 요약·다음 페이지를 유지하고 페이지 합계는 0으로 구체적으로 안내한다', async () => {
    renderAt('/reconciliations?month=2026-07&runId=41&onlyMismatch=true', (url) =>
      url.includes('/41/results')
        ? { ...results, items: { ...results.items, content: [matched], totalPages: 2 } }
        : undefined,
    )
    expect(await screen.findByText('이 페이지에는 불일치가 없습니다. 다른 페이지도 확인하세요.')).toBeVisible()
    expect(
      within(screen.getByRole('region', { name: '대사 결과' })).getByRole('button', { name: '2' }),
    ).toBeEnabled()
    const footer = screen.getByRole('region', { name: '대사 결과' }).querySelector('tfoot')!
    expect(footer).toHaveTextContent('이 페이지 합계')
    expect(within(footer).getAllByText('0')).toHaveLength(3)
    expect(footer.querySelector('.reco-react-difference')).toBeNull()
    expect(screen.getByRole('region', { name: '대상' })).toHaveTextContent('2건')
    expect(screen.getByRole('region', { name: '일치율' })).toBeVisible()
  })
  it('단일 페이지가 전부 일치이면 존재하지 않는 다른 페이지를 안내하지 않는다', async () => {
    renderAt('/reconciliations?month=2026-07&runId=41&onlyMismatch=true', (url) =>
      url.includes('/41/results')
        ? { ...results, items: { ...results.items, content: [matched], totalPages: 1 } }
        : undefined,
    )
    expect(await screen.findByText('이 페이지에는 불일치가 없습니다.')).toBeVisible()
    expect(screen.queryByText('이 페이지에는 불일치가 없습니다. 다른 페이지도 확인하세요.')).not.toBeInTheDocument()
  })
  for (const action of ['조회', '대사 실행'])
    it(`다른 월 ${action}은 전역 탭 영향 확인 후 취소하면 URL·기준월·POST를 변경하지 않는다`, async () => {
      const { router, user } = renderAt('/reconciliations?month=2026-07&stage=GA_TO_FC&insurer=1')
      await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
      fireEvent.change(screen.getByLabelText(/정산월/, { selector: 'input' }), { target: { value: '2026-08' } })
      await user.click(screen.getByRole('button', { name: action }))
      const dialog = screen.getByRole('dialog', { name: '기준 정산월 변경 확인' })
      expect(within(dialog).getByText(/열린 업무 탭/)).toHaveTextContent('2026-08')
      expect(bodyPosts()).toHaveLength(0)
      await user.click(within(dialog).getByRole('button', { name: '취소' }))
      expect(router.state.location.search).toContain('month=2026-07')
      expect(useWorkspaceStore.getState().month).toBe('2026-07')
      expect(bodyPosts()).toHaveLength(0)
    })
  it('비활성 보험회사도 과거 이력을 조회할 수 있지만 새 실행은 차단한다', async () => {
    const { router, user } = renderAt('/reconciliations?month=2026-07&stage=GA_TO_FC', (url) =>
      url.startsWith('/api/v1/base/insurers')
        ? { content: [{ insurerId: 9, insurerCode: 'OLD', insurerName: '과거회사', activeYn: false }], totalPages: 1 }
        : undefined,
    )
    expect(await screen.findByRole('option', { name: 'OLD · 과거회사' })).toBeEnabled()
    await user.selectOptions(screen.getByLabelText('보험회사'), '9')
    expect(screen.getByRole('button', { name: '대사 실행' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '조회' }))
    expect(router.state.location.search).toContain('insurer=9')
    await waitFor(() => expect(calls.some((call) => call.url.includes('insurerId=9'))).toBe(true))
    expect(bodyPosts()).toHaveLength(0)
  })
  it('회차 없는 비교 항목과 원천은 대시만 표시하며 0 회차는 보존한다', async () => {
    renderAt('/reconciliations?month=2026-07&resultId=7', (url) =>
      url === '/api/v1/reconciliations/results/7'
        ? {
            ...result,
            installmentNo: null,
            matches: [
              { ...result.matches![0], installmentNo: null },
              { ...result.matches![1], installmentNo: 0 },
            ],
          }
        : undefined,
    )
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('0회차')).toBeVisible()
    expect(dialog).not.toHaveTextContent('-회차')
    expect(dialog).not.toHaveTextContent('—회차')
    expect(within(dialog).getByText('C-001 · 초회 수수료 · —')).toBeVisible()
  })
  it('이력의 단계·보험회사·정산월·page/size/sort 조건 및 표시 규칙을 유지한다', async () => {
    const { router, user } = renderAt('/reconciliations?stage=GA_TO_FC&insurer=1')
    expect(await screen.findByRole('button', { name: '41' })).toBeVisible()
    // AppShell의 최초 정산월 URL 정규화가 끝난 다음 사용자의 페이지 이동을 시작한다.
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('month')).toBe('2026-07'))
    const list = calls.find((call) => call.url.startsWith('/api/v1/reconciliations?'))!
    expect(Object.fromEntries(new URLSearchParams(list.url.split('?')[1]))).toEqual({
      month: '2026-07',
      stage: 'GA_TO_FC',
      insurerId: '1',
      page: '1',
      size: '20',
      sort: 'createdAt,desc',
    })
    expect(screen.getByText('50.0%')).toBeVisible()
    expect(screen.getByText('(1,000)원')).toHaveClass('is-negative-amount')
    const pagination = within(screen.getByRole('region', { name: '실행 이력' }))
    await user.click(pagination.getByRole('button', { name: '2' }))
    await waitFor(() => expect(router.state.location.search).toContain('page=2'))
    await waitFor(() => expect(calls.some((call) => call.url.includes('page=2'))).toBe(true))
  })
  it('결과와 이력은 공통 숫자·5페이지 묶음으로 독립적으로 이동한다', async () => {
    const { router, user } = renderAt(
      '/reconciliations?month=2026-07&stage=GA_TO_FC&insurer=1&runId=41&runMonth=2026-07&resultType=AMOUNT_DIFFERENCE&onlyMismatch=true&page=4&resultPage=4',
      (url) => {
        const page = Number(new URLSearchParams(url.split('?')[1]).get('page'))
        if (url.startsWith('/api/v1/reconciliations?')) return { ...history, page, totalPages: 12 }
        if (url.includes('/41/results')) return { ...results, items: { ...results.items, page, totalPages: 12 } }
      },
    )
    const resultNav = () => within(screen.getByRole('navigation', { name: '대사 결과 페이지 이동' }))
    const historyNav = () => within(screen.getByRole('navigation', { name: '실행 이력 페이지 이동' }))
    const current = (name: string) => screen.getByRole('navigation', { name }).querySelector('[aria-current="page"]')
    await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })
    for (const controls of [resultNav, historyNav]) {
      expect(controls().getByText('4')).toHaveAttribute('aria-current', 'page')
      expect(controls().getByRole('button', { name: '이전 5페이지' })).toBeDisabled()
      expect(controls().queryByText('4 / 12')).toBeNull()
    }
    for (const [name, controls, otherKey] of [
      ['대사 결과 페이지 이동', resultNav, 'page'],
      ['실행 이력 페이지 이동', historyNav, 'resultPage'],
    ] as const) {
      await user.click(controls().getByRole('button', { name: '다음 5페이지' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('6'))
      expect(new URLSearchParams(router.state.location.search).get(otherKey)).toBe('4')
      await user.click(controls().getByRole('button', { name: '이전 5페이지' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('5'))
      await user.click(controls().getByRole('button', { name: '다음 5페이지' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('6'))
      await user.click(controls().getByRole('button', { name: '다음 5페이지' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('11'))
      expect(controls().getByRole('button', { name: '다음 5페이지' })).toBeDisabled()
      await user.click(controls().getByRole('button', { name: '12' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('12'))
      await user.click(controls().getByRole('button', { name: '이전 5페이지' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('10'))
      await user.click(controls().getByRole('button', { name: '이전 5페이지' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('5'))
      await user.click(controls().getByRole('button', { name: '4' }))
      await waitFor(() => expect(current(name)).toHaveTextContent('4'))
    }
    const params = new URLSearchParams(router.state.location.search)
    for (const [key, value] of Object.entries({
      month: '2026-07', stage: 'GA_TO_FC', insurer: '1', runId: '41',
      runMonth: '2026-07', resultType: 'AMOUNT_DIFFERENCE', onlyMismatch: 'true',
    })) expect(params.get(key)).toBe(value)
    const resultCall = calls.filter((call) => call.url.includes('/41/results')).at(-1)!
    expect(new URLSearchParams(resultCall.url.split('?')[1]).get('resultType')).toBe('AMOUNT_DIFFERENCE')
  })
  it.each([0, 1])('전체 %i페이지에서는 결과와 이력의 페이지 이동을 숨긴다', async (totalPages) => {
    renderAt('/reconciliations?month=2026-07&runId=41', (url) => {
      if (url.startsWith('/api/v1/reconciliations?')) return { ...history, totalPages }
      if (url.includes('/41/results')) return { ...results, items: { ...results.items, totalPages } }
    })
    await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })
    expect(screen.queryByRole('navigation', { name: '대사 결과 페이지 이동' })).toBeNull()
    expect(screen.queryByRole('navigation', { name: '실행 이력 페이지 이동' })).toBeNull()
  })
  it('조회 버튼은 초안 조건을 URL로 반영하고 이전 실행 선택을 지운다', async () => {
    const { router, user } = renderAt('/reconciliations?runId=41')
    await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
    await user.selectOptions(screen.getByLabelText('지급단계'), 'INSURER_TO_GA')
    await user.selectOptions(screen.getByLabelText('보험회사'), '1')
    await user.click(screen.getByRole('button', { name: '조회' }))
    expect(router.state.location.search).toContain('stage=INSURER_TO_GA')
    expect(router.state.location.search).not.toContain('runId=')
  })
  it('실행 전 최신 COMPLETED 검증 id를 찾고 새 실행을 자동 선택한다', async () => {
    const { router, user } = renderAt(
      '/reconciliations?month=2026-07&stage=GA_TO_FC&insurer=1&page=2',
      (url, options) =>
        url === '/api/v1/reconciliations' && options?.method === 'POST'
          ? { reconciliationRunId: 42, status: 'RUNNING' }
          : undefined,
    )
    await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
    await user.click(screen.getByRole('button', { name: '대사 실행' }))
    await waitFor(() => expect(router.state.location.search).toContain('runId=42'))
    expect(router.state.location.search).not.toContain('page=2')
    expect(bodyPosts()[0].options?.body).toEqual({
      settlementMonth: '2026-07',
      paymentStage: 'GA_TO_FC',
      insurerId: 1,
      validationRunId: 3,
    })
    expect(calls.find((call) => call.url.startsWith('/api/v1/validation-runs?'))?.url).toContain(
      'status=COMPLETED&page=1&size=1',
    )
    expect(await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })).toBeVisible()
  })
  it('실행 선택 → 결과 보기 → 비교 모달·원천·요율·관련 경로를 제공한다', async () => {
    const { router, user } = renderAt()
    await user.click(await screen.findByRole('button', { name: '41' }))
    await user.click(await screen.findByRole('button', { name: '대사 결과 7 비교 보기' }))
    const dialog = screen.getByRole('dialog')
    expect(await within(dialog).findByText('예상 원천 1 · schedule_line #12')).toBeVisible()
    expect(within(dialog).getByText('실제 원천 1 · commission_transaction #8')).toBeVisible()
    expect(within(dialog).getByText('123.1234%')).toBeVisible()
    expect(within(dialog).getByRole('link', { name: '스케줄 상세' })).toHaveAttribute(
      'href',
      '/schedules/11?month=2026-07',
    )
    expect(within(dialog).getByRole('link', { name: '관련 분개' })).toHaveAttribute('href', '/journals?month=2026-07')
    expect(router.state.location.search).toContain('resultId=7')
    await user.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(router.state.location.search).not.toContain('resultId=')
  })
  it('resultId 딥링크는 이력 선택 없이 모달을 열고 누락 원천을 명시한다', async () => {
    renderAt('/reconciliations?resultId=7', (url) =>
      url === '/api/v1/reconciliations/results/7' ? { ...result, matches: [] } : undefined,
    )
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('예상 없음')).toBeVisible()
    expect(within(dialog).getByText('실제 없음')).toBeVisible()
    expect(calls.some((call) => /\/reconciliations\/\d+\/results/.test(call.url))).toBe(false)
  })
  it('불일치 일괄 예외 생성 건수·중복 건수와 예외함 조회 링크를 표시한다', async () => {
    const { user } = renderAt('/reconciliations?runId=41', (url, options) =>
      url.endsWith('/exceptions') && options?.method === 'POST' ? { created: 2, skippedDuplicate: 1 } : undefined,
    )
    await screen.findByRole('button', { name: '41' })
    await user.click(screen.getByRole('button', { name: '불일치 예외 일괄 생성' }))
    expect((await screen.findByRole('link', { name: '예외함에서 확인' })).parentElement).toHaveTextContent(
      '예외 2건 생성, 1건은 이미 있어 건너뜀.',
    )
    expect(screen.getByRole('link', { name: '예외함에서 확인' })).toHaveAttribute(
      'href',
      '/exceptions?type=RECONCILIATION_MISMATCH&validationMonth=2026-07-01&status=ALL&month=2026-07',
    )
    expect(calls.find((call) => call.url.endsWith('/41/exceptions'))?.options?.method).toBe('POST')
  })
  it('완료 검증 실행이 없으면 1차 문구로 차단하고 대사 POST를 보내지 않는다', async () => {
    const { user } = renderAt('/reconciliations?stage=GA_TO_FC&insurer=1', (url) =>
      url.startsWith('/api/v1/validation-runs?') ? { content: [] } : undefined,
    )
    await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
    await user.click(screen.getByRole('button', { name: '대사 실행' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이 정산월의 월 통합검증이 아직 완료되지 않았습니다. 검증을 먼저 완료하세요.',
    )
    expect(bodyPosts()).toHaveLength(0)
  })
  it('중복 실행 서버 오류 코드를 변경하지 않고 표시한다', async () => {
    const { user } = renderAt('/reconciliations?stage=GA_TO_FC&insurer=1', (url, options) => {
      if (url === '/api/v1/reconciliations' && options?.method === 'POST')
        throw new ApiError(
          { code: 'FGC-RECO-002', message: '이미 같은 조건의 대사 실행이 있습니다.' },
          'trace-reco',
          409,
        )
    })
    await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
    await user.click(screen.getByRole('button', { name: '대사 실행' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이미 같은 조건의 대사 실행이 있습니다. (FGC-RECO-002 · 요청 ID: trace-reco)',
    )
  })
  for (const role of ['COMPLIANCE', 'GA_ADMIN'])
    it(`${role}는 조회만 가능하고 실행·일괄 예외 버튼이 비활성이다`, async () => {
      renderAt('/reconciliations?stage=GA_TO_FC&insurer=1&runId=41', undefined, role)
      await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
      expect(screen.getByRole('button', { name: '대사 실행' })).toBeDisabled()
      expect(screen.getByRole('button', { name: '불일치 예외 일괄 생성' })).toBeDisabled()
      expect(await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })).toBeEnabled()
    })
  it('불일치만은 현재 페이지 합계를 표시하고 실행 요약 카드와 범위를 구분한다', async () => {
    renderAt('/reconciliations?runId=41&onlyMismatch=true')
    expect(await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '대사 결과 8 비교 보기' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('불일치만')).toBeChecked()
    expect(screen.getByText(/불일치만은 현재 서버 페이지/)).toBeVisible()
    const footer = screen.getByRole('region', { name: '대사 결과' }).querySelector('tfoot')!
    expect(footer).toHaveTextContent('이 페이지 합계')
    expect(within(footer).getByText('12,000')).toBeVisible()
    expect(within(footer).getByText('11,000')).toBeVisible()
    expect(screen.getByRole('region', { name: '대상' })).toHaveTextContent('2건')
  })
  it('미선택은 0 합계, 무필터 조회는 페이지 행이 아닌 서버 실행 합계를 표시한다', async () => {
    const { user } = renderAt()
    const table = await screen.findByRole('table', { name: '선택한 대사 실행의 계약별 결과' })
    const footer = table.querySelector('tfoot')!
    expect(within(footer).getAllByText('0')).toHaveLength(3)
    expect(screen.getByRole('region', { name: '차액 합계' })).toHaveTextContent('—원')
    await user.click(await screen.findByRole('button', { name: '41' }))
    await within(footer).findByText('24,000')
    expect(within(footer).getByText('23,000')).toBeVisible()
    expect(within(footer).getByRole('rowheader')).toHaveTextContent(/^합계$/)
    expect(within(footer).getByRole('rowheader')).toHaveAttribute('colspan', '5')
  })
  it.each([
    { amount: 500.49, rowText: '500원', totalText: '1,000' },
    { amount: 500.5, rowText: '501원', totalText: '1,002' },
    { amount: -500.5, rowText: '(501)원', totalText: '(1,002)' },
  ])('결과 유형 필터는 행별 HALF_UP 표시와 합계를 일치시킨다: $amount', async ({ amount, rowText, totalText }) => {
    renderAt('/reconciliations?runId=41&resultType=AMOUNT_DIFFERENCE', (url) =>
      /\/41\/results/.test(url)
        ? {
            ...results,
            items: {
              ...results.items,
              content: [
                { ...result, expectedTotalAmount: amount },
                { ...result, reconciliationResultId: 8, expectedTotalAmount: amount },
              ],
            },
          }
        : undefined,
    )
    const footer = (await screen.findByRole('region', { name: '대사 결과' })).querySelector('tfoot')!
    expect(await within(footer).findByText(totalText)).toBeVisible()
    const body = footer.closest('table')!.querySelector('tbody')!
    expect(within(body).getAllByText(rowText)).toHaveLength(2)
    expect(footer).toHaveTextContent('이 페이지 합계')
  })
  it('조회 실패를 초기 0 합계나 정상적인 결과 없음으로 오인시키지 않는다', async () => {
    renderAt('/reconciliations?runId=41', (url) => {
      if (/\/41\/results/.test(url))
        throw new ApiError({ code: 'FGC-RECO-001', message: '조회 실패' }, 'totals-error', 400)
    })
    const region = await screen.findByRole('region', { name: '대사 결과' })
    expect(await within(region).findByRole('alert')).toHaveTextContent('조회 실패')
    expect(within(region).getByRole('status')).toHaveTextContent('대사 결과를 불러오지 못했습니다.')
    expect(within(region.querySelector('tfoot')!).getAllByText('—')).toHaveLength(3)
  })
  it('요약의 일치율은 건수 기준 한 자리이며 도움말은 요청할 때 제공한다', async () => {
    renderAt('/reconciliations?runId=41', (url) =>
      /\/41\/results/.test(url)
        ? {
            ...results,
            summary: { ...results.summary, resultCount: 3, matchedCount: 2 },
          }
        : undefined,
    )
    const rate = await screen.findByRole('region', { name: '일치율' })
    expect(await within(rate).findByText('66.7')).toBeVisible()
    const help = screen.getByLabelText('일치율 계산 안내')
    expect(help.closest('details')).not.toHaveAttribute('open')
    fireEvent.click(help)
    expect(help.closest('details')).toHaveAttribute('open')
  })
  it('결과 유형은 API로 필터링하고 결과 페이지는 이력 페이지와 독립적이다', async () => {
    const { router, user } = renderAt('/reconciliations?month=2026-07&runId=41&page=2', (url) =>
      /\/41\/results/.test(url) ? { ...results, items: { ...results.items, totalPages: 2 } } : undefined,
    )
    await screen.findByRole('button', { name: '대사 결과 7 비교 보기' })
    await user.selectOptions(screen.getByLabelText('결과 유형'), 'AMOUNT_DIFFERENCE')
    await waitFor(() => expect(calls.some((call) => call.url.includes('resultType=AMOUNT_DIFFERENCE'))).toBe(true))
    await user.click(
      within(screen.getByRole('region', { name: '대사 결과' })).getByRole('button', { name: '2' }),
    )
    expect(router.state.location.search).toContain('resultPage=2')
    expect(new URLSearchParams(router.state.location.search).get('page')).toBe('2')
  })
  it('RUNNING 이력을 폴링하고 완료 시 최종 결과를 재조회한다', async () => {
    let reads = 0
    const { client } = renderAt('/reconciliations?runId=41', (url) =>
      url.startsWith('/api/v1/reconciliations?')
        ? {
            ...history,
            content: [
              { ...run, status: ++reads > 1 ? 'COMPLETED' : 'RUNNING', statusLabel: reads > 1 ? '계산완료' : '실행중' },
            ],
          }
        : undefined,
    )
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    expect(await screen.findByText('대사 실행 중입니다. 완료될 때까지 결과를 갱신합니다.')).toBeVisible()
    expect(screen.getByRole('button', { name: '불일치 예외 일괄 생성' })).toBeDisabled()
    await waitFor(
      () => expect(screen.queryByText('대사 실행 중입니다. 완료될 때까지 결과를 갱신합니다.')).not.toBeInTheDocument(),
      { timeout: 5000 },
    )
    expect(reads).toBeGreaterThan(1)
    await waitFor(() => expect(calls.filter((call) => call.url.includes('/41/results')).length).toBeGreaterThan(1))
    expect(invalidate.mock.calls.filter(([options]) => options?.queryKey?.[1] === 'results')).toHaveLength(1)
  }, 10000)
  it('500 상세 조회는 requestId만 표시하고 임의 id는 호출하지 않는다', async () => {
    renderAt('/reconciliations?resultId=7', (url) => {
      if (url.endsWith('/results/7'))
        throw new ApiError({ code: 'FGC-COMMON-500', message: 'private DB info' }, 'reco-500', 500)
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: reco-500')
    expect(screen.queryByText(/private DB/)).not.toBeInTheDocument()
    expect(criteriaFromParams(new URLSearchParams('stage=BAD&insurer=-1&page=oops'), '2026-07')).toMatchObject({
      stage: '',
      insurer: '',
      page: 1,
    })
  })
  it('다른 정산월의 선택 실행은 결과 및 일괄 처리 대상으로 사용하지 않는다', async () => {
    renderAt('/reconciliations?month=2026-08&runId=41&runMonth=2026-07')
    expect(await screen.findByText('대사를 실행하거나 이력을 선택하면 결과가 여기 표시됩니다.')).toBeVisible()
    expect(calls.some((call) => call.url.includes('/41/results'))).toBe(false)
    expect(screen.getByRole('button', { name: '불일치 예외 일괄 생성' })).toBeDisabled()
  })
  it('다른 월 실행 초안은 POST 조건을 유지한 채 새 실행 월로 자동 선택한다', async () => {
    const { router, user } = renderAt('/reconciliations?stage=GA_TO_FC&insurer=1', (url, options) =>
      url === '/api/v1/reconciliations' && options?.method === 'POST' ? { reconciliationRunId: 42 } : undefined,
    )
    await screen.findByRole('option', { name: 'LIFE · 테스트생명' })
    fireEvent.change(screen.getByLabelText(/정산월/, { selector: 'input' }), { target: { value: '2026-08' } })
    await user.click(screen.getByRole('button', { name: '대사 실행' }))
    await user.click(screen.getByRole('button', { name: '변경하고 실행' }))
    await waitFor(() => expect(router.state.location.search).toContain('runMonth=2026-08'))
    expect(bodyPosts()[0].options?.body).toMatchObject({ settlementMonth: '2026-08' })
    await waitFor(() => expect(calls.some((call) => call.url.includes('/42/results'))).toBe(true))
  })
})
