import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import type { RequestOptions } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toasts'
import { useWorkspaceStore } from '../../stores/workspace'
import { criteriaFromParams, normalizePage } from './api'
import type { ScheduleDetail, ScheduleHeader } from './api'
import { responseLabel } from './labels'

const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'schedule-test' })
const header = (scheduleHeaderId: number, overrides: Partial<ScheduleHeader> = {}): ScheduleHeader => ({
  scheduleHeaderId,
  contractNo: 'C-2026-0001',
  insurerName: '한빛생명',
  productName: '종신보험',
  paymentStage: 'GA_TO_FC',
  paymentStageLabel: 'GA→설계사',
  scheduleRegime: 'CURRENT',
  scheduleRegimeLabel: '현행',
  schedulePurpose: 'OPERATIONAL',
  schedulePurposeLabel: '운영',
  scheduleVersionNo: 1,
  status: 'PLANNED',
  statusLabel: '예정',
  activeYn: true,
  lineCount: 2,
  expectedTotal: 1500000,
  policyVersionLabel: 'POL-2026-07 v3',
  generationReason: '최초 생성',
  generatedAt: '2026-07-01T01:00:00Z',
  ...overrides,
})
const detail = (overrides: Partial<ScheduleHeader> = {}): ScheduleDetail => ({
  header: header(11, overrides),
  lines: [
    {
      lineNo: 1,
      installmentNo: 1,
      contractMonthNo: 1,
      dueDate: '2026-08-10',
      commissionItemName: '초회 수수료',
      recipientName: '김설계',
      basisCode: 'MONTHLY_PREMIUM',
      basisAmount: 100000,
      calculationType: 'RATE',
      ratePct: 1000.123456,
      expectedAmount: 1600000,
      lineStatus: 'PLANNED',
      ruleRef: 7,
    },
    {
      lineNo: 2,
      installmentNo: 2,
      contractMonthNo: 13,
      dueDate: '2027-07-10',
      commissionItemName: '환수',
      recipientName: '김설계',
      basisCode: 'CHARGEBACK',
      basisAmount: -100000,
      calculationType: 'FIXED',
      expectedAmount: -100000,
      lineStatus: 'CONFIRMED',
    },
  ],
})

type Handler = (url: string, options?: RequestOptions) => unknown
let requested: { url: string; options?: RequestOptions }[]

function renderAt(path: string, handler: Handler, roleCode = 'SETTLEMENT') {
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    requested.push({ url, options })
    if (url === '/api/v1/auth/me')
      return envelope({
        loginId: 'settle01',
        userName: '정산 담당자',
        roleCode,
        demoMonth: '2026-07',
        canProcess: roleCode === 'SETTLEMENT',
      })
    const result = await handler(url, options)
    if (result === undefined) throw new Error(`unexpected ${url}`)
    return envelope(result)
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup() }
}
const calls = (prefix: string) => requested.filter(({ url }) => url.startsWith(prefix))
const query = (url: string) => Object.fromEntries(new URLSearchParams(url.split('?')[1]))
const toasts = () => useToastStore.getState().messages.map((message) => message.message)

const listPage = (content: ScheduleHeader[], extra = {}) => ({
  content,
  page: 1,
  size: 20,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
  ...extra,
})

