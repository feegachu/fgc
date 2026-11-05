import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { workflowHelpSteps } from '../assistant/workflow-catalog'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient, ApiError } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toasts'
import { useWorkspaceStore } from '../../stores/workspace'
import { validateCorrectionLines } from './correctionLines'
import type { ExceptionCase, ExceptionOptions, ExceptionSearchResult } from './api'
import { applyActionToCache } from './api'

const user = {
  loginId: 'settle01',
  userName: '정산 담당자',
  roleCode: 'SETTLEMENT',
  demoMonth: '2026-07',
  canProcess: true,
  canViewAuditLog: false,
  canHandleException: true,
  canReverseJournal: true,
  canFinalizeValidation: false,
}
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'excp-test' })

const baseCase: ExceptionCase = {
  exceptionCaseId: 11,
  exceptionKey: 'KEY-11',
  type: 'CAP_VIOLATION',
  typeLabel: '1,200% 위반',
  reasonCode: 'CAP_LIMIT_VIOLATION',
  reasonLabel: '1,200% 한도 초과',
  severity: 'HIGH',
  severityLabel: '높음',
  status: 'NEW',
  statusLabel: '신규',
  title: '한도 초과 계약',
  description: '한도를 넘었습니다.',
  contractId: 3,
  contractNo: 'C-0001',
  agentName: '김설계',
  sourceEntityType: 'INSURANCE_CONTRACT',
  sourceEntityId: '3',
  sourceLink: '/contracts/3',
  validationMonth: '2026-07-01',
  firstDetectedRunId: 44,
  lastDetectedRunId: 44,
  firstDetectedAt: '2026-07-10T15:30:00Z',
  lastDetectedAt: '2026-07-10T15:30:00Z',
  detectionCount: 2,
  createdAt: '2026-07-10T15:30:00Z',
  occurrences: [
    {
      exceptionOccurrenceId: 1,
      validationRunId: 44,
      runNo: 2,
      validationMonth: '2026-07-01',
      sourceEntityType: 'CAP_CHECK',
      sourceEntityId: '9',
      detectionLabel: '신규',
      evidenceItems: [{ label: '한도액', value: '1,200,000원' }],
      detectedAt: '2026-07-10T15:30:00Z',
    },
  ],
  actions: [],
}
const correctionCase: ExceptionCase = {
  ...baseCase,
  exceptionCaseId: 12,
  type: 'JOURNAL_CORRECTION_REQUIRED',
  typeLabel: '원장 정정 필요',
  status: 'IN_REVIEW',
  statusLabel: '검토중',
  title: '원장 정정',
  sourceEntityType: 'JOURNAL_HEADER',
  sourceEntityId: '70',
  sourceLink: '/journals?selected=70',
  occurrences: [],
}
const options: ExceptionOptions = {
  types: [
    { code: 'CAP_VIOLATION', label: '1,200% 위반' },
    { code: 'JOURNAL_CORRECTION_REQUIRED', label: '원장 정정 필요' },
  ],
  reasons: [{ code: 'CAP_LIMIT_VIOLATION', label: '1,200% 한도 초과' }],
  severities: [{ code: 'HIGH', label: '높음' }],
  assignees: [{ userId: 7, loginId: 'settle01' }],
  validationMonths: ['2026-07-01'],
}
const search = (content: ExceptionCase[]): ExceptionSearchResult => ({
  summary: [{ type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 1234 }],
  content,
  page: 1,
  size: 20,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
  sort: 'severity,desc',
})
const journal = {
  journalHeaderId: 70,
  journalNo: 'J-0070',
  journalTypeLabel: '조정',
  journalDate: '2026-07-05',
  description: '원분개 설명',
  debitTotal: 1000,
  creditTotal: 1000,
  balanced: true,
  statusLabel: '기표 완료',
  lines: [
    { lineNo: 1, accountCode: '1100', accountName: '미수수수료', debitAmount: 1000, creditAmount: 0, memo: '차변' },
    { lineNo: 2, accountCode: '4100', accountName: '수수료수익', debitAmount: 0, creditAmount: 1000, memo: '대변' },
  ],
}
const accounts = [
  { accountCode: '1100', accountName: '미수수수료' },
  { accountCode: '4100', accountName: '수수료수익' },
]

const clients: QueryClient[] = []
let currentUser = user
let searchResponse: (path: string) => unknown
let postResponse: (path: string, body: unknown) => unknown

