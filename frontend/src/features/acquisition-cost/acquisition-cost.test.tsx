import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import { ToastRegion } from '../../components/Toast'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toasts'
import AcquisitionCostPage from './AcquisitionCostPage'
import { ContractAcquisitionCost } from './ContractAcquisitionCost'
import type { AcquisitionCheck } from './api'

const envelope = (data: unknown) => ({ data, error: null, requestId: '651-test' })
const page = (content: AcquisitionCheck[], extra = {}) =>
  envelope({ content, page: 1, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, ...extra })

const limitPolicy = {
  policyVersionId: 7,
  policyCode: 'REG-FOUR-YEAR-2027-V1',
  versionNo: 1,
  policyName: '4년 분급 규제 상한',
  sourceClass: 'REGULATORY',
  sourceClassLabel: '규제',
  regulationRefs: ['REG-02', 'REG-03'],
  sourceRefs: ['SRC-022'],
}
const paymentPolicy = {
  ...limitPolicy,
  policyVersionId: 8,
  policyCode: 'GA-FOUR-YEAR',
  sourceClass: 'GA_POLICY',
  sourceClassLabel: '회사 정책',
}
const contractFacts = {
  contractId: 10,
  contractNo: 'ACQ-001',
  insurerName: '가상생명',
  productName: '가상 종신',
  offeringVersion: '2027-FOUR-YEAR',
  basicDocumentVersion: 'BD-2027-01',
  basicDocumentDate: '2026-12-01',
  salesStartDate: '2027-01-01',
  contractDate: '2027-03-02',
  feeRegimeCode: 'FOUR_YEAR_2027',
  paymentStage: 'GA_TO_FC',
  paymentStageLabel: 'GA→설계사',
  calcRuleVersion: 'acq-cost-v1',
  checkedAt: '2026-11-04T10:00:00+09:00',
} as const
const violation: AcquisitionCheck = {
  ...contractFacts,
  acquisitionCostCheckId: 2,
  resultStatus: 'VIOLATION',
  resultStatusLabel: '한도 초과',
  acquisitionCost: {
    amount: 3000000,
    basisCode: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM',
    premiumAmount: 100000,
    ratePct: 3000,
    description: '가상 GA 정책(PROJECT_ASSUMPTION)',
  },
  upfront: {
    limitCode: 'UPFRONT_TOTAL',
    label: '선지급 총액',
    baseAmount: 3000000,
    limitPct: 100,
    calculatedAmount: 3000001,
    limitAmount: 3000000,
    usagePct: 100.000033,
    status: 'VIOLATION',
    statusLabel: '한도 초과',
    exceededFromInstallmentNo: 2,
    reason: '선지급 총액 3,000,001원이 한도 3,000,000원을 1원 초과합니다. 2회차부터 초과합니다.',
  },
  maintenance: {
    limitCode: 'MAINTENANCE_TOTAL',
    label: '유지관리 총액',
    baseAmount: 3000000,
    limitPct: 100,
    calculatedAmount: 0,
    limitAmount: 3000000,
    usagePct: 0,
    status: 'NORMAL',
    statusLabel: '한도 이내',
    exceededFromInstallmentNo: null,
    reason: '유지관리 총액 0원이 한도 3,000,000원 이내입니다.',
  },
  indirectSupportAmount: 0,
  unclassifiedAmount: 0,
  exclusions: [
    {
      scheduleLineId: 91,
      installmentNo: 1,
      itemCode: 'NEWCOMER_SUPPORT',
      itemName: '신인활동지원비',
      amount: 300000,
      excluded: true,
      evidenceRef: 'EVD-1',
      reason: '요건과 증빙을 갖춰 계산에서 제외했습니다.',
    },
  ],
  lineGroups: [
    {
      bucket: 'UPFRONT',
      bucketLabel: '선지급',
      itemCode: 'BASE_COMMISSION',
      itemName: '기본 모집수수료',
      feeComponentType: 'UPFRONT',
      installmentFrom: 1,
      installmentTo: 2,
      lineCount: 2,
      amount: 3000001,
    },
  ],
  reasons: [{ code: 'LIMIT_EXCEEDED', message: '선지급 총액 3,000,001원이 한도 3,000,000원을 1원 초과합니다.' }],
  limitPolicy,
  paymentPolicy,
  scheduleHeaderId: 55,
  scheduleVersionNo: 1,
}
const missing: AcquisitionCheck = {
  ...contractFacts,
  acquisitionCostCheckId: 3,
  contractNo: 'ACQ-002',
  resultStatus: 'REVIEW_REQUIRED',
  resultStatusLabel: '검토 필요',
  acquisitionCost: { amount: null, basisCode: null, premiumAmount: 100000, ratePct: null, description: null },
  upfront: {
    limitCode: 'UPFRONT_TOTAL',
    label: '선지급 총액',
    baseAmount: null,
    limitPct: 100,
    calculatedAmount: null,
    limitAmount: null,
    usagePct: null,
    status: 'REVIEW_REQUIRED',
    statusLabel: '검토 필요',
    exceededFromInstallmentNo: null,
    reason: '계약체결비용 기준액이 없어 한도를 계산하지 못했습니다.',
  },
  maintenance: {
    limitCode: 'MAINTENANCE_TOTAL',
    label: '유지관리 총액',
    baseAmount: null,
    limitPct: 100,
    calculatedAmount: null,
    limitAmount: null,
    usagePct: null,
    status: 'REVIEW_REQUIRED',
    statusLabel: '검토 필요',
    exceededFromInstallmentNo: null,
    reason: '계약체결비용 기준액이 없어 한도를 계산하지 못했습니다.',
  },
  indirectSupportAmount: null,
  unclassifiedAmount: null,
  exclusions: [],
  lineGroups: [],
  reasons: [{ code: 'ACQUISITION_COST_MISSING', message: '지급 정책에 계약체결비용 기준이 없습니다.' }],
  limitPolicy,
  paymentPolicy,
  scheduleHeaderId: null,
  scheduleVersionNo: null,
}
const notApplicable: AcquisitionCheck = {
  ...contractFacts,
  acquisitionCostCheckId: 4,
  contractNo: 'ACQ-003',
  feeRegimeCode: 'CURRENT',
  resultStatus: 'NOT_APPLICABLE',
  resultStatusLabel: '적용 대상 아님',
  acquisitionCost: null,
  upfront: null,
  maintenance: null,
  indirectSupportAmount: null,
  unclassifiedAmount: null,
  exclusions: [],
  lineGroups: [],
  reasons: [{ code: 'NOT_APPLICABLE', message: '판매개시일과 기초서류가 분급 적용례를 충족하지 않아 적용 대상이 아닙니다.' }],
  limitPolicy: null,
  paymentPolicy: null,
  scheduleHeaderId: null,
  scheduleVersionNo: null,
}
type Responder = (path: string, options?: { method?: string; body?: unknown; idempotencyKey?: string }) => unknown
const contracts = envelope({
  content: [{ contractId: 10, contractNo: 'ACQ-001', productName: '가상 종신' }],
  page: 1,
  totalPages: 1,
})
const insurers = envelope({
  content: [{ insurerId: 1, insurerCode: 'INS1', insurerName: '가상생명', activeYn: true }],
  page: 1,
  totalPages: 1,
})
function base(path: string) {
  if (path.includes('/base/insurers')) return insurers
  if (path.startsWith('/api/v1/contracts')) return contracts
  return undefined
}
function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } })
}
function spy(responder: Responder) {
  return vi.spyOn(apiClient, 'request').mockImplementation(async (path, options) => {
    const handled = base(path)
    if (handled) return handled as never
    return responder(path, options as never) as never
  })
}
function mount(responder: Responder, path = '/acquisition-cost-checks', canProcess = true) {
  const request = spy(responder)
  const router = createMemoryRouter(
    [
      {
        element: <Outlet context={{ month: '2026-07', user: { canProcess } }} />,
        children: [{ path: '/acquisition-cost-checks', element: <AcquisitionCostPage /> }],
      },
    ],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={client()}>
      <RouterProvider router={router} />
      <ToastRegion />
    </QueryClientProvider>,
  )
  return { request, router, user: userEvent.setup() }
}
const posts = (request: ReturnType<typeof spy>) =>
  request.mock.calls.filter(([, options]) => (options as { method?: string } | undefined)?.method === 'POST')
