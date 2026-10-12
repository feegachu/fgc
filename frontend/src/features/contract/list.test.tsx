import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toasts'
import { exportCsv } from '../../lib/exportCsv'
import ContractListPage from './ContractListPage'

vi.mock('../../lib/exportCsv', () => ({ exportCsv: vi.fn().mockResolvedValue(undefined) }))
const envelope = (data: unknown) => ({ data, error: null, requestId: 'contract-list-test' })
const pageData = (content: unknown[], totalPages = 1) => ({
  content,
  totalPages,
  totalElements: content.length,
  page: 1,
  size: 20,
})
const row = {
  contractId: 1,
  contractNo: 'C-2026-001',
  insurerName: '한빛생명',
  productName: '종신보험',
  contractDate: '2026-07-01',
  monthlyEquivalentFirstPremium: 100000,
  contractStatus: 'ACTIVE',
  contractStatusLabel: '서버 계약 정상',
  capResultStatus: 'NORMAL',
  capResultStatusLabel: '정상',
  dataOrigin: 'MANUAL',
  dataOriginLabel: '수동 입력',
}
const requests: string[] = []
function mount(path = '/contracts', canProcess = true, list?: (url: URL) => unknown) {
  vi.spyOn(apiClient, 'request').mockImplementation(async (path) => {
    requests.push(path)
    const url = new URL(path, 'http://localhost')
    if (url.pathname === '/api/v1/auth/me') return envelope({ canProcess })
    if (url.pathname === '/api/v1/contracts') return envelope(list ? await list(url) : pageData([row], 2))
    if (url.pathname.endsWith('/insurers'))
      return envelope(
        pageData([
          { insurerId: 1, insurerCode: 'A', insurerName: '한빛생명' },
          { insurerId: 2, insurerCode: 'B', insurerName: '새빛생명' },
        ]),
      )
    if (url.pathname.endsWith('/organizations'))
      return envelope(pageData([{ organizationId: 3, organizationCode: 'O', organizationName: '서울' }]))
    if (url.pathname.endsWith('/products'))
      return envelope(
        pageData([
          {
            productOfferingId: url.searchParams.get('insurerId') === '1' ? 11 : 22,
            productName: '종신보험',
            offeringVersion: 'v1',
          },
        ]),
      )
    if (url.pathname.endsWith('/agents')) {
      const agents = [
        { agentId: 4, agentCode: 'FC4', agentName: '김설계', agentStatus: 'ACTIVE', activeYn: true },
        { agentId: 5, agentCode: 'FC5', agentName: '비활동 설계사', agentStatus: 'INACTIVE', activeYn: true },
        { agentId: 6, agentCode: 'FC6', agentName: '사용중지 설계사', agentStatus: 'ACTIVE', activeYn: false },
      ]
      // 실제 API도 이 플래그 없이는 해촉일이 지난 설계사를 제외한다.
      if (url.searchParams.get('includeTerminated') === 'true')
        agents.push({
          agentId: 7,
          agentCode: 'FC7',
          agentName: '퇴사 설계사',
          agentStatus: 'TERMINATED',
          activeYn: false,
        })
      return envelope(pageData(agents))
    }
    throw new Error(`Unexpected request: ${path}`)
  })
  const router = createMemoryRouter(
    [
      { path: '/contracts', element: <ContractListPage /> },
      { path: '/contracts/:id', element: <p>상세 이동</p> },
    ],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, user: userEvent.setup() }
}
beforeEach(() => {
  requests.length = 0
  useToastStore.setState({ messages: [] })
  useAuthStore.setState({ accessToken: 'fixture', loggingOut: false })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  useAuthStore.setState({ accessToken: null })
})