function requests() {
  return vi.mocked(apiClient.request).mock.calls
}
const searchPaths = () =>
  requests()
    .map(([path]) => path)
    .filter((path) => path.startsWith('/api/v1/exceptions?'))

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  useToastStore.setState({ messages: [] })
  sessionStorage.clear()
  currentUser = user
  searchResponse = () => search([baseCase])
  postResponse = () => {
    throw new Error('unexpected POST')
  }
  vi.spyOn(apiClient, 'request').mockImplementation((async (
    path: string,
    init?: { method?: string; body?: unknown },
  ) => {
    if (init?.method === 'POST') return envelope(await postResponse(path, init.body))
    if (path.startsWith('/api/v1/exceptions/options')) return envelope(options)
    if (path.startsWith('/api/v1/exceptions?')) return envelope(await searchResponse(path))
    if (path.startsWith('/api/v1/journals/accounts')) return envelope(accounts)
    if (path.startsWith('/api/v1/journals/70')) return envelope(journal)
    return envelope(currentUser)
  }) as typeof apiClient.request)
})
afterEach(() => {
  vi.restoreAllMocks()
  for (const client of clients.splice(0)) client.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

function renderPage(path = '/app/exceptions') {
  useAuthStore.getState().setAccessToken('access')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter(routes, { basename: '/app/', initialEntries: [path] })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup(), unmount: view.unmount }
}
const table = () => screen.findByRole('table', { name: '예외 목록' })

describe('EXCP-W01 예외함', () => {
  it('삭제된 지급의 반려 예외에는 재검토 폼과 원천 링크를 표시하지 않는다', async () => {
    searchResponse = () =>
      search([
        {
          ...baseCase,
          status: 'REJECTED',
          sourceEntityType: 'COMMISSION_TRANSACTION',
          sourceEntityId: '41',
          sourceLink: null,
          sourceDeleted: true,
        },
      ])
    renderPage('/app/exceptions?status=REJECTED')
    await userEvent.setup().click(within(await table()).getAllByRole('row')[1])
    expect(screen.queryByRole('option', { name: '재검토 시작' })).not.toBeInTheDocument()
    expect(document.querySelector('a[href*="transactions/new?id=41"]')).toBeNull()
    expect(screen.getAllByText('지급 건 41')).not.toHaveLength(0)
    expect(screen.queryByText(/COMMISSION_TRANSACTION/)).not.toBeInTheDocument()
  })

  it('미처리 기본값으로 요약 카드와 목록을 보여 준다', async () => {
    renderPage()
    const row = within(await table()).getAllByRole('row')[1]
    expect(within(row).getByText('1,200% 위반')).toBeInTheDocument()
    expect(within(row).getByText('C-0001')).toBeInTheDocument()
    // UTC 15:30 은 Asia/Seoul 로 다음 날 00:30 이다.
    expect(within(row).getByText('2026-07-11 00:30')).toBeInTheDocument()
    expect(within(row).getByText('2회')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '1,200% 위반 1,234건' })).toHaveAttribute(
      'href',
      '/app/exceptions?type=CAP_VIOLATION&status=OPEN&month=2026-07',
    )
    expect(searchPaths()[0]).toContain('status=OPEN')
    expect(searchPaths()[0]).toContain('page=1')
  })

  it('#604 헤더를 누르면 정렬 방향이 바뀌고 첫 페이지로 돌아가며 검색조건을 유지한다', async () => {
    searchResponse = (path) => ({
      ...search([baseCase]),
      page: Number(new URLSearchParams(path.split('?')[1]).get('page')),
      totalPages: 12,
      totalElements: 240,
    })
    const { router, actions } = renderPage('/app/exceptions?month=2026-07&contractNo=C-0001&page=4&selected=11')
    const list = await table()
    const lastQuery = () => new URLSearchParams(searchPaths().at(-1)!.split('?')[1])
    // 순서 기준이 없는 유형·상태 컬럼은 정렬 버튼이 없다.
    expect(within(list).queryByRole('button', { name: '상태' })).toBeNull()
    expect(within(list).getByRole('columnheader', { name: /^검출/ })).toHaveAttribute('aria-sort', 'none')
    await actions.click(within(list).getByRole('button', { name: '검출' }))
    await waitFor(() => expect(lastQuery().get('sort')).toBe('detectionCount,asc'))
    expect(lastQuery().get('page')).toBe('1')
    expect(lastQuery().get('contractNo')).toBe('C-0001')
    expect(lastQuery().get('status')).toBe('OPEN')
    expect(within(list).getByRole('columnheader', { name: /^검출/ })).toHaveAttribute('aria-sort', 'ascending')
    await actions.click(within(list).getByRole('button', { name: '검출' }))
    await waitFor(() => expect(router.state.location.search).toContain('sort=detectionCount%2Cdesc'))
    expect(within(list).getByRole('columnheader', { name: /^검출/ })).toHaveAttribute('aria-sort', 'descending')
    // 다른 컬럼을 누르면 그 컬럼이 오름차순으로 시작한다.
    await actions.click(within(list).getByRole('button', { name: '심각도' }))
    await waitFor(() => expect(lastQuery().get('sort')).toBe('severity,asc'))
  })
  it('#604 허용되지 않은 정렬 URL 값은 무시하고 서버 기본 정렬을 쓴다', async () => {
    renderPage('/app/exceptions?sort=status,asc')
    await table()
    expect(searchPaths().every((path) => !path.includes('sort='))).toBe(true)
  })

  it('상세 원인·참조 컬럼은 헤더와 내용을 가운데 정렬한다', async () => {
    renderPage()
    const list = await table()
    const headers = within(list).getAllByRole('columnheader')
    const centered = ['상세 원인', '참조']
    for (const name of centered) {
      const index = headers.findIndex((header) => header.textContent === name)
      expect(index).toBeGreaterThanOrEqual(0)
      expect(headers[index]).toHaveClass('is-center')
      const cell = within(list).getAllByRole('row')[1].querySelectorAll('td')[index]
      expect(cell).toHaveClass('is-center')
    }
    // 다른 컬럼의 정렬은 바뀌지 않는다.
    expect(headers.find((header) => header.textContent?.startsWith('계약'))).not.toHaveClass('is-center')
  })

  it('페이지네이션은 간결한 화살표와 5페이지 묶음으로 이동하며 검색조건을 유지한다', async () => {
    searchResponse = (path) => ({
      ...search([baseCase]),
      page: Number(new URLSearchParams(path.split('?')[1]).get('page')),
      totalPages: 12,
      totalElements: 240,
    })
    const { router, actions } = renderPage(
      '/app/exceptions?month=2026-07&validationMonth=2026-07&type=CAP_VIOLATION&status=OPEN&contractNo=C-0001&page=4&selected=11',
    )
    await screen.findByText('4 / 12 페이지')
    const pagination = () => screen.getByRole('navigation', { name: '예외 목록 페이지 선택' })
    const controls = () => within(pagination())
    expect(controls().queryByRole('button', { name: '처음' })).toBeNull()
    expect(controls().queryByRole('button', { name: '마지막' })).toBeNull()
    expect(pagination().querySelector('[aria-current="page"]')).toHaveTextContent('4')
    expect(pagination().querySelectorAll('.pagination-button')).toHaveLength(7)
    expect(controls().getByRole('button', { name: '이전 5페이지' })).toBeDisabled()
    await actions.click(controls().getByRole('button', { name: '다음 5페이지' }))
    await screen.findByText('6 / 12 페이지')
    const params = new URLSearchParams(router.state.location.search)
    for (const [key, value] of Object.entries({
      month: '2026-07',
      validationMonth: '2026-07',
      type: 'CAP_VIOLATION',
      status: 'OPEN',
      contractNo: 'C-0001',
    }))
      expect(params.get(key)).toBe(value)
    expect(params.has('selected')).toBe(false)
    await actions.click(controls().getByRole('button', { name: '이전 5페이지' }))
    await screen.findByText('5 / 12 페이지')
    await actions.click(controls().getByRole('button', { name: '3' }))
    await screen.findByText('3 / 12 페이지')
    await actions.click(controls().getByRole('button', { name: '다음 5페이지' }))
    await screen.findByText('6 / 12 페이지')
    await actions.click(controls().getByRole('button', { name: '다음 5페이지' }))
    await screen.findByText('11 / 12 페이지')
    expect(controls().getByRole('button', { name: '다음 5페이지' })).toBeDisabled()
    expect(pagination().querySelectorAll('.pagination-button')).toHaveLength(4)
  })

  it('전체 4페이지면 양쪽 묶음 화살표가 비활성이어도 숫자 페이지는 선택할 수 있다', async () => {
    searchResponse = (path) => ({
      ...search([baseCase]),
      page: Number(new URLSearchParams(path.split('?')[1]).get('page')),
      totalPages: 4,
      totalElements: 80,
    })
    const { actions } = renderPage('/app/exceptions?page=1')
    await screen.findByText('1 / 4 페이지')
    const controls = () => within(screen.getByRole('navigation', { name: '예외 목록 페이지 선택' }))
    expect(controls().getByRole('button', { name: '이전 5페이지' })).toBeDisabled()
    expect(controls().getByRole('button', { name: '다음 5페이지' })).toBeDisabled()
    await actions.click(controls().getByRole('button', { name: '4' }))
    await screen.findByText('4 / 4 페이지')
  })

  it('요약 카드의 한글 설명을 눌러도 원본 유형과 미처리·월 조건으로 조회한다', async () => {
    const { router, actions } = renderPage()
    const card = await screen.findByRole('link', { name: '1,200% 위반 1,234건' })
    expect(within(card).getByText('확정 위반 판정')).toBeInTheDocument()
    expect(within(card).queryByText('CAP_VIOLATION')).not.toBeInTheDocument()
    await actions.click(within(card).getByText('확정 위반 판정'))
    await waitFor(() => expect(router.state.location.search).toContain('type=CAP_VIOLATION'))
    const query = new URLSearchParams(searchPaths().at(-1)!.split('?')[1])
    expect(query.get('type')).toBe('CAP_VIOLATION')
    expect(query.get('status')).toBe('OPEN')
    expect(router.state.location.search).toContain('month=2026-07')
    expect(screen.getByRole('link', { name: '1,200% 위반 1,234건' })).toHaveAttribute('aria-current', 'true')
  })

  it('필터를 적용하면 URL 과 조회 조건이 바뀌고 초기화하면 되돌아간다', async () => {
    const { router, actions } = renderPage()
    await table()
    await screen.findByRole('option', { name: '(미배정)' })
    await actions.selectOptions(screen.getByLabelText('예외 유형'), 'CAP_VIOLATION')
    await actions.selectOptions(screen.getByLabelText('담당자'), 'unassigned')
    await actions.selectOptions(screen.getByLabelText('상태'), 'ALL')
    await actions.type(screen.getByLabelText('계약번호'), ' C-0001 ')
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).toContain('type=CAP_VIOLATION'))
    const last = new URLSearchParams(searchPaths().at(-1)!.split('?')[1])
    expect(last.get('type')).toBe('CAP_VIOLATION')
    expect(last.get('assigneeFilter')).toBe('unassigned')
    expect(last.get('contractNo')).toBe('C-0001')
    // 전체는 빈 status 로 보내야 서버가 미처리 기본값을 적용하지 않는다.
    expect(last.get('status')).toBe('')
    await actions.click(screen.getByRole('button', { name: '초기화' }))
    await waitFor(() => expect(router.state.location.search).toBe('?month=2026-07'))
  })

  it('새 유형 코드가 응답에 추가되어도 카드에 기본 안내를 표시한다', async () => {
    searchResponse = () => ({
      ...search([baseCase]),
      summary: [{ type: 'FUTURE_TYPE', typeLabel: '새 예외', count: 1 }],
    })
    renderPage()
    const card = await screen.findByRole('link', { name: '새 예외 1건' })
    expect(within(card).getByText('상세 내용 확인 필요')).toBeInTheDocument()
    expect(within(card).queryByText('FUTURE_TYPE')).not.toBeInTheDocument()
  })

  it('행을 선택하면 상세·검출 증거의 사용률을 한 자리로 표시하고 처리 이력을 보여 준다', async () => {
    searchResponse = () =>
      search([
        {
          ...baseCase,
          title: '한도 초과, 사용률 104.166667%',
          description: '1,200% 한도 초과(사용률 104.166667%).',
          occurrences: [
            {
              ...baseCase.occurrences![0],
              evidenceItems: [
                { label: '한도액', value: '1,200,000원' },
                { label: '사용률', value: '104.166667%' },
              ],
            },
          ],
          actions: [
            {
              actionSeq: 1,
              actionTypeLabel: '담당 배정',
              fromStatusLabel: '신규',
              toStatusLabel: '신규',
              reason: '배정합니다',
              actionByLoginId: 'settle01',
              actionAt: '2026-07-11T00:30:00+09:00',
            },
          ],
        },
      ])
    const { actions } = renderPage()
    await actions.click(within(await table()).getByRole('button', { name: '11' }))
    expect(await screen.findByText('1,200,000원')).toBeInTheDocument()
    expect(within(await table()).getByText('한도 초과, 사용률 104.1%')).toBeInTheDocument()
    expect(screen.getByText('한도 초과, 사용률 104.1%', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.getByText('1,200% 한도 초과(사용률 104.1%).', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.getByText('104.1%')).toBeInTheDocument()
    expect(screen.queryByText(/104\.166667%/)).not.toBeInTheDocument()
    expect(screen.getByText('1,200% 한도 초과', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.getByText('배정합니다')).toBeInTheDocument()
    // 계약 원천은 내부 번호 대신 계약번호로 읽는다.
    const references = screen.getAllByRole('link', { name: /^계약 C-0001/ })
    expect(references.length).toBeGreaterThan(0)
    for (const link of references) expect(link).toHaveAttribute('href', '/app/contracts/3')
    expect(screen.getByText('검증 실행', { exact: false, selector: '.exception-occurrence-meta' })).toHaveTextContent(
      '검증 실행 44, 1,200% 판정 9',
    )
  })

  it('저장된 설명과 처리 사유의 스크립트는 글자로만 보이고 실행되지 않는다 (SER-004)', async () => {
    const payload = '<img src=x onerror="alert(1)"><script>alert(2)</script>'
    searchResponse = () =>
      search([
        {
          ...baseCase,
          description: payload,
          actions: [
            {
              actionSeq: 1,
              actionTypeLabel: '담당 배정',
              fromStatusLabel: '신규',
              toStatusLabel: '신규',
              reason: payload,
              actionByLoginId: 'settle01',
              actionAt: '2026-07-11T00:30:00+09:00',
            },
          ],
        },
      ])
    const { actions } = renderPage()
    await actions.click(within(await table()).getByRole('button', { name: '11' }))
    expect(await screen.findByText(payload, { selector: '.exception-history-reason' })).toBeInTheDocument()
    expect(screen.getByText(payload, { selector: 'dd' })).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
    expect(document.querySelector('#root script, main script, [onerror]')).toBeNull()
  })

  it('사유 없이는 저장할 수 없고, 저장하면 이력과 상태가 갱신된다', async () => {
    postResponse = (path, body) => {
      expect(path).toBe('/api/v1/exceptions/11/actions')
      expect(body).toEqual({ actionType: 'ASSIGN', reason: '내가 맡습니다', evidenceRef: null })
      return {
        actionSeq: 1,
        fromStatus: 'NEW',
        toStatus: 'NEW',
        actionType: 'ASSIGN',
        actionTypeLabel: '담당 배정',
        fromStatusLabel: '신규',
        toStatusLabel: '신규',
        reason: '내가 맡습니다',
        actionBy: 7,
        actionByLoginId: 'settle01',
        actionAt: '2026-07-11T01:00:00+09:00',
      }
    }
    const { actions } = renderPage()
    await actions.click(within(await table()).getByRole('button', { name: '11' }))
    const save = await screen.findByRole('button', { name: '처리 저장' })
    expect(save).toBeDisabled()
    await actions.selectOptions(screen.getByLabelText(/조치/), 'ASSIGN')
    expect(save).toBeDisabled()
    await actions.type(screen.getByLabelText(/처리 사유/), '내가 맡습니다')
    await actions.click(save)
    expect(await screen.findByText('1. 담당 배정')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: '예외 목록' })).getByText('settle01')).toBeInTheDocument()
  })

  it('서버가 처리를 거절하면 오류를 표시하고 폼을 유지한다', async () => {
    postResponse = () => {
      throw new ApiError(
        { code: 'FGC-EXCP-003', message: '처리할 수 없습니다. 현재 상태에서는 수행할 수 없습니다.' },
        'req-9',
        409,
      )
    }
    const { actions } = renderPage()
    await actions.click(within(await table()).getByRole('button', { name: '11' }))
    await actions.selectOptions(await screen.findByLabelText(/조치/), 'START_REVIEW')
    await actions.type(screen.getByLabelText(/처리 사유/), '검토')
    await actions.click(screen.getByRole('button', { name: '처리 저장' }))
    expect(await screen.findByText(/처리할 수 없습니다/, { selector: '.exception-action-error' })).toBeInTheDocument()
    expect(screen.getByLabelText(/처리 사유/)).toHaveValue('검토')
  })

  it('처리 권한이 없으면 조회만 가능하다', async () => {
    currentUser = { ...user, roleCode: 'COMPLIANCE', canProcess: false, canHandleException: false }
    const { actions } = renderPage()
    await actions.click(within(await table()).getByRole('button', { name: '11' }))
    expect(await screen.findByText('조회 전용 권한입니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '처리 저장' })).toBeNull()
  })

  it('결과가 없으면 빈 상태를, 조회 오류는 재시도 버튼을 보여 준다', async () => {
    searchResponse = () => search([])
    const first = renderPage()
    expect(await screen.findByText('조건에 맞는 자료가 없습니다.')).toBeInTheDocument()
    expect(first.router.state.location.pathname).toBe('/app/exceptions')
  })

  it('조회 오류는 안내하고 다시 시도하면 복구한다', async () => {
    let fail = true
    searchResponse = () => {
      if (fail) throw new ApiError({ code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다.' }, 'req-1', 500)
      return search([baseCase])
    }
    const { actions } = renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('처리 중 오류가 발생했습니다.')
    fail = false
    await actions.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await table()).toBeInTheDocument()
  })

  it.each([false, true])('원장 정정은 실제 서버 성공 뒤 결과를 표시한다 (도움말=%s)', async (guided) => {
    vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
    searchResponse = () => search([correctionCase])
    postResponse = (path, body) => {
      expect(path).toBe('/api/v1/exceptions/12/journal-correction')
      expect(body).toMatchObject({
        reason: '계정 오류 정정',
        journalDate: '2026-07-05',
        description: '원분개 설명',
        lines: [
          { originalLineNo: 1, accountCode: '1100', debitAmount: 1000, creditAmount: 0, lineDescription: '차변' },
          { originalLineNo: 2, accountCode: '4100', debitAmount: 0, creditAmount: 1000, lineDescription: '대변' },
        ],
      })
      return {
        actionSeq: 2,
        fromStatus: 'IN_REVIEW',
        toStatus: 'RESOLVED',
        actionType: 'CORRECT',
        reason: '계정 오류 정정',
        actionBy: 7,
        actionByLoginId: 'settle01',
        actionAt: '2026-07-11T01:00:00+09:00',
        originalJournalHeaderId: 70,
        reversalJournalHeaderId: 71,
        repostedJournalHeaderId: 72,
        correctionGroupKey: 'CG-1',
      }
    }
    const { actions } = renderPage()
    await actions.click(within(await table()).getByRole('button', { name: '12' }))
    // 원장 정정 예외의 처리 폼에는 일반 조치만 나온다.
    const actionSelect = await screen.findByLabelText(/조치/)
    expect(within(actionSelect).queryByRole('option', { name: '해결' })).toBeNull()
    expect(within(actionSelect).getByRole('option', { name: '오탐' })).toBeInTheDocument()
    await actions.click(screen.getByRole('button', { name: '원장 정정 계속' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/J-0070/)).toBeInTheDocument()
    expect(within(dialog).getByText('차변/대변 검증 통과')).toBeInTheDocument()
    // 계정과목은 한글명만 보이고 내부 코드는 병기하지 않는다
    expect(within(dialog).getAllByText('미수수수료').length).toBeGreaterThan(0)
    expect(within(dialog).queryByText(/1100|4100/)).not.toBeInTheDocument()
    if (guided) {
      await actions.click(within(dialog).getByRole('button', { name: '도움말' }))
      expect(within(dialog).getByRole('button', { name: '다음' })).toBeDisabled()
    }
    await actions.type(within(dialog).getByLabelText(/정정 사유/), '계정 오류 정정')
    if (guided) {
      await waitFor(() => expect(within(dialog).getByRole('button', { name: '다음' })).toBeEnabled())
      await actions.click(within(dialog).getByRole('button', { name: '다음' }))
      expect(requests().some(([path]) => path.endsWith('/journal-correction'))).toBe(false)
      expect(within(dialog).getByRole('button', { name: '다음' })).toBeDisabled()
    }
    await actions.click(within(dialog).getByRole('button', { name: '정정 실행' }))
    if (guided) {
      await waitFor(() => expect(within(dialog).getByRole('button', { name: '다음' })).toBeEnabled())
      expect(within(dialog).queryByText('역분개와 재기표를 완료했습니다.')).toBeNull()
      await actions.click(within(dialog).getByRole('button', { name: '다음' }))
      expect(await within(dialog).findByText('서버 정정 확인 · 가이드 완료')).toBeVisible()
      await actions.click(within(dialog).getByRole('button', { name: '안내 마치기' }))
    }
    expect(await within(dialog).findByText('역분개와 재기표를 완료했습니다.')).toBeInTheDocument()
    expect(within(dialog).getByText('정정 묶음으로 연결했습니다.')).toBeInTheDocument()
    expect(within(dialog).queryByText(/CG-1|#72/)).not.toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /^재기표/ })).toHaveAttribute('href', '/app/journals?selected=72')
  })
})

describe('원장 정정 입력 검증', () => {
  const lines = (debit: string, credit: string) => [
    { debitAmount: debit, creditAmount: '0' },
    { debitAmount: '0', creditAmount: credit },
  ]
  it('균형 잡힌 한쪽씩 입력은 통과한다', () => {
    expect(validateCorrectionLines(lines('1000', '1000'))).toBeNull()
    // 쓰지 않는 쪽을 비워도 0 으로 본다.
    expect(
      validateCorrectionLines([
        { debitAmount: '500', creditAmount: '' },
        { debitAmount: '', creditAmount: '500' },
      ]),
    ).toBeNull()
  })
  it('양쪽이 모두 0 이거나 모두 양수인 줄을 거부한다', () => {
    expect(validateCorrectionLines([{ debitAmount: '', creditAmount: '' }])).toContain('1번 줄')
    expect(validateCorrectionLines([{ debitAmount: '10', creditAmount: '10' }])).toContain('한쪽에만')
  })
  it('음수·소수·차대 불균형·0원 합계를 거부한다', () => {
    expect(validateCorrectionLines([{ debitAmount: '-1', creditAmount: '0' }])).toContain('정수')
    expect(validateCorrectionLines([{ debitAmount: '1.5', creditAmount: '0' }])).toContain('정수')
    expect(validateCorrectionLines(lines('1000', '900'))).toContain('같아야')
  })
})

it('양수였던 금액을 지워 두 금액이 모두 0이 되면 요청을 보내지 않고 안내한다', async () => {
  searchResponse = () => search([correctionCase])
  const { actions } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '12' }))
  await actions.click(await screen.findByRole('button', { name: '원장 정정 계속' }))
  const dialog = await screen.findByRole('dialog')
  await within(dialog).findByText(/J-0070/)
  await actions.type(within(dialog).getByLabelText(/정정 사유/), '사유')
  const debit = within(dialog).getAllByLabelText(/차변/)[0]
  await actions.clear(debit)
  await actions.type(debit, '0')
  await actions.click(within(dialog).getByRole('button', { name: '정정 실행' }))
  expect(await within(dialog).findByText(/1번 줄은 차변 또는 대변 중 한쪽에만/)).toBeInTheDocument()
  expect(requests().some(([path]) => path.endsWith('/journal-correction'))).toBe(false)
})

it('action help requires a real action sequence and preserves input for a failed retry', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
  let attempts = 0
  postResponse = () => {
    attempts++
    if (attempts === 1) throw new ApiError({ code: 'FGC-EXCP-003', message: '상태 충돌' }, 'guide-conflict', 409)
    return { exceptionActionId: undefined, actionSeq: 1, actionType: 'START_REVIEW', actionTypeLabel: '검토 시작', fromStatus: 'NEW', toStatus: 'IN_REVIEW', toStatusLabel: '검토중', reason: '원천 확인' }
  }
  const { actions } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '11' }))
  const form = within(await screen.findByRole('form', { name: '예외 처리' }))
  await actions.click(form.getByRole('button', { name: '도움말' }))
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  await actions.selectOptions(form.getByLabelText(/조치/), 'START_REVIEW')
  await actions.type(form.getByLabelText(/처리 사유/), '원천 확인')
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  await actions.click(screen.getByRole('button', { name: '다음' }))
  expect(attempts).toBe(0)
  await actions.click(form.getByRole('button', { name: '처리 저장' }))
  await screen.findByText(/상태 충돌/, { selector: '.exception-action-error' })
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  expect(form.getByLabelText(/처리 사유/)).toHaveValue('원천 확인')
  await actions.click(form.getByRole('button', { name: '처리 저장' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  expect(screen.queryByText('1. 검토 시작')).not.toBeInTheDocument()
  await actions.click(screen.getByRole('button', { name: '다음' }))
  expect(await screen.findByText('서버 처리 확인 · 가이드 완료')).toBeVisible()
  await actions.click(screen.getByRole('button', { name: '안내 마치기' }))
  expect(await screen.findByText('1. 검토 시작')).toBeVisible()
})

it('resolved journal corrections expose read-only results without a write guide', async () => {
  searchResponse = () => search([{ ...correctionCase, status: 'RESOLVED', statusLabel: '해결' }])
  const { actions } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '12' }))
  await actions.click(await screen.findByRole('button', { name: '원장 정정 확인' }))
  const dialog = await screen.findByRole('dialog')
  await within(dialog).findByText(/J-0070/)
  expect(within(dialog).queryByRole('button', { name: '도움말' })).not.toBeInTheDocument()
  expect(within(dialog).queryByRole('button', { name: '정정 실행' })).not.toBeInTheDocument()
  expect(requests().some(([path]) => path.endsWith('/journal-correction'))).toBe(false)
})


