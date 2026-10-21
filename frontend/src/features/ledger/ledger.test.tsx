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
import { correctionTarget, criteriaFromParams } from './api'
import type { JournalDetail } from './api'

const original: JournalDetail = {
  journalHeaderId: 11,
  journalNo: 'JRN-001',
  journalDate: '2026-07-01',
  journalType: 'EXPECTED_FC_PAYOUT',
  journalTypeLabel: '예상 지급(GA→설계사)',
  contractId: 1,
  contractNo: 'C-001',
  status: 'POSTED',
  statusLabel: '기표됨',
  balanced: true,
  debitTotal: 1200000,
  creditTotal: 1200000,
  validationRunId: 3,
  lines: [
    { lineNo: 1, accountCode: 'COMMISSION_EXPENSE', accountName: '수수료 비용', debitAmount: 1200000, creditAmount: 0 },
    {
      lineNo: 2,
      accountCode: 'COMMISSION_PAYABLE',
      accountName: '수수료 미지급금',
      debitAmount: 0,
      creditAmount: 1200000,
    },
  ],
}
let calls: { url: string; options?: RequestOptions }[]
const page = { content: [original], page: 1, size: 20, totalElements: 21, totalPages: 2, sort: 'journalDate,desc' }
function renderAt(
  path = '/journals',
  handler?: (url: string, options?: RequestOptions) => unknown,
  role = 'SETTLEMENT',
) {
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
        canReverseJournal: role !== 'COMPLIANCE',
      }
    else {
      data = await handler?.(url, options)
      if (data === undefined) {
        if (url === '/api/v1/journals/accounts')
          data = [{ accountCode: 'COMMISSION_EXPENSE', accountName: '수수료 비용', activeYn: true }]
        else if (url.startsWith('/api/v1/journals?')) data = page
        else if (url === '/api/v1/journals/11') data = original
        else if (url.startsWith('/api/v1/journals/imbalances')) data = { totalCount: 0, content: [] }
        else if (url.startsWith('/api/v1/exceptions')) data = { content: [], totalElements: 0, totalPages: 0 }
        else throw new Error(`unexpected ${url}`)
      }
    }
    return { data, error: null, requestId: 'ledger-test' }
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, user: userEvent.setup() }
}
beforeAll(async () => {
  await import('./LedgerPage')
})
beforeEach(() => {
  useAuthStore.setState({ accessToken: 'ledger-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
  useToastStore.setState({ messages: [] })
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null })
})
describe('LEDG-W01 검증원장 React 전환', () => {
  it('활성 계정 API에서 빠진 기존 계정도 과거 원장 검색 조건으로 제공한다', async () => {
    const { user } = renderAt('/journals?month=2026-07', (url) =>
      url === '/api/v1/journals/accounts'
        ? [{ accountCode: 'EXPECTED_INCOME', accountName: '예상 수수료수익 대응', activeYn: true }]
        : undefined,
    )
    await screen.findByRole('option', { name: '예상 수수료수익 대응' })
    expect(screen.getByRole('option', { name: '예상 수수료미수금' })).toBeEnabled()
    await user.selectOptions(screen.getByLabelText('계정과목'), 'EXPECTED_RECEIVABLE')
    await user.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(calls.some((call) => call.url.includes('account=EXPECTED_RECEIVABLE'))).toBe(true))
    expect(calls.some((call) => call.options?.method === 'POST')).toBe(false)
  })
  it('서버 계정명을 우선 표시하고 비활성 계정도 검색에서 숨기지 않는다', async () => {
    const { user } = renderAt('/journals?month=2026-07', (url) =>
      url === '/api/v1/journals/accounts'
        ? [{ accountCode: 'EXPECTED_RECEIVABLE', accountName: '과거 미수금', activeYn: false }]
        : undefined,
    )
    expect(await screen.findByRole('option', { name: '과거 미수금' })).toBeEnabled()
    expect(screen.queryByRole('option', { name: '예상 수수료미수금' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('계정과목'), 'EXPECTED_RECEIVABLE')
    await user.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(calls.some((call) => call.url.includes('account=EXPECTED_RECEIVABLE'))).toBe(true))
  })
  it('계정명이 비어 있어도 영어 코드를 노출하지 않고 기존 한글 매핑을 유지한다', async () => {
    const { user } = renderAt('/journals?month=2026-07', (url) =>
      url === '/api/v1/journals/accounts'
        ? [
            { accountCode: 'EXPECTED_RECEIVABLE', accountName: '', activeYn: true },
            { accountCode: 'LEGACY_UNKNOWN', accountName: '  ', activeYn: false },
          ]
        : undefined,
    )
    expect(await screen.findByRole('option', { name: '예상 수수료미수금' })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: '계정명 확인 필요' })).toHaveValue('LEGACY_UNKNOWN')
    expect(screen.queryByRole('option', { name: /LEGACY_UNKNOWN|EXPECTED_RECEIVABLE/ })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('계정과목'), 'LEGACY_UNKNOWN')
    await user.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(calls.some((call) => call.url.includes('account=LEGACY_UNKNOWN'))).toBe(true))
  })
  it('명시적인 조회 전에는 목록 API를 호출하지 않는다', async () => {
    renderAt()
    expect(await screen.findByText('조회 조건을 설정하고 조회 버튼을 눌러 주세요.')).toBeVisible()
    expect(calls.some((call) => call.url.startsWith('/api/v1/journals?'))).toBe(false)
    expect(screen.getByLabelText('분개일 (시작)')).toHaveValue('2026-07-01')
    expect(screen.getByLabelText('분개일 (끝)')).toHaveValue('2026-07-31')
  })
  it('기간·유형·계정·계약번호·상태 조회조건과 1-base 페이징을 URL/API에 유지한다', async () => {
    const { router, user } = renderAt()
    await screen.findByRole('option', { name: '수수료 비용' })
    fireEvent.change(screen.getByLabelText('분개일 (시작)'), { target: { value: '2026-07-02' } })
    await user.selectOptions(screen.getByLabelText('분개 유형'), 'EXPECTED_FC_PAYOUT')
    await user.selectOptions(screen.getByLabelText('계정과목'), 'COMMISSION_EXPENSE')
    await user.type(screen.getByLabelText('계약번호'), ' fgc-fgl02-202608 ')
    await user.selectOptions(screen.getByLabelText('상태'), 'POSTED')
    await user.click(screen.getByRole('button', { name: '조회' }))
    expect(await screen.findByRole('button', { name: 'JRN-001' })).toBeVisible()
    const query = new URLSearchParams(calls.find((call) => call.url.startsWith('/api/v1/journals?'))!.url.split('?')[1])
    expect(Object.fromEntries(query)).toMatchObject({
      from: '2026-07-02',
      to: '2026-07-31',
      type: 'EXPECTED_FC_PAYOUT',
      account: 'COMMISSION_EXPENSE',
      contractNo: 'fgc-fgl02-202608',
      status: 'POSTED',
      page: '1',
      size: '20',
    })
    expect(router.state.location.search).toContain('searched=true')
    expect(screen.getAllByText('1,200,000원')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: '2' }))
    await waitFor(() => expect(calls.some((call) => call.url.includes('page=2'))).toBe(true))
  })
  it('selected 딥링크는 searched 없이 상세와 검증 실행별 불균형을 조회한다', async () => {
    renderAt('/journals?selected=11', (url) =>
      url.startsWith('/api/v1/journals/imbalances') ? { totalCount: 2, content: [] } : undefined,
    )
    expect(await screen.findByText('이 검증 실행에 차변·대변이 맞지 않는 분개가 2건 있습니다.')).toBeVisible()
    expect(screen.getByRole('table', { name: '선택한 분개의 차변·대변 상세행' })).toBeVisible()
    expect(calls.some((call) => call.url.startsWith('/api/v1/journals?'))).toBe(false)
  })
  it('행 선택은 URL에 selected를 저장하고 상세를 표시한다', async () => {
    const { router, user } = renderAt('/journals?searched=true')
    await user.click(await screen.findByRole('button', { name: 'JRN-001' }))
    expect(await screen.findByRole('button', { name: '역분개' })).toBeEnabled()
    expect(router.state.location.search).toContain('selected=11')
  })
  it('역분개 사유 필수·trim·근거 제출·캐시 재조회·상태 갱신', async () => {
    let reversed = false
    const { user } = renderAt('/journals?selected=11', (url, options) => {
      if (url.endsWith('/reverse')) {
        expect(options?.body).toEqual({ reason: '정정 필요', evidenceRef: 'DOC-1' })
        reversed = true
        return { journalHeaderId: 12, reversalOfId: 11 }
      }
      if (url === '/api/v1/journals/11' && reversed)
        return { ...original, status: 'REVERSED', statusLabel: '역분개됨', reversedByJournalHeaderId: 12 }
    })
    await user.click(await screen.findByRole('button', { name: '역분개' }))
    await user.click(screen.getByRole('button', { name: '역분개 생성' }))
    expect(screen.getByText('역분개 사유를 입력하세요.')).toBeVisible()
    expect(calls.some((call) => call.url.endsWith('/reverse'))).toBe(false)
    await user.type(screen.getByRole('textbox', { name: '역분개 사유' }), ' 정정 필요 ')
    await user.type(screen.getByLabelText('증빙 참조'), ' DOC-1 ')
    await user.click(screen.getByRole('button', { name: '역분개 생성' }))
    expect(await within(screen.getByRole('region', { name: '분개 상세' })).findByText('역분개됨')).toBeVisible()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
  it('정정 예외 요청 응답 redirectUrl의 selected를 유지하여 예외함으로 이동한다', async () => {
    const { router, user } = renderAt('/journals?selected=11', (url, options) => {
      if (url.endsWith('/correction-exceptions')) {
        expect(options?.body).toEqual({ reason: '재기표 필요', evidenceRef: undefined })
        return { exceptionCaseId: 8, redirectUrl: '/exceptions?selected=8' }
      }
    })
    await user.click(await screen.findByRole('button', { name: '역분개 + 재기표' }))
    await user.type(screen.getByRole('textbox', { name: '정정 요청 사유' }), '재기표 필요')
    await user.click(screen.getByRole('button', { name: '예외함에서 정정 계속' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/exceptions'))
    expect(router.state.location.search).toContain('selected=8')
  })
  it('이미 역분개된 서버 오류 코드·메시지는 그대로 표시하고 모달을 유지한다', async () => {
    const { user } = renderAt('/journals?selected=11', (url) => {
      if (url.endsWith('/reverse'))
        throw new ApiError({ code: 'FGC-LEDG-004', message: '이미 역분개된 원장입니다.' }, 'trace-11', 409)
    })
    await user.click(await screen.findByRole('button', { name: '역분개' }))
    await user.type(screen.getByRole('textbox', { name: '역분개 사유' }), '정정')
    await user.click(screen.getByRole('button', { name: '역분개 생성' }))
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent(
      '이미 역분개된 원장입니다. (FGC-LEDG-004 · 요청 ID: trace-11)',
    )
  })
  it('COMPLIANCE는 역분개와 정정 요청 버튼이 비활성이다', async () => {
    renderAt('/journals?selected=11', undefined, 'COMPLIANCE')
    expect(await screen.findByRole('button', { name: '역분개' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '역분개 + 재기표' })).toBeDisabled()
  })
  it('역분개 원장·이미 역분개됨·작성중은 처리 버튼을 제공하지 않는다', async () => {
    renderAt('/journals?selected=11', (url) =>
      url === '/api/v1/journals/11' ? { ...original, journalType: 'REVERSAL' } : undefined,
    )
    expect(await screen.findByText('POSTED 상태이고 아직 역분개되지 않은 원분개만 처리할 수 있습니다.')).toBeVisible()
    expect(screen.queryByRole('button', { name: '역분개' })).not.toBeInTheDocument()
  })
  it('500 오류는 서버 상세 대신 requestId만 표시한다', async () => {
    renderAt('/journals?selected=11', (url) => {
      if (url === '/api/v1/journals/11')
        throw new ApiError({ code: 'FGC-COMMON-500', message: 'sensitive backend detail' }, 'trace-500', 500)
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: trace-500')
    expect(screen.queryByText(/sensitive backend/)).not.toBeInTheDocument()
  })
  it('불균형 필터 미지원 안내·초기화 후 검색 대기를 유지한다', async () => {
    const { user } = renderAt('/journals?searched=true&imbalanceOnly=true')
    expect(await screen.findByText(/불균형만 조회하는 목록 필터는 지원하지/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: '초기화' }))
    expect(await screen.findByText('조회 조건을 설정하고 조회 버튼을 눌러 주세요.')).toBeVisible()
  })
  it('계약번호는 프런트에서 자르지 않고 그대로 백엔드 검증에 맡긴다', () => {
    const long = 'A'.repeat(81)
    expect(criteriaFromParams(new URLSearchParams({ contractNo: ` ${long} ` })).contractNo).toBe(long)
  })
  it('날짜·enum·id 보정과 정정 요청 외부 이동 방지', () => {
    expect(
      criteriaFromParams(new URLSearchParams('from=2026-02-30&type=BAD&status=BAD&contractNo=%20FGC-A1%20&page=1abc')),
    ).toMatchObject({ from: '', type: '', status: '', contractNo: 'FGC-A1', page: 1 })
    expect(correctionTarget('/exceptions?selected=8')).toBe('/exceptions?selected=8')
    for (const url of [
      '//evil.test/exceptions',
      '/\\evil.test/exceptions',
      'https://evil.test/',
      '/login',
      '/journals',
      '/%65xceptions',
    ])
      expect(correctionTarget(url)).toBeNull()
  })
})
