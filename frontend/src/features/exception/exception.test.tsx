import { render, screen, waitFor, within } from '@testing-library/react'
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
import type { ExceptionCase, ExceptionOptions, ExceptionSearchResult } from './api'

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
  sort: 'severity,asc,createdAt,desc',
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
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup() }
}
const table = () => screen.findByRole('table', { name: '예외 목록' })

describe('EXCP-W01 예외함', () => {
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

  it('행을 선택하면 상세·검출 이력·처리 이력을 보여 준다', async () => {
    searchResponse = () =>
      search([
        {
          ...baseCase,
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
    expect(screen.getByText('1,200% 한도 초과', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.getByText('배정합니다')).toBeInTheDocument()
    for (const link of screen.getAllByRole('link', { name: /INSURANCE_CONTRACT:3/ }))
      expect(link).toHaveAttribute('href', '/app/contracts/3')
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
        { code: 'FGC-EXCP-003', message: '처리 불가 — 현재 상태에서는 수행할 수 없습니다.' },
        'req-9',
        409,
      )
    }
    const { actions } = renderPage()
    await actions.click(within(await table()).getByRole('button', { name: '11' }))
    await actions.selectOptions(await screen.findByLabelText(/조치/), 'START_REVIEW')
    await actions.type(screen.getByLabelText(/처리 사유/), '검토')
    await actions.click(screen.getByRole('button', { name: '처리 저장' }))
    expect(await screen.findByText(/처리 불가/, { selector: '.exception-action-error' })).toBeInTheDocument()
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

  it('원장 정정 예외는 원분개를 불러와 재기표를 요청하고 결과 링크를 보여 준다', async () => {
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
    expect(within(dialog).getByText('차변·대변 검증 통과')).toBeInTheDocument()
    await actions.type(within(dialog).getByLabelText(/정정 사유/), '계정 오류 정정')
    await actions.click(within(dialog).getByRole('button', { name: '정정 실행' }))
    expect(await within(dialog).findByText('역분개와 재기표를 완료했습니다.')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: '재기표 #72' })).toHaveAttribute(
      'href',
      '/app/journals?selected=72',
    )
  })
})