it.each(['status', 'sequence', 'pending refresh'])('committed action with %s mismatch refreshes and blocks resubmission', async (kind) => {
  let finish: ((value: unknown) => void) | undefined
  const response = { actionSeq: kind === 'sequence' ? 0 : 1, actionType: 'START_REVIEW', fromStatus: kind === 'status' ? 'IN_REVIEW' : 'NEW', toStatus: 'IN_REVIEW' }
  postResponse = () => kind === 'pending refresh' ? new Promise(resolve => { finish = resolve }) : response
  const { actions } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '11' }))
  const form = await screen.findByRole('form', { name: '예외 처리' })
  await actions.selectOptions(within(form).getByLabelText(/조치/), 'START_REVIEW')
  await actions.type(within(form).getByLabelText(/처리 사유/), '원천 확인')
  const reads = searchPaths().length
  await actions.click(within(form).getByRole('button', { name: '처리 저장' }))
  if (kind === 'pending refresh') {
    await waitFor(() => expect(finish).toBeDefined())
    await act(async () => { clients[0].setQueriesData({ queryKey: ['exceptions', 'search'] }, search([{ ...baseCase, status: 'IN_REVIEW' }])) })
    await waitFor(() => expect(within(form).queryByRole('option', { name: '검토 시작' })).not.toBeInTheDocument())
    await act(async () => { finish!(response) })
  }
  expect(await within(form).findByText(/서버 저장 성공 응답을 받았지만 결과 확인/)).toBeVisible()
  await waitFor(() => expect(searchPaths().length).toBeGreaterThan(reads))
  expect(within(form).getByRole('button', { name: '처리 저장' })).toBeDisabled()
  fireEvent.submit(form)
  expect(within(form).getByText(/서버 저장 성공 응답을 받았지만 결과 확인/)).toBeVisible()
  expect(requests().filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})