it('URL 필터 복원, 서버 라벨·금액, 페이지 이동, CSV에 동일한 검색 조건을 유지한다', async () => {
  const { router, user } = mount(
    '/contracts?insurerId=1&productOfferingId=11&contractNo=C-2026&currentStatus=ACTIVE&month=2026-07',
  )
  const table = await screen.findByRole('table', { name: '보험계약 검색 결과' })
  expect(within(table).getByText('100,000원')).toBeInTheDocument()
  expect(within(table).getByText('서버 계약 정상')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByLabelText('상품')).toHaveValue('11'))
  await user.click(screen.getByRole('button', { name: '2' }))
  await waitFor(() =>
    expect(requests.some((path) => path.includes('page=2') && path.includes('productOfferingId=11'))).toBe(true),
  )
  expect(router.state.location.search).toContain('month=2026-07')
  await user.click(screen.getByRole('button', { name: 'CSV 내보내기' }))
  expect(exportCsv).toHaveBeenCalledWith(
    '/api/v1/contracts/export.csv?contractNo=C-2026&insurerId=1&productOfferingId=11&currentStatus=ACTIVE',
    { filename: 'contracts.csv', notifyError: false },
  )
})

it.each([
  [500, 'FGC-COMMON-500', 'internal CSV SQL detail', '요청 ID: csv-request'],
  [
    400,
    'FGC-COMMON-001',
    '검색 조건을 다시 확인해 주세요.',
    '검색 조건을 다시 확인해 주세요. (FGC-COMMON-001 · 요청 ID: csv-request)',
  ],
] as const)(
  'CSV 오류 %i는 서버 메시지 또는 안전한 요청 ID를 한 번 표시한다',
  async (status, code, message, expected) => {
    vi.mocked(exportCsv).mockRejectedValueOnce(new ApiError({ code, message }, 'csv-request', status))
    const { user } = mount()
    await screen.findByRole('table', { name: '보험계약 검색 결과' })
    await user.click(screen.getByRole('button', { name: 'CSV 내보내기' }))
    await waitFor(() => expect(useToastStore.getState().messages).toHaveLength(1))
    expect(useToastStore.getState().messages[0].message).toBe(expected)
  },
)

it('보험사를 바꾸면 이전 상품을 지우고 조회 시 페이지를 초기화한다', async () => {
  const { router, user } = mount('/contracts?insurerId=1&productOfferingId=11&page=2')
  await waitFor(() => expect(screen.getByLabelText('보험회사')).toBeEnabled())
  await user.selectOptions(screen.getByLabelText('보험회사'), '2')
  await waitFor(() => expect(screen.getByLabelText('상품')).toBeEnabled())
  expect(screen.getByLabelText('상품')).toHaveValue('')
  await user.click(screen.getByRole('button', { name: '조회' }))
  expect(router.state.location.search).toContain('insurerId=2')
  expect(router.state.location.search).not.toContain('productOfferingId')
  expect(router.state.location.search).not.toContain('page=')
})

it('과거 계약 검색에서는 해촉·비활동·사용중지 설계사 이름을 선택하고 검색 조건에 전달한다', async () => {
  const { router, user } = mount('/contracts?orgId=3', false)
  const terminated = await screen.findByRole('option', { name: 'FC7 · 퇴사 설계사 (해촉 · 기준정보 사용중지)' })
  expect(terminated).toBeEnabled()
  expect(screen.getByRole('option', { name: 'FC5 · 비활동 설계사 (비활동)' })).toBeEnabled()
  expect(screen.getByRole('option', { name: 'FC6 · 사용중지 설계사 (활동 · 기준정보 사용중지)' })).toBeEnabled()
  const agentRequest = requests
    .map((path) => new URL(path, 'http://localhost'))
    .find((url) => url.pathname === '/api/v1/base/agents')!
  expect(agentRequest.searchParams.get('includeTerminated')).toBe('true')
  expect(agentRequest.searchParams.get('organizationId')).toBe('3')
  await user.selectOptions(screen.getByLabelText('모집 설계사'), terminated)
  await user.click(screen.getByRole('button', { name: '조회' }))
  expect(new URLSearchParams(router.state.location.search).get('agentId')).toBe('7')
  await waitFor(() =>
    expect(
      requests.some((path) => {
        const url = new URL(path, 'http://localhost')
        return (
          url.pathname === '/api/v1/contracts' &&
          url.searchParams.get('agentId') === '7' &&
          url.searchParams.get('orgId') === '3'
        )
      }),
    ).toBe(true),
  )
})