const failure = (status: number, code: string, message: string, field: string | null = null) =>
  new ApiError({ code, message, field }, 'req-651', status)

afterEach(() => {
  vi.restoreAllMocks()
  useToastStore.setState({ messages: [] })
})

describe('ACQC-W01 결과 표시', () => {
  it('선지급과 유지관리를 각각 보이고 서로 차감한 값이나 고정 비율을 만들지 않는다', async () => {
    mount(() => page([violation]))
    const upfront = await screen.findByRole('article', { name: '선지급 총액 한도 결과' })
    expect(within(upfront).getByText('3,000,001원')).toBeInTheDocument()
    expect(within(upfront).getAllByText('3,000,000원')).toHaveLength(2)
    expect(within(upfront).getAllByText('100.0%')).toHaveLength(2)
    expect(within(upfront).getByText('한도 초과')).toBeInTheDocument()
    expect(within(upfront).getByText(/2회차부터 초과/)).toBeInTheDocument()

    const maintenance = screen.getByRole('article', { name: '유지관리 총액 한도 결과' })
    // 선지급이 한도를 넘어도 유지관리 한도는 그대로이고, 실제 0은 0원이다.
    expect(within(maintenance).getByText('한도 이내')).toBeInTheDocument()
    expect(within(maintenance).getAllByText('3,000,000원')).toHaveLength(2)
    expect(within(maintenance).getAllByText('0원')).toHaveLength(2)
    expect(document.body.textContent).toContain('서로 차감하지 않습니다')
    expect(document.body.textContent).not.toContain('19%')
    expect(document.body.textContent).not.toContain('잔여')
  })

  it('정책 근거와 값의 출처를 보이고 규정 고정값처럼 보이지 않게 한다', async () => {
    mount(() => page([violation]))
    const basis = await screen.findByRole('region', { name: '계산 근거' })
    expect(within(basis).getByText('가상 GA 정책(PROJECT_ASSUMPTION)')).toBeInTheDocument()
    expect(within(basis).getByText(/4년 분급 규제 상한 v1, 회사 정책/)).toBeInTheDocument()
    expect(within(basis).getByText(/4년 분급 규제 상한 v1, 규제/)).toBeInTheDocument()
    expect(within(basis).getByText('선지급수수료, 유지관리수수료')).toBeInTheDocument()
    expect(within(basis).getByText(/규정이 정한 고정값이 아니라/)).toBeInTheDocument()
    expect(within(basis).getByRole('table', { name: '제4-32조제7항 제외 후보' })).toHaveTextContent('EVD-1')
    expect(within(basis).getByRole('table', { name: '예정 회차 산입 내역' })).toHaveTextContent('기본 모집수수료')
    expect(document.body.textContent).toContain('4년 및 7년 회차는 결과와 관계없이 확정할 수 없습니다')
  })

  it('자료 없음은 0원으로 보이지 않고 검토 필요와 적용 대상 아님을 구분한다', async () => {
    const { user } = mount(() => page([missing, notApplicable]))
    const row = await screen.findByRole('row', { name: '계약 ACQ-002 결과 보기' })
    expect(within(row).getAllByText('자료 없음').length).toBeGreaterThan(0)
    const upfront = screen.getByRole('article', { name: '선지급 총액 한도 결과' })
    expect(within(upfront).getByText('검토 필요')).toBeInTheDocument()
    expect(within(upfront).queryByText('0원')).not.toBeInTheDocument()
    expect(within(upfront).getAllByText('자료 없음')).toHaveLength(4)
    expect(screen.getByRole('list', { name: '판정 사유' })).toHaveTextContent('계약체결비용 기준이 없습니다')

    await user.click(screen.getByRole('row', { name: '계약 ACQ-003 결과 보기' }))
    expect(await screen.findByRole('list', { name: '판정 사유' })).toHaveTextContent('적용 대상이 아닙니다')
    expect(screen.queryByRole('article', { name: '선지급 총액 한도 결과' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '계산 근거' })).not.toBeInTheDocument()
  })
})