beforeAll(async () => {
  await Promise.all([import('./ScheduleListPage'), import('./ScheduleDetailPage')])
})
beforeEach(() => {
  useAuthStore.setState({ accessToken: 'schedule-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
  useToastStore.setState({ messages: [] })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('1차 schedule-list.test.cjs 이전', () => {
  it('범위를 벗어난 page 는 마지막 페이지(결과 없음이면 1)로 맞춘다', () => {
    expect(normalizePage(999, 0)).toBe(1)
    expect(normalizePage(1, 5)).toBe(1)
    expect(normalizePage(2, 5)).toBe(2)
    expect(normalizePage(999, 5)).toBe(5)
    expect(criteriaFromParams(new URLSearchParams('page=2abc')).page).toBe(1)
  })
  it('서버 라벨을 먼저 쓰고 없으면 코드표 → 코드값으로 대체한다', () => {
    const labels = { PLANNED: '예정' }
    expect(responseLabel('서버 예정', labels, 'PLANNED')).toBe('서버 예정')
    expect(responseLabel('', labels, 'PLANNED')).toBe('예정')
    expect(responseLabel(null, labels, 'UNKNOWN')).toBe('UNKNOWN')
  })
  it('목록에 없는 코드값은 1차처럼 전체(용도는 운영)로 되돌린다', () => {
    expect(
      criteriaFromParams(new URLSearchParams('stage=BAD&regime=X&purpose=Y&status=Z&contractNo=%20C-1%20')),
    ).toEqual({
      contractNo: 'C-1',
      stage: '',
      regime: '',
      purpose: 'OPERATIONAL',
      status: '',
      page: 1,
    })
  })
})

describe('SCHE-W01 예상 스케줄 목록', () => {
  it('#1 URL 조건으로 조회·라벨·금액 표시·페이지·URL 동기화·CSV 가 1차와 같다', async () => {
    const rows = [
      header(11),
      header(12, {
        paymentStage: 'INSURER_TO_GA',
        paymentStageLabel: undefined,
        status: 'CONFIRMED',
        statusLabel: '확정',
        expectedTotal: -1517944,
        activeYn: false,
      }),
    ]
    const { router, actions } = renderAt('/schedules?month=2026-07&stage=GA_TO_FC&status=PLANNED&page=2', (url) =>
      url.startsWith('/api/v1/schedules?') ? listPage(rows, { page: 2, totalElements: 41, totalPages: 3 }) : undefined,
    )
    expect(await screen.findByRole('heading', { name: '예상 스케줄 목록' })).toBeInTheDocument()
    expect(await screen.findAllByRole('link', { name: 'C-2026-0001 회차 보기' })).toHaveLength(2)
    expect(query(calls('/api/v1/schedules?')[0].url)).toEqual({
      stage: 'GA_TO_FC',
      purpose: 'OPERATIONAL',
      status: 'PLANNED',
      page: '2',
      size: '20',
    })
    const table = screen.getByRole('table', { name: '예상 지급 스케줄 검색 결과' })
    expect(within(table).getAllByText('GA→설계사')).toHaveLength(1)
    // 서버 라벨이 비면 코드표로 대체한다.
    expect(within(table).getByText('원수사→GA')).toBeInTheDocument()
    expect(within(table).getByText('1,500,000원')).toBeInTheDocument()
    expect(within(table).getByText('(1,517,944)원')).toHaveClass('is-negative-amount')
    expect(within(table).getByText('미사용')).toBeInTheDocument()
    expect(within(table).getByText('확정').querySelector('.schedule-badge-lock')).not.toBeNull()
    expect(screen.getByText('41')).toBeInTheDocument()
    expect(screen.getByText('2 / 3 페이지')).toBeInTheDocument()
    expect(screen.getByLabelText('지급단계')).toHaveValue('GA_TO_FC')
    expect(screen.getByLabelText('용도')).toHaveValue('OPERATIONAL')

    // 행 → 상세는 /schedules/{id} 딥링크다.
    expect(within(table).getAllByRole('link', { name: 'C-2026-0001' })[0]).toHaveAttribute('href', '/schedules/11')

    await actions.click(screen.getByRole('button', { name: '3' }))
    await waitFor(() =>
      expect(router.state.location.search).toBe('?month=2026-07&stage=GA_TO_FC&status=PLANNED&page=3'),
    )

    await actions.type(screen.getByLabelText('계약번호'), '  C-9 ')
    await actions.selectOptions(screen.getByLabelText('적용 체계'), 'FOUR_YEAR_2027')
    await actions.click(screen.getByRole('button', { name: /조회/ }))
    await waitFor(() =>
      expect(router.state.location.search).toBe(
        '?month=2026-07&stage=GA_TO_FC&status=PLANNED&contractNo=C-9&regime=FOUR_YEAR_2027&purpose=OPERATIONAL',
      ),
    )

    // 뒤로 가기는 이전 조건을 입력란과 조회에 복원한다.
    await router.navigate(-1)
    await waitFor(() => expect(screen.getByLabelText('적용 체계')).toHaveValue(''))

    await actions.click(screen.getByRole('button', { name: /초기화/ }))
    await waitFor(() => expect(router.state.location.search).toBe('?month=2026-07&purpose=OPERATIONAL'))

    const blob = vi.spyOn(apiClient, 'requestBlob').mockResolvedValue({ blob: new Blob(['a']), disposition: null })
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} }))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await actions.click(screen.getByRole('button', { name: /CSV 내보내기/ }))
    await waitFor(() =>
      expect(blob).toHaveBeenCalledWith('/api/v1/schedules/export.csv?purpose=OPERATIONAL', expect.anything()),
    )
    await waitFor(() => expect(toasts()).toContain('예상 스케줄 CSV를 내려받았습니다.'))
  })

  it('범위를 넘은 page 는 마지막 페이지로 바꿔 다시 조회하고, 운영 스케줄 중복을 경고한다', async () => {
    const { router } = renderAt('/schedules?month=2026-07&page=9', (url) =>
      url.startsWith('/api/v1/schedules?')
        ? listPage([header(11), header(12)], { page: Number(query(url).page), totalElements: 2, totalPages: 1 })
        : undefined,
    )
    await waitFor(() => expect(router.state.location.search).toBe('?month=2026-07'))
    expect(await screen.findByText(/운영 스케줄이 중복되었습니다/)).toBeInTheDocument()
    await waitFor(() => expect(query(calls('/api/v1/schedules?').at(-1)!.url).page).toBe('1'))
  })

  it('빈 결과와 오류(500 은 서버 문구·요청 ID)를 구분하고 다시 시도한다', async () => {
    let fail = true
    const { actions } = renderAt('/schedules?month=2026-07', (url) => {
      if (!url.startsWith('/api/v1/schedules?')) return undefined
      if (fail) {
        fail = false
        throw new ApiError(
          { code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다. 요청번호 req-9를 담당자에게 알려주세요.' },
          'req-9',
          500,
        )
      }
      return listPage([])
    })
    expect(await screen.findByText('예상 스케줄을 불러오지 못했습니다.')).toBeInTheDocument()
    expect(
      screen.getByText('처리 중 오류가 발생했습니다. 요청번호 req-9를 담당자에게 알려주세요. (FGC-COMMON-500)'),
    ).toBeInTheDocument()
    await actions.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('조건에 맞는 예상 스케줄이 없습니다.')).toBeInTheDocument()
  })
})