it('committed correction with an invalid receipt refreshes and cannot be posted twice', async () => {
  searchResponse = () => search([correctionCase])
  postResponse = () => ({ actionType: 'CORRECT', originalJournalHeaderId: 70, reversalJournalHeaderId: 71, repostedJournalHeaderId: 0, toStatus: 'RESOLVED' })
  const { actions } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '12' }))
  await actions.click(await screen.findByRole('button', { name: '원장 정정 계속' }))
  const dialog = await screen.findByRole('dialog')
  await within(dialog).findByText(/J-0070/)
  await actions.type(within(dialog).getByLabelText(/정정 사유/), '계정 오류 정정')
  const reads = searchPaths().length
  await actions.click(within(dialog).getByRole('button', { name: '정정 실행' }))
  expect(await within(dialog).findByText(/서버 정정 성공 응답을 받았지만 결과 확인/)).toBeVisible()
  await waitFor(() => expect(searchPaths().length).toBeGreaterThan(reads))
  expect(within(dialog).getByRole('button', { name: '정정 실행' })).toBeDisabled()
  fireEvent.submit(dialog.querySelector('form')!)
  expect(requests().filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})

it('does not append a cached action twice or overwrite a newer cached status', () => {
  const client = new QueryClient()
  const action = { actionSeq: 1, actionType: 'START_REVIEW', toStatus: 'IN_REVIEW' } as const
  client.setQueryData(['exceptions', 'search'], search([{ ...baseCase, status: 'RESOLVED', actions: [action] }]))
  applyActionToCache(client, 11, action)
  const item = client.getQueryData<ExceptionSearchResult>(['exceptions', 'search'])!.content![0]
  expect(item.actions).toHaveLength(1)
  expect(item.status).toBe('RESOLVED')
  client.clear()
})

