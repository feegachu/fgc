import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, MemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import { capDetailQuery, criteriaFromParams } from './api'
import type { CapBasis, CapItem, CapSearch } from './api'
import { CapDetailModal } from './CapDetailModal'

const item: CapItem = {
  capCheckId: 11,
  contractId: 3,
  contractNo: 'C-2026-0001',
  paymentStage: 'INSURER_TO_GA',
  paymentStageLabel: '원수사 → GA',
  asOfDate: '2026-07-01',
  basePremiumAmount: 100000,
  refund12mAmount: 288000,
  complianceDeductionAmount: 3000,
  limitAmount: 915000,
  includedAmount: 1000000,
  remainingAmount: -85000,
  usagePct: '1200.0000009',
  resultStatus: 'WARNING',
  resultStatusLabel: '주의',
  capRuleSetId: 7,
}
const basis: CapBasis = {
  capCheck: item,
  calculationSnapshot: { premiumMultiplier: 12, warningUsagePct: '90.0000' },
  warningStartUsagePct: '82.857142',
  details: [
    {
      detailSeq: 2,
      commissionItemName: '기본수수료',
      amount: 1000000,
      classificationSnapshot: 'INCLUDED',
      decisionReason: '저장 당시 산입',
      evidenceRef: 'REG-09',
    },
    {
      detailSeq: 1,
      commissionItemName: '지원비',
      amount: 10000,
      classificationSnapshot: 'EXCLUDED',
      decisionReason: '저장 당시 제외',
    },
  ],
}
const list: CapSearch = {
  content: [item],
  page: 1,
  size: 20,
  totalElements: 41,
  totalPages: 3,
  summary: { normal: 30, warning: 8, violation: 2, reviewRequired: 1 },
  stageSummary: [
    {
      paymentStage: 'INSURER_TO_GA',
      contractCount: 30,
      limitAmountTotal: 30000000,
      includedAmountTotal: 25000000,
      complianceDeductionAmountTotal: 3000,
      usagePct: '83.3333339',
      warningCount: 2,
    },
    {
      paymentStage: 'GA_TO_FC',
      contractCount: 11,
      limitAmountTotal: 11000000,
      includedAmountTotal: 9000000,
      usagePct: '81.8181819',
      violationCount: 1,
    },
  ],
  agentSummary: [
    {
      agentId: 4,
      agentName: '김설계',
      agentCode: 'FC-4',
      organizationName: '서울',
      contractCount: 11,
      limitAmountTotal: 11000000,
      includedAmountTotal: 9000000,
      usagePct: '81.8181819',
      violationCount: 1,
    },
  ],
}
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'cap-test' })
let requests: string[]
type Handler = (url: string) => unknown
function renderAt(path: string, handler?: Handler, roleCode = 'SETTLEMENT') {
  requests = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url) => {
    requests.push(url)
    if (url === '/api/v1/auth/me')
      return envelope({
        loginId: 'cap01',
        userName: 'CAP 담당자',
        roleCode,
        demoMonth: '2026-07',
        canProcess: roleCode === 'SETTLEMENT',
      })
    const override = await handler?.(url)
    if (override !== undefined) return envelope(override)
    if (url.startsWith('/api/v1/base/insurers'))
      return envelope({ content: [{ insurerId: 1, insurerCode: 'INS-1', insurerName: '한빛생명', activeYn: true }] })
    if (url.startsWith('/api/v1/base/organizations'))
      return envelope({
        content: [{ organizationId: 2, organizationCode: 'ORG-2', organizationName: '서울', activeYn: true }],
      })
    if (url.endsWith('/details')) return envelope(basis)
    if (url.startsWith('/api/v1/cap-checks?')) return envelope(list)
    throw new Error(`unexpected ${url}`)
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, user: userEvent.setup() }
}
const listCalls = () => requests.filter((url) => url.startsWith('/api/v1/cap-checks?'))
beforeAll(async () => {
  await import('./CapListPage')
})
beforeEach(() => {
  useAuthStore.setState({ accessToken: 'cap-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
})

describe('CAP-W01 URL·조회 전용 전환', () => {
  it('부정확한 URL은 공통 기준월/허용 코드/1-based page/20행으로 보정한다', () => {
    expect(
      criteriaFromParams(
        new URLSearchParams(
          'month=2026-13&stage=NO&status=NO&page=2abc&size=99&insurerId=-1&orgId=oops&contractNo=%20C%20',
        ),
        '2026-07',
      ),
    ).toEqual({
      month: '2026-07',
      stage: '',
      status: '',
      page: 1,
      size: 20,
      insurerId: '',
      orgId: '',
      contractNo: 'C',
      sort: '',
    })
    expect(criteriaFromParams(new URLSearchParams(), null).month).toBe('')
  })
  it('#604 허용된 정렬만 요청에 싣고 나머지는 서버 기본 정렬로 되돌린다', () => {
    expect(criteriaFromParams(new URLSearchParams('sort=usagePct,desc'), '2026-07').sort).toBe('usagePct,desc')
    expect(criteriaFromParams(new URLSearchParams('sort=resultStatus,asc'), '2026-07').sort).toBe('')
    expect(criteriaFromParams(new URLSearchParams('sort=usagePct'), '2026-07').sort).toBe('')
  })
  it('#604 헤더를 누르면 정렬 방향이 바뀌고 첫 페이지로 돌아가며 검색조건과 유지한다', async () => {
    const { router, user } = renderAt('/cap-checks?month=2026-07&contractNo=C-1&page=2')
    const table = await screen.findByRole('table', { name: '계약별 판정 목록' })
    // 순서 기준이 없는 판정 컬럼은 정렬 버튼이 없다. 도움말 버튼은 정렬 버튼과 분리되어 있다.
    expect(within(table).queryByRole('button', { name: '판정' })).toBeNull()
    expect(within(table).getByRole('columnheader', { name: /사용률/ })).toHaveAttribute('aria-sort', 'none')
    await user.click(within(table).getByRole('button', { name: '사용률' }))
    await waitFor(() => expect(new URLSearchParams(listCalls().at(-1)!.split('?')[1]).get('sort')).toBe('usagePct,asc'))
    const last = new URLSearchParams(listCalls().at(-1)!.split('?')[1])
    expect(last.get('page')).toBe('1')
    expect(last.get('contractNo')).toBe('C-1')
    expect(within(table).getByRole('columnheader', { name: /사용률/ })).toHaveAttribute('aria-sort', 'ascending')
    await user.click(within(table).getByRole('button', { name: '사용률' }))
    await waitFor(() => expect(router.state.location.search).toContain('sort=usagePct%2Cdesc'))
    expect(within(table).getByRole('columnheader', { name: /사용률/ })).toHaveAttribute('aria-sort', 'descending')
    // 한도 헤더의 도움말은 정렬을 바꾸지 않는다.
    expect(within(table).getByRole('button', { name: '한도' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).toContain('sort=usagePct%2Cdesc'))
  })
  it('서버 기준월로 조회하며 문자열 절삭·저장 판정·음수·전체 범위 집계를 표시한다', async () => {
    renderAt('/cap-checks')
    const table = await screen.findByRole('table', { name: '계약별 판정 목록' })
    await waitFor(() => expect(within(table).getByText('C-2026-0001')).toBeVisible())
    expect(screen.getByLabelText('기준월')).toHaveValue('2026-07')
    expect(listCalls().every((url) => new URLSearchParams(url.split('?')[1]).get('month') === '2026-07')).toBe(true)
    expect(within(table).getByText('1,200.000000%')).toBeVisible()
    expect(within(table).getByText('주의')).toBeVisible() // 1200%를 프론트에서 VIOLATION으로 다시 판정하지 않음
    expect(within(table).getByText('-85,000원')).toHaveClass('is-negative-amount')
    expect(within(table).getByRole('link', { name: 'C-2026-0001' })).toHaveAttribute('href', '/contracts/3?tab=cap')
    expect(screen.getByRole('button', { name: /저장 판정 정상\s*30/ })).toBeVisible()
    expect(screen.getByText('30,000,000원')).toBeVisible() // 현재 페이지 1행으로 합산하지 않음
    expect(screen.getByText('모니터링 지표 · 규제 판정 아님')).toBeVisible()
  })
  it('필터·페이지·행 수를 URL에 유지하고 뒤로 이동 시 조건을 복원한다', async () => {
    const { router, user } = renderAt(
      '/cap-checks?month=2026-06&stage=GA_TO_FC&status=WARNING&insurerId=1&orgId=2&contractNo=OLD&page=2&size=10',
    )
    await screen.findByText('C-2026-0001')
    await waitFor(() => expect(screen.getByLabelText('보험회사')).toHaveValue('1'))
    expect(screen.getByLabelText('조직')).toHaveValue('2')
    await user.clear(screen.getByLabelText('계약번호'))
    await user.type(screen.getByLabelText('계약번호'), ' NEW ')
    await user.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).toContain('contractNo=NEW'))
    expect(new URLSearchParams(router.state.location.search).has('page')).toBe(false)
    await user.click(screen.getByRole('button', { name: '2' }))
    await waitFor(() => expect(router.state.location.search).toContain('page=2'))
    await user.selectOptions(screen.getByLabelText('한 화면 행 수'), '50')
    await waitFor(() => expect(router.state.location.search).toContain('size=50'))
    expect(new URLSearchParams(router.state.location.search).has('page')).toBe(false)
    await router.navigate(-1)
    await waitFor(() => expect(screen.getByLabelText('한 화면 행 수')).toHaveValue('10'))
    expect(new URLSearchParams(router.state.location.search).get('page')).toBe('2')
    expect(screen.getByLabelText('계약번호')).toHaveValue('NEW')
  })
  it('KPI 상태 토글과 초기화가 페이지를 리셋한다', async () => {
    const { router, user } = renderAt('/cap-checks?month=2026-07&page=2&size=10')
    const kpi = await screen.findByRole('button', { name: /저장 판정 위반\s*2/ })
    await user.click(kpi)
    await waitFor(() => expect(router.state.location.search).toContain('status=VIOLATION'))
    expect(kpi).toHaveAttribute('aria-pressed', 'true')
    expect(new URLSearchParams(router.state.location.search).has('page')).toBe(false)
    await user.click(kpi)
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).has('status')).toBe(false))
    await user.click(screen.getByRole('button', { name: '초기화' }))
    await waitFor(() => expect(screen.getByLabelText('한 화면 행 수')).toHaveValue('20'))
    expect(screen.getByLabelText('계약번호')).toHaveValue('')
  })
  it.each(['SYSTEM_ADMIN', 'GA_ADMIN', 'SETTLEMENT', 'COMPLIANCE'])('%s 역할은 읽기만 한다', async (role) => {
    renderAt('/cap-checks', undefined, role)
    await screen.findByText('C-2026-0001')
    expect(screen.queryByRole('button', { name: /^(저장|확정|재계산|판정 변경)$/ })).not.toBeInTheDocument()
  })
  it('0건 월에서는 목록·단계·설계사 빈 상태를 표시한다', async () => {
    renderAt('/cap-checks?month=2026-01', (url) =>
      url.startsWith('/api/v1/cap-checks?')
        ? {
            content: [],
            summary: {},
            stageSummary: [],
            agentSummary: [],
            page: 1,
            size: 20,
            totalElements: 0,
            totalPages: 0,
          }
        : undefined,
    )
    await screen.findByText('조건에 맞는 계약 판정이 없습니다.')
    expect(screen.getByText('조건에 맞는 지급단계별 집계가 없습니다.')).toBeVisible()
    expect(screen.getByText('조건에 맞는 GA → 설계사 단계 모니터링 대상이 없습니다.')).toBeVisible()
  })
})