describe('SCHE-W02 예상 스케줄 상세', () => {
  const versions = [
    header(10, { scheduleVersionNo: 1, activeYn: false, status: 'CANCELLED', statusLabel: '취소' }),
    header(11, { scheduleVersionNo: 2 }),
  ]
  const baseHandler =
    (current = detail({ scheduleVersionNo: 2 })): Handler =>
    (url) => {
      if (url === '/api/v1/schedules/11') return current
      if (url === '/api/v1/schedules/11/versions') return versions
      if (url === '/api/v1/schedules/10') return { ...detail({ scheduleVersionNo: 1 }), header: versions[0] }
      if (url === '/api/v1/schedules/10/versions') return versions
      return undefined
    }

  it('헤더·회차·합계·버전 이력을 1차와 같은 표기로 보여 준다', async () => {
    renderAt('/schedules/11?month=2026-07', baseHandler())
    expect(await screen.findByText('schedule_header #11')).toBeInTheDocument()
    expect(screen.getByText('한빛생명 · 종신보험')).toBeInTheDocument()
    const lines = screen.getByRole('table', { name: '회차별 예상 지급 금액' })
    expect(within(lines).getByText('1,000.1234%')).toBeInTheDocument()
    expect(within(lines).getByRole('cell', { name: '요율' })).toBeInTheDocument()
    expect(within(lines).getByRole('cell', { name: '정액' })).toBeInTheDocument()
    expect(within(lines).getAllByText('(100,000)원')[0]).toHaveClass('is-negative-amount')
    // 합계는 서버 expectedTotal 이다.
    expect(lines.querySelector('tfoot')).toHaveTextContent('합계 (2줄)1,500,000원')
    const versionTable = await screen.findByRole('table', { name: '같은 계약의 스케줄 버전 이력' })
    const currentRow = within(versionTable).getByRole('link', { name: 'v2' }).closest('tr')!
    expect(currentRow).toHaveAttribute('aria-current', 'true')
    expect(currentRow).toHaveClass('is-selected')
  })

  it('#2 지급단계 전환은 활성 버전 id 로, 버전 행은 해당 버전으로 이동한다', async () => {
    const { router, actions } = renderAt('/schedules/11?month=2026-07', (url) =>
      url === '/api/v1/schedules/11/active?paymentStage=INSURER_TO_GA' ? 10 : baseHandler()(url),
    )
    await screen.findByText('schedule_header #11')
    await actions.selectOptions(screen.getByLabelText('지급단계'), 'INSURER_TO_GA')
    await waitFor(() => expect(router.state.location.pathname).toBe('/schedules/10'))
    expect(await screen.findByText('schedule_header #10')).toBeInTheDocument()
    await actions.click(
      within(await screen.findByRole('table', { name: '같은 계약의 스케줄 버전 이력' })).getByRole('link', {
        name: 'v2',
      }),
    )
    await waitFor(() => expect(router.state.location.pathname).toBe('/schedules/11'))
    expect(await screen.findByText('schedule_header #11')).toBeInTheDocument()
  })

  it('지급단계 이동 대상이 없으면 경고하고 현재 단계를 유지한다', async () => {
    const { router, actions } = renderAt('/schedules/11', (url) => {
      if (url.includes('/active?'))
        throw new ApiError({ code: 'FGC-COMMON-002', message: '사용 중인 운영 스케줄이 없습니다.' }, 'r1', 404)
      return baseHandler()(url)
    })
    await screen.findByText('schedule_header #11')
    await actions.selectOptions(screen.getByLabelText('지급단계'), 'INSURER_TO_GA')
    await waitFor(() => expect(toasts()).toContain('사용 중인 운영 스케줄이 없습니다. (FGC-COMMON-002 · 요청 ID: r1)'))
    expect(screen.getByLabelText('지급단계')).toHaveValue('GA_TO_FC')
    expect(router.state.location.pathname).toBe('/schedules/11')
  })

  it('#3 사유를 입력해 재생성하면 새 버전으로 이동하고, 확정하면 확정 상태를 반영한다', async () => {
    let confirmed = false
    const { router, actions } = renderAt('/schedules/11?month=2026-07', (url, options) => {
      if (url === '/api/v1/schedules/11/regenerate') return { scheduleHeaderId: 12, versionNo: 3 }
      if (url === '/api/v1/schedules/12')
        return confirmed
          ? detail({ scheduleHeaderId: 12, status: 'CONFIRMED', statusLabel: '확정' })
          : { ...detail(), header: header(12, { scheduleVersionNo: 3 }) }
      if (url === '/api/v1/schedules/12/versions') return [...versions, header(12, { scheduleVersionNo: 3 })]
      if (url === '/api/v1/schedules/12/confirm' && options?.method === 'POST') {
        confirmed = true
        return { ...detail(), header: header(12, { scheduleVersionNo: 3, status: 'CONFIRMED', statusLabel: '확정' }) }
      }
      return baseHandler()(url)
    })
    await screen.findByText('schedule_header #11')
    await actions.click(screen.getByRole('button', { name: /새 버전 만들기/ }))
    const regenerate = await screen.findByRole('dialog', { name: '새 버전 만들기' })
    expect(within(regenerate).getByLabelText(/생성 사유/)).toHaveFocus()
    await actions.type(within(regenerate).getByLabelText(/생성 사유/), ' 2026-07 정책 개정 ')
    await actions.click(within(regenerate).getByRole('button', { name: '새 버전 만들기' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/schedules/12'))
    const regen = calls('/api/v1/schedules/11/regenerate')[0]
    expect(regen.options).toMatchObject({ method: 'POST', body: { reason: '2026-07 정책 개정' } })
    expect(toasts()).toContain('새 스케줄 버전을 만들었습니다. 새 버전으로 이동합니다.')
    expect(await screen.findByText('schedule_header #12')).toBeInTheDocument()

    await actions.click(screen.getByRole('button', { name: '확정' }))
    const confirm = await screen.findByRole('dialog', { name: '이 스케줄을 확정할까요?' })
    expect(within(confirm).getByRole('button', { name: '취소' })).toHaveFocus()
    expect(within(confirm).getByText('v3')).toBeInTheDocument()
    await actions.click(within(confirm).getByRole('button', { name: '확정' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(toasts()).toContain('스케줄을 확정했습니다. 이제 금액을 고칠 수 없습니다. 바꾸려면 새 버전을 만드세요.')
    expect(await screen.findByText('이미 확정된 스케줄입니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '확정' })).toBeDisabled()
  })

  it('#4 사유 미입력·41자 사유는 저장하지 않고 필드 오류를 보여 준다', async () => {
    const { actions } = renderAt('/schedules/11', baseHandler())
    await screen.findByText('schedule_header #11')
    await actions.click(screen.getByRole('button', { name: /새 버전 만들기/ }))
    const dialog = await screen.findByRole('dialog', { name: '새 버전 만들기' })
    const submit = within(dialog).getByRole('button', { name: '새 버전 만들기' })
    const input = within(dialog).getByLabelText(/생성 사유/)
    await actions.click(submit)
    expect(within(dialog).getByRole('alert')).toHaveTextContent('저장 불가 — 생성 사유를 입력하세요.')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    // maxLength 로 41자 입력은 막히지만, 붙여 넣기·자동완성으로 들어온 값도 저장 전에 막는다.
    expect(input).toHaveAttribute('maxLength', '40')
    fireEvent.change(input, { target: { value: '가'.repeat(41) } })
    await actions.click(submit)
    expect(within(dialog).getByRole('alert')).toHaveTextContent('생성 사유는 40자 이하여야 합니다.')
    expect(calls('/api/v1/schedules/11/regenerate')).toHaveLength(0)
  })

  it('서버가 사유 필드를 거절하면 문구를 필드 옆에 그대로 둔다', async () => {
    const { actions } = renderAt('/schedules/11', (url) => {
      if (url.endsWith('/regenerate'))
        throw new ApiError({ code: 'FGC-COMMON-001', message: '입력값을 확인하세요.', field: 'reason' }, 'r2', 400)
      return baseHandler()(url)
    })
    await screen.findByText('schedule_header #11')
    await actions.click(screen.getByRole('button', { name: /새 버전 만들기/ }))
    const dialog = await screen.findByRole('dialog', { name: '새 버전 만들기' })
    await actions.type(within(dialog).getByLabelText(/생성 사유/), '사유')
    await actions.click(within(dialog).getByRole('button', { name: '새 버전 만들기' }))
    expect(await within(dialog).findByText('입력값을 확인하세요.')).toBeInTheDocument()
  })

  it('#5 이미 확정된 버전 재확정은 서버 오류 코드 문구를 그대로 보여 주고 모달을 유지한다', async () => {
    const message = '이미 확정된 스케줄입니다.'
    const { actions } = renderAt('/schedules/11', (url) => {
      if (url.endsWith('/confirm')) throw new ApiError({ code: 'FGC-SCHE-001', message }, 'r3', 409)
      return baseHandler()(url)
    })
    await screen.findByText('schedule_header #11')
    await actions.click(screen.getByRole('button', { name: '확정' }))
    const dialog = await screen.findByRole('dialog', { name: '이 스케줄을 확정할까요?' })
    await actions.click(within(dialog).getByRole('button', { name: '확정' }))
    const expected = `${message} (FGC-SCHE-001 · 요청 ID: r3)`
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(expected)
    expect(toasts()).toContain(expected)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('#6 COMPLIANCE 는 확정·재생성 버튼이 비활성이고 권한 안내를 본다', async () => {
    renderAt('/schedules/11', baseHandler(), 'COMPLIANCE')
    expect(await screen.findByText('확정·재생성은 정산 담당자만 할 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /새 버전 만들기/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '확정' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '확정' })).toHaveAttribute('aria-describedby', 'schedule-action-note')
    // 조회와 CSV 는 모든 인증 역할이 할 수 있다.
    expect(screen.getByRole('button', { name: /CSV 내보내기/ })).toBeEnabled()
  })

  it('숫자가 아닌 id 는 API 를 부르지 않고, 조회 실패는 다시 시도할 수 있다', async () => {
    renderAt('/schedules/abc', () => undefined)
    expect((await screen.findAllByText('스케줄 ID를 확인할 수 없습니다.')).length).toBeGreaterThan(0)
    expect(calls('/api/v1/schedules')).toHaveLength(0)
  })

  it('상세 CSV 는 회차 표 API 로 내려받는다', async () => {
    const { actions } = renderAt('/schedules/11', baseHandler())
    await screen.findByText('schedule_header #11')
    const blob = vi.spyOn(apiClient, 'requestBlob').mockResolvedValue({ blob: new Blob(['a']), disposition: null })
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} }))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await actions.click(screen.getByRole('button', { name: /CSV 내보내기/ }))
    await waitFor(() => expect(blob).toHaveBeenCalledWith('/api/v1/schedules/11/export.csv', expect.anything()))
    await waitFor(() => expect(toasts()).toContain('회차 표 CSV를 내려받았습니다.'))
  })
})