it.each([[false, 1], [true, 1], [false, 0]] as const)('updates or refreshes a committed action after unmount (help=%s, sequence=%s)', async (guided, sequence) => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  let finish!: (value: unknown) => void
  postResponse = () => new Promise(resolve => { finish = resolve })
  const { actions, unmount } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '11' }))
  const form = within(await screen.findByRole('form', { name: '예외 처리' }))
  await actions.selectOptions(form.getByLabelText(/조치/), 'START_REVIEW')
  await actions.type(form.getByLabelText(/처리 사유/), '원천 확인')
  if (guided) {
    await actions.click(form.getByRole('button', { name: '도움말' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
    await actions.click(screen.getByRole('button', { name: '다음' }))
  }
  await actions.click(form.getByRole('button', { name: '처리 저장' }))
  await waitFor(() => expect(finish).toBeDefined())
  const receipt = { actionSeq: sequence, actionType: 'START_REVIEW', fromStatus: 'NEW', toStatus: 'IN_REVIEW' }
  if (guided) {
    await act(async () => finish(receipt))
    await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  }
  unmount()
  if (!guided) await act(async () => finish(receipt))
  await waitFor(() => {
    const [, data] = clients[0].getQueriesData<ExceptionSearchResult>({ queryKey: ['exceptions', 'search'] })[0]
    expect(data!.content![0].status).toBe(sequence ? 'IN_REVIEW' : 'NEW')
    expect(data!.content![0].actions).toHaveLength(sequence ? 1 : 0)
    expect(clients[0].getQueriesData({ queryKey: ['exceptions', 'search'] }).every(([key]) => clients[0].getQueryState(key)?.isInvalidated)).toBe(true)
  })
})

it('invalidates a completed correction when help is unmounted before acknowledgement', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  searchResponse = () => search([correctionCase])
  postResponse = () => ({ actionSeq: 2, actionType: 'CORRECT', fromStatus: 'IN_REVIEW', toStatus: 'RESOLVED', originalJournalHeaderId: 70, reversalJournalHeaderId: 71, repostedJournalHeaderId: 72 })
  const { actions, unmount } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '12' }))
  await actions.click(await screen.findByRole('button', { name: '원장 정정 계속' }))
  const dialog = within(await screen.findByRole('dialog'))
  await dialog.findByText(/J-0070/)
  await actions.type(dialog.getByLabelText(/정정 사유/), '계정 오류 정정')
  await actions.click(dialog.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(dialog.getByRole('button', { name: '다음' })).toBeEnabled())
  await actions.click(dialog.getByRole('button', { name: '다음' }))
  clients[0].setQueryData(['validation-runs', 'receipt-test'], { status: 'COMPLETED' })
  await actions.click(dialog.getByRole('button', { name: '정정 실행' }))
  await waitFor(() => expect(dialog.getByRole('button', { name: '다음' })).toBeEnabled())
  unmount()
  await waitFor(() => expect(clients[0].getQueryState(['validation-runs', 'receipt-test'])?.isInvalidated).toBe(true))
})

