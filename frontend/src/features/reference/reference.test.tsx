import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import { BasePage } from '../base/BasePage'
import { getAllBaseOptions } from '../base/api'
import { PoliciesPage } from '../policies/PoliciesPage'
import type { Policy, PolicyDetail } from '../policies/api'
const policies: Policy[] = [
  {
    policyVersionId: 1,
    policyCode: 'POL-1',
    policyName: '규제 정책',
    versionNo: 1,
    sourceClass: 'REGULATORY',
    sourceClassLabel: '규제',
    status: 'ACTIVE',
    statusLabel: '적용중',
    regulationRefs: ['REG-08'],
  },
  {
    policyVersionId: 2,
    policyCode: 'POL-2',
    policyName: '가정 정책',
    versionNo: 2,
    sourceClass: 'PROJECT_ASSUMPTION',
    sourceClassLabel: '프로젝트 가정',
    regulationRefs: [],
  },
]
const detail: PolicyDetail = {
  header: policies[0],
  sourceRefs: ['SRC-01'],
  commissionRules: [
    {
      commissionRuleId: 1,
      paymentStageLabel: 'GA→설계사',
      ratePct: '650.123456',
      installmentFrom: 1,
      installmentTo: 12,
      fixedAmount: -1234,
    },
  ],
  capRuleSets: [
    {
      capRuleSetId: 1,
      paymentStageLabel: 'GA→설계사',
      warningUsagePct: '89.123456',
      premiumMultiplier: '12.0000',
      items: [
        {
          capRuleItemId: 1,
          itemName: '기본수수료',
          inclusionStatus: 'REVIEW_REQUIRED',
          decisionReason: '증빙 확인',
          evidenceRequiredYn: true,
        },
      ],
    },
  ],
  refundRateTables: [
    {
      refundRateTableId: 1,
      productName: '보험상품',
      standardDeduction80Yn: true,
      lines: [{ contractMonthNo: 12, refundRatePct: '34.567899' }],
    },
  ],
}
const envelope = (data: unknown) => ({ data, error: null, requestId: 'request-406' })
const page = { page: 1, size: 20, totalPages: 2, totalElements: 21 }
function fixtures(path: string) {
  const url = new URL(path, 'http://test')
  if (url.pathname === '/api/v1/policies') return envelope(policies)
  if (url.pathname.startsWith('/api/v1/policies/')) return envelope(detail)
  if (url.pathname.endsWith('/organizations'))
    return envelope({
      ...page,
      totalPages: url.searchParams.get('size') === '100' ? 1 : 2,
      page: Number(url.searchParams.get('page') ?? 1),
      content: [{ organizationId: 1, organizationName: '서울지사', organizationCode: 'BR-1', activeYn: false }],
    })
  if (url.pathname.endsWith('/insurers'))
    return envelope({ ...page, totalPages: 1, content: [{ insurerId: 1, insurerName: '가상생명', activeYn: false }] })
  if (url.pathname.endsWith('/products'))
    return envelope({ ...page, content: [{ productOfferingId: 1, productName: '보험상품' }] })
  if (url.pathname.endsWith('/agents'))
    return envelope({ ...page, content: [{ agentId: 1, agentName: '김설계', priorThreeYearExperienceYn: null }] })
  return envelope([{ commissionItemId: 1, itemName: '기본수수료', itemCategory: 'SALES', cashflowType: 'PAYMENT' }])
}
function mount(path: string, responder = fixtures, month = '2026-08') {
  const request = vi.spyOn(apiClient, 'request').mockImplementation(async (url) => responder(url) as never)
  const router = createMemoryRouter(
    [
      {
        element: <Outlet context={{ month }} />,
        children: [
          { path: '/base', element: <BasePage /> },
          { path: '/policies', element: <PoliciesPage /> },
        ],
      },
    ],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, request }
}
afterEach(() => vi.restoreAllMocks())
it('BASE restores keyword/date/page URL, resets page on search and restores tab filters', async () => {
  const user = userEvent.setup()
  const { router, request } = mount('/base?month=2026-08&tab=organization&keyword=서울&asOf=2026-07-01&page=2')
  await screen.findByText('서울지사')
  expect(screen.getByLabelText('조직 검색')).toHaveValue('서울')
  expect(screen.getByLabelText(/기준일/)).toHaveValue('2026-07-01')
  expect(request.mock.calls.some(([p]) => p.includes('page=2') && p.includes('asOf=2026-07-01'))).toBe(true)
  await user.click(screen.getByRole('button', { name: '조회' }))
  await waitFor(() => expect(router.state.location.search).not.toContain('page='))
  await user.click(screen.getByRole('tab', { name: '보험회사' }))
  await screen.findByText('가상생명')
  expect(router.state.location.search).not.toContain('keyword=')
  await user.click(screen.getByRole('tab', { name: '조직' }))
  expect(screen.getByLabelText('조직 검색')).toHaveValue('서울')
  expect(router.state.location.search).toContain('month=2026-08')
})
it('BASE insurer page navigation uses server page metadata', async () => {
  const user = userEvent.setup()
  const { request } = mount('/base?tab=organization')
  await screen.findByText('서울지사')
  await user.click(screen.getByRole('button', { name: '2' }))
  await waitFor(() => expect(request.mock.calls.some(([p]) => p.includes('page=2'))).toBe(true))
})
it('BASE five tabs, required product selection, dropdowns and item categories', async () => {
  const user = userEvent.setup()
  const { request } = mount('/base')
  await screen.findByText('서울지사')
  expect(screen.getByLabelText(/기준일/)).toHaveValue('2026-08-01')
  await user.click(screen.getByRole('tab', { name: '상품' }))
  await screen.findByRole('option', { name: '가상생명 · 사용중지' })
  expect(request.mock.calls.some(([p]) => p.includes('/products'))).toBe(false)
  await user.selectOptions(screen.getByLabelText(/보험회사/), '1')
  await user.click(screen.getByRole('button', { name: '조회' }))
  await screen.findByText('보험상품')
  await user.click(screen.getByRole('tab', { name: '설계사' }))
  await screen.findByText('김설계')
  await screen.findByRole('option', { name: '서울지사 · 사용중지' })
  expect(screen.getByText('확인 전')).toBeVisible()
  await user.click(screen.getByRole('tab', { name: '수수료 항목' }))
  await screen.findByText('기본수수료')
  expect(screen.getByText('모집수수료')).toBeVisible()
  expect(screen.queryByRole('navigation', { name: '페이지 선택' })).not.toBeInTheDocument()
})
it.each(['organization', 'insurer', 'product', 'agent', 'commission-item'])(
  'BASE %s supports empty 200',
  async (tab) => {
    mount(`/base?tab=${tab}&insurerId=1`, (p) =>
      p.includes('commission-items')
        ? envelope([])
        : envelope({ ...page, content: [], totalPages: 0, totalElements: 0 }),
    )
    expect(await screen.findByText('조건에 맞는 자료가 없습니다.')).toBeVisible()
  },
)
it('POL uses month first day and fetches detail lazily; preserves selection across all four tabs', async () => {
  const user = userEvent.setup()
  const { router, request } = mount('/policies?month=2026-08')
  await screen.findByText('규제 정책')
  expect(screen.getByLabelText(/기준일/)).toHaveValue('2026-08-01')
  expect(request.mock.calls.some(([p]) => /policies\/\d/.test(p))).toBe(false)
  expect(screen.getByText('근거 미기재').closest('tr')).toHaveClass('reference-missing-evidence')
  expect(screen.getByText('프로젝트 가정')).toHaveClass('status-badge-warning')
  await user.click(screen.getByRole('tab', { name: '수수료 규칙' }))
  await screen.findByText('650.1234')
  expect(screen.getByText('1 ~ 12회차')).toBeVisible()
  expect(screen.getByText('(1,234)원')).toHaveClass('is-negative-amount')
  await user.click(screen.getByRole('tab', { name: '1,200% 룰셋' }))
  await screen.findByText('89.1234')
  expect(screen.getByText('증빙필수')).toBeVisible()
  await user.click(screen.getByRole('tab', { name: '예상 해약환급률표' }))
  await screen.findByText('34.5678')
  expect(screen.getByText('1,200% 한도 가산에 쓰는 값')).toBeVisible()
  expect(router.state.location.search).toContain('policyVersionId=1')
  await user.click(screen.getByRole('tab', { name: '정책 버전' }))
  await user.click(screen.getByRole('radio', { name: 'POL-2 v2 선택' }))
  expect(router.state.location.search).toContain('policyVersionId=2')
  await user.click(screen.getByRole('button', { name: 'REG-08' }))
  expect(screen.getByRole('tooltip')).toHaveTextContent('REG-08')
  expect(screen.getByRole('radio', { name: 'POL-2 v2 선택' })).toBeChecked()
})
it('POL restores detail tab and selected id from a shared URL', async () => {
  const { request } = mount('/policies?asOf=2026-07-11&tab=refund&policyVersionId=2')
  await screen.findByText('34.5678')
  expect(screen.getByRole('tab', { name: '예상 해약환급률표' })).toHaveAttribute('aria-selected', 'true')
  expect(request.mock.calls.some(([p]) => p === '/api/v1/policies/2')).toBe(true)
})
it('POL asOf changes query and clears selected id while retaining month/tab', async () => {
  const { router, request } = mount('/policies?month=2026-08&tab=cap&policyVersionId=2')
  await screen.findByText('89.1234')
  const { fireEvent } = await import('@testing-library/react')
  fireEvent.change(screen.getByLabelText(/기준일/), { target: { value: '2026-09-01' } })
  await waitFor(() => expect(request.mock.calls.some(([p]) => p === '/api/v1/policies?asOf=2026-09-01')).toBe(true))
  expect(router.state.location.search).not.toContain('policyVersionId=')
  expect(router.state.location.search).toContain('month=2026-08')
})
it('POL 404 detail does not discard version rows', async () => {
  const user = userEvent.setup()
  mount('/policies?tab=commission&policyVersionId=999', (p) => {
    if (p.endsWith('/999'))
      throw new ApiError({ code: 'FGC-POL-404', message: '정책을 찾을 수 없습니다.' }, 'trace-404', 404)
    return fixtures(p)
  })
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '정책을 찾을 수 없습니다. (FGC-POL-404 · 요청 ID: trace-404)',
  )
  await user.click(screen.getByRole('tab', { name: '정책 버전' }))
  expect(screen.getByText('규제 정책')).toBeVisible()
})
it('malformed asOf reaches the API and retains the server 400 message', async () => {
  const { request } = mount('/policies?asOf=broken', () => {
    throw new ApiError({ code: 'FGC-COMMON-400', message: '올바른 날짜 형식으로 입력하세요.' }, 'trace-400', 400)
  })
  expect(await screen.findByRole('alert')).toHaveTextContent('올바른 날짜 형식으로 입력하세요.')
  expect(request.mock.calls[0][0]).toBe('/api/v1/policies?asOf=broken')
})
it('500 only reveals request id, not exception detail or server text', async () => {
  mount('/base', () => {
    throw new ApiError({ message: 'SQL private detail', detail: { secret: 'hidden' } }, 'trace-500', 500)
  })
  expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: trace-500')
  expect(screen.queryByText(/SQL private detail/)).not.toBeInTheDocument()
})
it.each(['commission', 'cap', 'refund'])('POL %s handles empty detail', async (tab) => {
  mount(`/policies?tab=${tab}`, (p) =>
    p.includes('/policies/') ? envelope({ commissionRules: [], capRuleSets: [], refundRateTables: [] }) : fixtures(p),
  )
  expect(await screen.findByText(/이 정책에는 .* 없습니다\./)).toBeVisible()
})
it('POL empty versions and keyboard tab switching', async () => {
  const user = userEvent.setup()
  mount('/policies', () => envelope([]))
  await screen.findByText('조건에 맞는 자료가 없습니다.')
  screen.getByRole('tab', { name: '정책 버전' }).focus()
  await user.keyboard('{End}')
  expect(screen.getByRole('tab', { name: '예상 해약환급률표' })).toHaveFocus()
  expect(screen.getByText('정책 버전 탭에서 정책을 선택하세요.')).toBeVisible()
})
it('dropdown options collect all pages and forward the cancellation signal', async () => {
  const signal = new AbortController().signal
  const request = vi
    .spyOn(apiClient, 'request')
    .mockResolvedValueOnce(envelope({ ...page, content: [{ insurerId: 1 }] }) as never)
    .mockResolvedValueOnce(envelope({ ...page, content: [{ insurerId: 2 }] }) as never)
  expect(await getAllBaseOptions('insurers', {}, signal)).toHaveLength(2)
  expect(request.mock.calls[1][0]).toContain('page=2&size=100')
  expect(request.mock.calls[1][1]?.signal).toBe(signal)
  const aborted = new AbortController()
  aborted.abort()
  await expect(getAllBaseOptions('insurers', {}, aborted.signal)).rejects.toThrow()
  expect(request).toHaveBeenCalledTimes(2)
})
it('BASE preserves unsubmitted drafts per tab and reset clears drafts even when URL is unchanged', async () => {
  const user = userEvent.setup()
  mount('/base?tab=organization&asOf=2026-08-01')
  await screen.findByText('서울지사')
  await user.type(screen.getByLabelText('조직 검색'), '미조회 초안')
  await user.click(screen.getByRole('tab', { name: '보험회사' }))
  await screen.findByText('가상생명')
  await user.click(screen.getByRole('tab', { name: '조직' }))
  expect(screen.getByLabelText('조직 검색')).toHaveValue('미조회 초안')
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByLabelText('조직 검색')).toHaveValue('')
})
it.each(['organization', 'insurer', 'product', 'agent'])(
  'BASE %s restores page 2 and resets page on submitted filter changes',
  async (tab) => {
    const user = userEvent.setup()
    const { router, request } = mount(
      `/base?month=2026-08&tab=${tab}&page=2&insurerId=1&organizationId=1&asOf=2026-07-01&keyword=서울`,
    )
    await screen.findByRole('table')
    await waitFor(() => expect(request.mock.calls.some(([p]) => p.includes('page=2&size=20'))).toBe(true))
    if (tab === 'product') await screen.findByRole('option', { name: '가상생명 · 사용중지' })
    if (tab === 'agent') await screen.findByRole('option', { name: '서울지사 · 사용중지' })
    await user.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).not.toContain('page='))
    if (tab === 'insurer') expect(router.state.location.search).not.toContain('asOf=')
    if (tab === 'agent')
      expect(request.mock.calls.some(([p]) => p.includes('organizationId=1') && p.includes('keyword='))).toBe(true)
    if (tab === 'product')
      expect(request.mock.calls.some(([p]) => p.includes('insurerId=1') && p.includes('asOf=2026-07-01'))).toBe(true)
  },
)
it('BASE commission-item queries selected date and restores it from URL', async () => {
  const { request } = mount('/base?tab=commission-item&asOf=2026-09-17')
  await screen.findByText('기본수수료')
  expect(screen.getByLabelText(/기준일/)).toHaveValue('2026-09-17')
  expect(request.mock.calls.some(([p]) => p === '/api/v1/base/commission-items?asOf=2026-09-17')).toBe(true)
})
it('POL list failure does not load or reveal a selected detail from an invalid date URL', async () => {
  const { request } = mount('/policies?asOf=broken&tab=commission&policyVersionId=1', (p) => {
    if (p.includes('?asOf=broken'))
      throw new ApiError({ code: 'FGC-COMMON-400', message: '올바른 날짜 형식으로 입력하세요.' }, 'invalid-date', 400)
    return fixtures(p)
  })
  await screen.findByRole('alert')
  expect(request.mock.calls.some(([p]) => p === '/api/v1/policies/1')).toBe(false)
  expect(screen.queryByText('650.1234')).not.toBeInTheDocument()
})