describe('ACQC-W01 조회 상태', () => {
  it('로딩 뒤 0건은 정상 문구이고 조회만으로 검증을 만들지 않는다', async () => {
    let release: (value: unknown) => void = () => {}
    const { request } = mount(() => new Promise((resolve) => (release = resolve)))
    expect(await screen.findByRole('status')).toHaveTextContent('불러오는 중')
    release(page([]))
    expect(await screen.findByText('조건에 맞는 검증 결과가 없습니다.')).toBeInTheDocument()
    expect(posts(request)).toHaveLength(0)
  })

  it.each([
    [403, 'FGC-COMMON-403', '권한 없음', '이 작업을 할 권한이 없습니다.', false],
    [500, 'FGC-COMMON-500', '내부 오류', 'req-651', true],
  ])('%s 조회 실패를 0건으로 표시하지 않는다', async (status, code, message, expected, retry) => {
    mount(() => {
      throw failure(status, code, message)
    })
    expect(await screen.findByRole('alert')).toHaveTextContent(expected)
    expect(screen.queryByText('조건에 맞는 검증 결과가 없습니다.')).not.toBeInTheDocument()
    expect(screen.queryByText(/총 0건/)).not.toBeInTheDocument()
    if (retry) expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    else expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('서버 실패 뒤 다시 시도하면 결과를 보인다', async () => {
    let fail = true
    const { user } = mount(() => {
      if (fail) throw failure(500, 'FGC-COMMON-500', '내부 오류')
      return page([violation])
    })
    await user.click(await screen.findByRole('button', { name: '다시 시도' }))
    fail = false
    await user.click(await screen.findByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('row', { name: '계약 ACQ-001 결과 보기' })).toBeInTheDocument()
  })

  it('필터는 1쪽으로 돌아가고 초기화는 기본값으로 다시 조회한다', async () => {
    const { request, router, user } = mount(() => page([violation]), '/acquisition-cost-checks?resultStatus=VIOLATION&page=2&contractNo=ACQ')
    await screen.findByRole('row', { name: '계약 ACQ-001 결과 보기' })
    expect(screen.getByLabelText('계약번호')).toHaveValue('ACQ')
    expect(request.mock.calls.some(([path]) => path.includes('resultStatus=VIOLATION') && path.includes('page=2'))).toBe(true)

    await user.selectOptions(screen.getByLabelText('지급단계'), 'GA_TO_FC')
    expect(router.state.location.search).toContain('paymentStage=GA_TO_FC')
    expect(router.state.location.search).not.toContain('page=')

    await user.click(screen.getByRole('button', { name: '초기화' }))
    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(screen.getByLabelText('계약번호')).toHaveValue('')
    expect(screen.getByLabelText('판정')).toHaveValue('')
  })

  it('늦게 도착한 이전 조건의 응답으로 현재 결과를 덮지 않는다', async () => {
    const slow: { resolve?: (value: unknown) => void } = {}
    const { user } = mount((path) => {
      if (path.includes('resultStatus=VIOLATION')) return page([violation])
      return new Promise((resolve) => (slow.resolve = resolve))
    })
    await screen.findByLabelText('판정')
    await user.selectOptions(screen.getByLabelText('판정'), 'VIOLATION')
    expect(await screen.findByRole('row', { name: '계약 ACQ-001 결과 보기' })).toBeInTheDocument()
    slow.resolve?.(page([missing]))
    await waitFor(() => expect(screen.queryByRole('row', { name: '계약 ACQ-002 결과 보기' })).not.toBeInTheDocument())
    expect(screen.getByRole('row', { name: '계약 ACQ-001 결과 보기' })).toBeInTheDocument()
  })
})

describe('ACQC-W01 검증 요청', () => {
  async function openAndPick(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: '검증 요청' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByLabelText(/계약/))
    await user.click(await screen.findByRole('option', { name: /ACQ-001/ }))
    return dialog
  }

  it('읽기 역할에는 요청 버튼 대신 조건을 설명한다', async () => {
    mount(() => page([violation]), '/acquisition-cost-checks', false)
    await screen.findByRole('row', { name: '계약 ACQ-001 결과 보기' })
    expect(screen.queryByRole('button', { name: '검증 요청' })).not.toBeInTheDocument()
    expect(screen.getByText('검증 요청은 처리 권한이 있는 계정만 할 수 있습니다.')).toBeInTheDocument()
  })

  it('계약을 고르지 않으면 보내지 않고 저장 뒤 결과를 선택한다', async () => {
    const { request, user } = mount((_, options) => {
      if (options?.method === 'POST') return envelope(missing)
      return page([violation, missing])
    })
    await user.click(await screen.findByRole('button', { name: '검증 요청' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '검증 요청' }))
    expect(await within(dialog).findByText('계약을 고르세요.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/계약/)).toHaveFocus()
    expect(posts(request)).toHaveLength(0)

    await user.click(within(dialog).getByLabelText(/계약/))
    await user.click(await screen.findByRole('option', { name: /ACQ-001/ }))
    await user.selectOptions(within(dialog).getByLabelText(/지급단계/), 'INSURER_TO_GA')
    await user.click(within(dialog).getByRole('button', { name: '검증 요청' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [path, options] = posts(request)[0]
    expect(path).toBe('/api/v1/acquisition-cost-checks')
    expect((options as { body: unknown }).body).toEqual({ contractId: 10, paymentStage: 'INSURER_TO_GA' })
    expect((options as { idempotencyKey: string }).idempotencyKey).toBeTruthy()
    expect(await screen.findByText(/검증을 저장했습니다. 판정: 검토 필요/)).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: '판정 사유' })).toHaveTextContent('계약체결비용 기준이 없습니다')
  })

  it('처리 중에는 버튼을 잠그고 응답 유실 재시도는 같은 키, 성공 뒤의 요청은 새 키를 쓴다', async () => {
    let release: (value: unknown) => void = () => {}
    let attempt = 0
    const { request, user } = mount((_, options) => {
      if (options?.method !== 'POST') return page([violation])
      attempt += 1
      if (attempt === 1) throw new TypeError('Failed to fetch')
      if (attempt === 2) return new Promise((resolve) => (release = resolve))
      return envelope(violation)
    })
    const dialog = await openAndPick(user)
    await user.click(within(dialog).getByRole('button', { name: '검증 요청' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('연결이 끊겨')
    // 실패해도 입력은 그대로다.
    expect(within(dialog).getByLabelText(/계약/)).toHaveValue('ACQ-001, 가상 종신')

    // [다시 시도]는 목록만 다시 읽지 않고 같은 키로 요청을 다시 보낸다.
    await user.click(within(dialog).getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: /검증 요청/ })).toBeDisabled())
    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    release(envelope(violation))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const again = await openAndPick(user)
    await user.click(within(again).getByRole('button', { name: '검증 요청' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const keys = posts(request).map(([, options]) => (options as { idempotencyKey: string }).idempotencyKey)
    expect(keys).toHaveLength(3)
    expect(keys[1]).toBe(keys[0])
    // 모달을 다시 열면 새 시도다.
    expect(keys[2]).not.toBe(keys[0])
  })

  it('409는 입력을 지우지 않고 다시 조회를 안내하고 422는 부족한 항목을 보인다', async () => {
    const error = { current: failure(409, 'FGC-IMPL-REQUEST_IN_PROGRESS', '같은 요청을 처리하고 있습니다.') }
    const lists = { count: 0 }
    const { user } = mount((_, options) => {
      if (options?.method === 'POST') throw error.current
      lists.count += 1
      return page([violation])
    })
    const dialog = await openAndPick(user)
    await user.click(within(dialog).getByRole('button', { name: '검증 요청' }))
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('최신 상태와 달라졌습니다')
    expect(within(dialog).getByLabelText(/계약/)).toHaveValue('ACQ-001, 가상 종신')
    const before = lists.count
    await user.click(within(alert).getByRole('button', { name: '다시 조회' }))
    await waitFor(() => expect(lists.count).toBeGreaterThan(before))

    error.current = failure(422, 'FGC-IMPL-POLICY_MISSING', '적용 정책 또는 계산 근거가 부족해 계산할 수 없습니다.', 'contractId')
    await user.click(within(dialog).getByRole('button', { name: '검증 요청' }))
    await waitFor(() => expect(within(dialog).getByRole('alert')).toHaveTextContent('근거가 부족해'))
    expect(within(dialog).getByRole('alert')).not.toHaveTextContent('contractId')
    expect(screen.queryByText('0원', { selector: '.acq-failure *' })).not.toBeInTheDocument()
  })

  it('403 요청 실패는 권한 없음만 보이고 재시도나 다른 결과를 만들지 않는다', async () => {
    const { user } = mount((_, options) => {
      if (options?.method === 'POST') throw failure(403, 'FGC-COMMON-403', '권한 없음')
      return page([violation])
    })
    const dialog = await openAndPick(user)
    await user.click(within(dialog).getByRole('button', { name: '검증 요청' }))
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('이 작업을 할 권한이 없습니다.')
    expect(within(alert).queryByRole('button')).not.toBeInTheDocument()
  })

  it('모달을 닫아도 필터와 선택을 유지한다', async () => {
    const { router, user } = mount(() => page([violation, missing]), '/acquisition-cost-checks?resultStatus=VIOLATION')
    await user.click(await screen.findByRole('row', { name: '계약 ACQ-002 결과 보기' }))
    await user.click(screen.getByRole('button', { name: '검증 요청' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(router.state.location.search).toBe('?resultStatus=VIOLATION')
    expect(screen.getByRole('list', { name: '판정 사유' })).toHaveTextContent('계약체결비용 기준이 없습니다')
  })
})

describe('CONT-W02 계약체결비용 영역', () => {
  function mountContract(responder: Responder, canProcess = true) {
    useAuthStore.setState({ accessToken: 'fixture-acquisition', loggingOut: false })
    const request = vi.spyOn(apiClient, 'request').mockImplementation(async (path, options) => {
      if (path === '/api/v1/auth/me')
        return envelope({ loginId: 'a', userName: 'a', roleCode: 'SETTLEMENT', demoMonth: '2026-07', canProcess }) as never
      return responder(path, options as never) as never
    })
    const router = createMemoryRouter(
      [
        { path: '/contracts/10', element: <ContractAcquisitionCost contractId="10" /> },
        { path: '/acquisition-cost-checks', element: <h1>검증 목록 화면</h1> },
      ],
      { initialEntries: ['/contracts/10'] },
    )
    render(
      <QueryClientProvider client={client()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    return { request, router, user: userEvent.setup() }
  }

  it('지급단계별 최근 결과만 보이고 결과가 없으면 예정 생성과 별개임을 안내한다', async () => {
    const { request, user } = mountContract((path) => {
      expect(path).toContain('contractId=10')
      return page(path.includes('paymentStage=GA_TO_FC') ? [violation] : [])
    })
    expect(await screen.findByRole('article', { name: '선지급 총액 한도 결과' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('지급단계'), 'INSURER_TO_GA')
    expect(await screen.findByText('이 지급단계의 검증 결과가 없습니다.')).toBeInTheDocument()
    // 다른 단계의 결과가 많아도 묻히지 않게 지급단계별로 조회한다.
    expect(request).toHaveBeenCalledWith(expect.stringContaining('paymentStage=INSURER_TO_GA'), expect.anything())
    expect(screen.queryByText('0원')).not.toBeInTheDocument()
    expect(posts(request as never)).toHaveLength(0)
  })

  it('검증 요청 뒤 저장 결과를 보이고 전체 결과 링크는 표시하지 않는다', async () => {
    let stored: AcquisitionCheck[] = []
    const { request, user } = mountContract((_, options) => {
      if (options?.method === 'POST') {
        stored = [violation]
        return envelope(violation)
      }
      return page(stored)
    })
    await user.click(await screen.findByRole('button', { name: '검증 요청' }))
    expect(await screen.findByRole('article', { name: '선지급 총액 한도 결과' })).toBeInTheDocument()
    expect((posts(request as never)[0][1] as { body: unknown }).body).toEqual({ contractId: 10, paymentStage: 'GA_TO_FC' })
    expect(screen.queryByRole('link', { name: '전체 결과 보기' })).not.toBeInTheDocument()
  })

  it('요청 실패의 다시 시도는 같은 키로 다시 보내고 409의 다시 조회는 결과만 새로 읽는다', async () => {
    let attempt = 0
    let lists = 0
    const { request, user } = mountContract((_, options) => {
      if (options?.method !== 'POST') {
        lists += 1
        return page([])
      }
      attempt += 1
      if (attempt === 1) throw new TypeError('Failed to fetch')
      throw failure(409, 'FGC-IMPL-REQUEST_IN_PROGRESS', '같은 요청을 처리하고 있습니다.')
    })
    await user.click(await screen.findByRole('button', { name: '검증 요청' }))
    await user.click(await screen.findByRole('button', { name: '다시 시도' }))
    const reload = await screen.findByRole('button', { name: '다시 조회' })
    const keys = posts(request).map(([, options]) => (options as { idempotencyKey: string }).idempotencyKey)
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])

    const before = lists
    await user.click(reload)
    await waitFor(() => expect(lists).toBeGreaterThan(before))
    expect(posts(request)).toHaveLength(2)
  })

  it('요청 실패와 조회 실패를 0건으로 표시하지 않고 읽기 역할에는 버튼이 없다', async () => {
    mountContract(() => {
      throw failure(500, 'FGC-COMMON-500', '내부 오류')
    }, false)
    expect(await screen.findByRole('alert')).toHaveTextContent('req-651')
    expect(screen.queryByText(/검증 결과가 없습니다/)).not.toBeInTheDocument()
    expect(await screen.findByText('검증 요청은 처리 권한이 있는 계정만 할 수 있습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '검증 요청' })).not.toBeInTheDocument()
  })
})