it('차익거래 판정 사유로 저장된 예외 설명의 개발 용어는 쉬운 말로 보이고 다른 설명은 그대로 둔다', async () => {
  searchResponse = () =>
    search([
      {
        ...baseCase,
        type: 'DATA_QUALITY',
        typeLabel: '데이터 품질',
        title: '차익거래 검증 자료 확인 필요',
        description: '기준일 이하 계약 금융 스냅샷이 없습니다.',
        sourceEntityType: 'ARBITRAGE_CHECK',
        sourceEntityId: '5',
        sourceLink: '/arbitrage-checks',
      },
      { ...baseCase, exceptionCaseId: 13, description: '한도를 넘었습니다.' },
    ])
  const { actions } = renderPage()
  await actions.click(within(await table()).getByRole('button', { name: '11' }))
  expect(await screen.findByText('기준일 이전 계약 금액 자료가 없습니다.', { selector: 'dd' })).toBeInTheDocument()
  expect(screen.queryByText(/스냅샷/)).not.toBeInTheDocument()
  await actions.click(within(await table()).getByRole('button', { name: '13' }))
  expect(await screen.findByText('한도를 넘었습니다.', { selector: 'dd' })).toBeInTheDocument()
})


it('gives different exception summary types distinct guide targets and preserves unknown types', async () => {
  searchResponse = () => ({ ...search([baseCase]), summary: [
    { type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 2 },
    { type: 'JOURNAL_CORRECTION_REQUIRED', typeLabel: '원장 정정 필요', count: 3 },
    { type: 'FUTURE_TYPE', typeLabel: '새 예외', count: 1 },
  ] })
  renderPage()
  await table()
  for (const [type, label] of [['cap_violation', '1,200% 위반'], ['journal_correction_required', '원장 정정 필요']]) {
    const id = `exception.summary.${type}`
    const nodes = document.querySelectorAll(`[data-guide-feature="${id}"]`)
    expect(nodes).toHaveLength(1)
    expect(nodes[0]).toHaveTextContent(label)
    expect(nodes[0]).toHaveAttribute('href', expect.stringContaining(`type=${type.toUpperCase()}`))
    expect(workflowHelpSteps('exception.resolve', 'COMPLIANCE').some((step) => step.targetId === id)).toBe(true)
  }
  expect(screen.getByRole('link', { name: '새 예외 1건' })).not.toHaveAttribute('data-guide-feature')
  expect(document.querySelector('[data-guide-feature="features.exception.exception-page.link-3"]')).toBeNull()
})