describe('CAP-W02 공유 모달·스냅샷', () => {
  it('기존 비대상 사유를 시행 전 계약 사유로 바꾸지 않는다', async () => {
    const storedReason = '기존 비대상 사유'
    vi.spyOn(apiClient, 'request').mockResolvedValue(
      envelope({
        capCheck: {
          ...item,
          resultStatus: 'NOT_APPLICABLE',
          resultStatusLabel: '적용대상 아님',
          capRuleSetId: null,
          limitAmount: 0,
          includedAmount: 0,
          remainingAmount: 0,
          usagePct: null,
        },
        details: [],
        calculationSnapshot: { notApplicable: true, notApplicableReason: storedReason },
      }),
    )
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <CapDetailModal capCheckId={11} onClose={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByText(storedReason)).toBeInTheDocument()
    expect(screen.queryByText('적용대상 아님 — 시행 전 계약')).not.toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('적용대상 아님은 저장된 제외 사유를 보여주고 계산식과 게이지를 숨긴다', async () => {
    const nonApplicable: CapBasis = {
      capCheck: {
        ...item,
        resultStatus: 'NOT_APPLICABLE',
        resultStatusLabel: '적용대상 아님',
        capRuleSetId: null,
        limitAmount: null,
        includedAmount: null,
        remainingAmount: null,
        usagePct: null,
      },
      details: [],
      calculationSnapshot: {
        applicability: {
          reason: '적용대상 아님 — 시행 전 계약',
          reasonCode: 'CONTRACT_BEFORE_EFFECTIVE_DATE',
          contractDate: '2026-06-30',
          effectiveContractDate: '2026-07-01',
          policyVersion: 'CAP_APPLICABILITY_V1',
        },
      },
    }
    vi.spyOn(apiClient, 'request').mockResolvedValue(envelope(nonApplicable))
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <CapDetailModal capCheckId={11} onClose={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByText('적용대상 아님 — 시행 전 계약')).toBeInTheDocument()
    expect(screen.getByText('CONTRACT_BEFORE_EFFECTIVE_DATE')).toBeInTheDocument()
    expect(screen.getByText('2026-06-30')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(screen.queryByText('② 계산식')).not.toBeInTheDocument()
    expect(screen.queryByText('저장 산입금액과 항목별 산입 합계가 일치하지 않습니다.')).not.toBeInTheDocument()
  })
  it('행·산입 버튼으로 열고 닫아도 검색/페이지가 유지된다', async () => {
    const { router, user } = renderAt('/cap-checks?month=2026-07&page=2')
    await user.click(await screen.findByRole('button', { name: 'C-2026-0001 계산근거 열기' }))
    const dialog = await screen.findByRole('dialog', { name: '계산근거' })
    await within(dialog).findByText('① 입력값')
    expect(router.state.location.search).toContain('capCheckId=11')
    expect(router.state.location.search).toContain('page=2')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(router.state.location.search).not.toContain('capCheckId')
    expect(router.state.location.search).toContain('page=2')
    const table = screen.getByRole('table', { name: '계약별 판정 목록' })
    fireEvent.click(within(table).getByText('기준일').closest('table')!.querySelector('tbody td:nth-child(3)')!)
    await screen.findByRole('dialog')
  })
  it('목록과 무관한 딥링크·공식·항목 순서·증빙 공란·합계 검산을 유지한다', async () => {
    renderAt('/cap-checks?capCheckId=11', (url) =>
      url.startsWith('/api/v1/cap-checks?') ? { ...list, content: [] } : undefined,
    )
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('한도 = 100,000 × 12 − 288,000 + 3,000 = 915,000원')
    expect(within(dialog).getByText('증빙 미연결 / 후속 연결 대기')).toBeVisible()
    expect(within(dialog).getByText('제외 항목 미포함')).toBeVisible()
    const detailRows = within(dialog).getByRole('table', { name: '항목별 산입 내역' }).querySelectorAll('tbody tr')
    expect(detailRows[0]).toHaveTextContent('기본수수료') // API 순서를 프론트에서 임의 재정렬하지 않음
    expect(detailRows[1]).toHaveTextContent('지원비')
    expect(within(dialog).getByRole('link', { name: '예외 목록 보기' })).toHaveAttribute('href', '/exceptions')
    expect(within(dialog).getAllByText(/1,200.000000%/).length).toBeGreaterThan(0)
    expect(within(dialog).getAllByText('주의').length).toBeGreaterThan(0)
  })
  it('없는 ID의 서버 오류를 표시해도 목록은 정상이다', async () => {
    renderAt('/cap-checks?capCheckId=999', (url) => {
      if (url.endsWith('/999/details'))
        throw new ApiError({ code: 'FGC-CAP-404', message: '저장된 판정을 찾을 수 없습니다.' }, 'missing-999', 404)
    })
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('저장된 판정을 찾을 수 없습니다.')
    await screen.findByText('C-2026-0001', { selector: 'a' })
    expect(screen.getByRole('table', { name: '계약별 판정 목록' })).toBeVisible()
  })
  it('500은 요청 ID만 노출하고 상세 내부 메시지는 숨긴다', async () => {
    renderAt('/cap-checks?capCheckId=999', (url) => {
      if (url.endsWith('/999/details'))
        throw new ApiError({ code: 'FGC-COMMON-500', message: 'internal database secret' }, 'trace-500', 500)
    })
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('요청 ID: trace-500')
    expect(screen.queryByText(/internal database secret/)).not.toBeInTheDocument()
  })
  it.each([null, undefined, ''])(
    '공통 모달은 계약 상세 소비자의 빈 ID(%s)를 열거나 조회하지 않는다',
    async (emptyId) => {
      const close = vi.fn()
      const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope(basis))
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      function Consumer({ id }: { id: string | number | null | undefined }) {
        return (
          <QueryClientProvider client={client}>
            <CapDetailModal capCheckId={id} onClose={close} />
          </QueryClientProvider>
        )
      }
      const router = createMemoryRouter([{ path: '/contracts/:id', element: <Consumer id={emptyId} /> }], {
        initialEntries: ['/contracts/3?tab=cap'],
      })
      const view = render(<RouterProvider router={router} />)
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(request).not.toHaveBeenCalled()
      expect(capDetailQuery(emptyId).enabled).toBe(false)
      expect(capDetailQuery(emptyId).queryKey).toEqual(['cap-checks', 'detail', null])
      await expect(client.fetchQuery(capDetailQuery(emptyId))).rejects.toThrow('계산근거 ID가 없습니다.')
      expect(request).not.toHaveBeenCalled()
      view.unmount()
      const opened = createMemoryRouter([{ path: '/contracts/:id', element: <Consumer id={11} /> }], {
        initialEntries: ['/contracts/3?tab=cap'],
      })
      render(<RouterProvider router={opened} />)
      const dialog = await screen.findByRole('dialog')
      await within(dialog).findByText('저장 당시 산입', { selector: '.table-cell-preview' })
      expect(request).toHaveBeenCalledWith(
        '/api/v1/cap-checks/11/details',
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      )
      fireEvent.click(within(dialog).getByRole('button', { name: '닫기' }))
      expect(close).toHaveBeenCalledOnce()
    },
  )
  it('주의선은 저장 정책 90이 아닌 서버의 사용률 위치를 쓰고 저장 판정을 유지한다', async () => {
    renderAt('/cap-checks?capCheckId=11')
    const dialog = await screen.findByRole('dialog')
    const marker = await within(dialog).findByTitle('주의 기준 82.857142%')
    expect(marker).toHaveStyle({ left: '82.857142%' })
    expect(within(dialog).getByText('주의 기준 (1,200% 예산) 90.0000%')).toBeVisible()
    expect(within(dialog).getAllByText('주의').length).toBeGreaterThan(0)
  })
  it('서버 주의선 위치가 없으면 90% 선을 추정하지 않는다', async () => {
    renderAt('/cap-checks?capCheckId=11', (url) =>
      url.endsWith('/details') ? { ...basis, warningStartUsagePct: undefined } : undefined,
    )
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('저장 당시 산입', { selector: '.table-cell-preview' })
    expect(dialog.querySelector('.cap-react-warning')).not.toBeInTheDocument()
  })
  it('GA→설계사는 공제 미적용, 상세 합계 불일치 안내만 하고 저장 판정은 유지한다', async () => {
    renderAt('/cap-checks?capCheckId=11', (url) =>
      url.endsWith('/details')
        ? {
            ...basis,
            capCheck: {
              ...item,
              paymentStage: 'GA_TO_FC',
              complianceDeductionAmount: 0,
              limitAmount: 912000,
              includedAmount: 999999,
              remainingAmount: -87999,
            },
          }
        : undefined,
    )
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('저장 산입금액과 항목별 산입 합계가 일치하지 않습니다.')
    expect(within(dialog).getByText('적용하지 않음')).toBeVisible()
    expect(within(dialog).getByText('한도 = 100,000 × 12 − 288,000 = 912,000원')).toBeVisible()
    expect(within(dialog).getAllByText('주의').length).toBeGreaterThan(0)
  })
})