it('해촉 설계사 URL 검색조건을 ID 대체문구 대신 이름과 상태로 복원한다', async () => {
  const { user } = mount('/contracts?agentId=7')
  await waitFor(() => expect(screen.getByLabelText('모집 설계사')).toBeEnabled())
  const agent = screen.getByLabelText('모집 설계사') as HTMLSelectElement
  expect(agent).toHaveValue('7')
  expect(agent.selectedOptions[0]).toHaveTextContent('퇴사 설계사 (해촉 · 기준정보 사용중지)')
  expect(screen.queryByRole('option', { name: '선택된 ID: 7' })).toBeNull()
  await user.click(screen.getByRole('button', { name: 'CSV 내보내기' }))
  expect(exportCsv).toHaveBeenCalledWith('/api/v1/contracts/export.csv?agentId=7', {
    filename: 'contracts.csv',
    notifyError: false,
  })
})

it('늦게 도착한 이전 보험사 응답을 적용하지 않는다', async () => {
  let resolveOld!: (data: ReturnType<typeof envelope>) => void
  const { user } = mount('/contracts?insurerId=1')
  const original = vi.mocked(apiClient.request).getMockImplementation()!
  vi.mocked(apiClient.request).mockImplementation(async (path, options) => {
    if (path.includes('/products?') && path.includes('insurerId=1'))
      return await new Promise((resolve) => {
        resolveOld = resolve
      })
    return original(path, options)
  })
  await waitFor(() => expect(screen.getByLabelText('보험회사')).toBeEnabled())
  await user.selectOptions(screen.getByLabelText('보험회사'), '2')
  await waitFor(() => expect(screen.getByLabelText('상품')).toBeEnabled())
  resolveOld?.(envelope(pageData([{ productOfferingId: 11, productName: '이전 보험사 상품', offeringVersion: 'v1' }])))
  expect(screen.queryByRole('option', { name: /이전 보험사 상품/ })).toBeNull()
  expect(screen.getByRole('option', { name: '종신보험 · v1' })).toHaveValue('22')
})

it('조회 전용 권한은 신규 버튼을 숨기고 행 링크로 상세에 진입한다', async () => {
  const { user } = mount('/contracts', false)
  await user.click(await screen.findByRole('link', { name: row.contractNo }))
  expect(await screen.findByText('상세 이동')).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '신규 등록' })).toBeNull()
})

it('목록 500 오류는 내부 메시지를 노출하지 않고 재시도할 수 있다', async () => {
  let failed = true
  const { user } = mount('/contracts', true, () => {
    if (failed) throw new ApiError({ code: 'FGC-COMMON-500', message: 'internal SQL detail' }, 'safe-id', 500)
    return pageData([row])
  })
  expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: safe-id')
  expect(screen.queryByText(/internal SQL/)).toBeNull()
  failed = false
  await user.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('link', { name: row.contractNo })).toBeInTheDocument()
})

it('URL에 반영하기 전 입력한 검색조건도 초기화한다', async () => {
  const { user } = mount()
  await screen.findByRole('table', { name: '보험계약 검색 결과' })
  await user.type(screen.getByLabelText('계약번호'), '아직 조회하지 않은 계약')
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByLabelText('계약번호')).toHaveValue('')
})

it('범위를 초과한 페이지는 마지막 페이지로 교정한다', async () => {
  const { router } = mount('/contracts?page=999', true, () => pageData([row], 3))
  await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('page')).toBe('3'))
})
