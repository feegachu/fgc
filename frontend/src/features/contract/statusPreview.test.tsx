import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import ContractDetailPage from './ContractDetailPage'
import type { ContractDetail, ContractStatusPreview } from './api'
import { previewEffectiveAtError, statusDraftFrom } from './form'

const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'status-preview-test' })
const PREVIEW_URL = '/api/v1/contracts/1/status-events/preview'
const AT = '2026-01-15T09:00'
const contract: ContractDetail = {
  contractId: 1,
  contractNo: 'CONT-0001',
  contractStatus: 'ACTIVE',
  contractStatusLabel: '정상',
  insurerName: '한빛생명',
  productName: '종신보험',
  contractDate: '2025-07-01',
  agentName: '김설계',
  agentId: 600,
  organizationName: '서울지점',
  updatedAt: '2026-01-20T00:00:00.123456Z',
  allowedNextStatuses: ['UNPAID', 'TERMINATED'],
}
const line = (id: number, previousState: string, nextState: string, reasonCode: string, extra = {}) => ({
  scheduleLineId: id,
  paymentStage: 'GA_TO_FC' as const,
  commissionItemName: '유지관리수수료',
  installmentNo: id,
  dueDate: '2026-02-25',
  nextDueDate: null,
  amount: 120000,
  previousState,
  nextState,
  reasonCode,
  reason: `회차 ${id} 근거 문구`,
  ...extra,
})
const preview: ContractStatusPreview = {
  contractId: 1,
  currentStatus: 'ACTIVE',
  eventType: 'TERMINATED',
  effectiveAt: '2026-01-15T09:00:00+09:00',
  stoppedLines: [line(11, 'PLANNED', 'CANCELLED', 'STATUS_INACTIVE')],
  resumedLines: [line(12, 'HOLD', 'PLANNED', 'UNPAID_RESUMED', { nextDueDate: '2026-03-05' })],
  excludedLines: [line(13, 'PLANNED', 'EXCLUDED', 'MONTH_LOCKED')],
  payments: [
    {
      commissionTransactionId: 91,
      amount: 3000,
      previousState: 'DRAFT',
      nextState: 'HELD',
      reasonCode: 'STATUS_INACTIVE',
      reason: '지급 건 근거 문구',
    },
  ],
  heldAmount: { before: 1000, after: 4000 },
  paidAmount: [
    { paymentStage: 'INSURER_TO_GA', amount: 7000 },
    { paymentStage: 'GA_TO_FC', amount: 5000 },
  ],
  clawbackReview: {
    candidate: true,
    amount: 2500,
    reason: '환수 정책으로 계산한 예상액입니다. 확정액이 아닙니다.',
    reasonCodes: [],
    elapsedMonths: 6,
    policyVersionId: 77,
    policyCode: 'CLAW-GA-VIRTUAL-2026',
    policyVersionNo: 1,
    lines: [
      {
        transactionAttributionId: 501,
        commissionItemName: '기본수수료',
        installmentNo: 1,
        basisAmount: 5000,
        appliedRatePct: 50,
        clawbackAmount: 2500,
      },
    ],
  },
  pendingFollowUps: 0,
  snapshotAt: '2026-01-20T10:30:15+09:00',
}

type Handler = (url: string, options?: RequestOptions) => unknown
let requested: { url: string; options?: RequestOptions }[]
const posts = () => requested.filter(({ url }) => url === PREVIEW_URL)
const writes = () =>
  requested.filter(({ options, url }) => options?.method && options.method !== 'GET' && url !== PREVIEW_URL)
const apiError = (status: number, code: string, extra: object = {}) =>
  new ApiError({ code, message: `서버 문구 ${code}`, ...extra }, 'req-1', status)

function renderPage(
  handler: Handler = () => preview,
  {
    roleCode = 'SETTLEMENT',
    canProcess = true,
    detail = () => contract,
  }: { roleCode?: string; canProcess?: boolean; detail?: () => ContractDetail } = {},
) {
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    requested.push({ url, options })
    if (url === '/api/v1/auth/me') return envelope({ loginId: 'tester', userName: '담당자', roleCode, canProcess, demoMonth: '2026-07' })
    if (url === PREVIEW_URL) return envelope(await handler(url, options))
    if (url === '/api/v1/contracts/1') return envelope(detail())
    if (url === '/api/v1/contracts/1/status-events') return envelope([])
    throw new Error(`unexpected ${url}`)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const router = createMemoryRouter(
    [
      { path: '/contracts/:id', element: <ContractDetailPage /> },
      { path: '/contracts/:id/edit', element: <h1>계약 수정 화면</h1> },
    ],
    { initialEntries: ['/contracts/1?tab=basic&returnTo=%2Fcontracts%3Fpage%3D3'] },
  )
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { client, router, actions: userEvent.setup() }
}

async function openModal(actions: ReturnType<typeof userEvent.setup>) {
  await actions.click(await screen.findByRole('button', { name: '영향 미리보기' }))
  return screen.getByRole('dialog', { name: '상태 변경 영향 미리보기' })
}
function fill(dialog: HTMLElement, eventType = 'TERMINATED', effectiveAt = AT) {
  fireEvent.change(within(dialog).getByLabelText(/변경 종류/), { target: { value: eventType } })
  fireEvent.change(within(dialog).getByLabelText(/효력일시/), { target: { value: effectiveAt } })
}
const submit = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: '영향 미리보기' })

beforeEach(() => useAuthStore.setState({ accessToken: 'fixture-status-preview', loggingOut: false }))
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('CONT-W02 상태 변경 영향 미리보기', () => {
  it.each([['SETTLEMENT'], ['GA_ADMIN'], ['SYSTEM_ADMIN']])('%s 역할에 버튼을 보인다', async (roleCode) => {
    renderPage(undefined, { roleCode })
    expect(await screen.findByRole('button', { name: '영향 미리보기' })).toBeInTheDocument()
  })

  it.each([['COMPLIANCE'], ['AGENT']])('%s 역할에는 버튼을 보이지 않는다', async (roleCode) => {
    renderPage(undefined, { roleCode, canProcess: false })
    await screen.findByRole('heading', { name: 'CONT-0001' })
    expect(screen.queryByRole('button', { name: '영향 미리보기' })).not.toBeInTheDocument()
  })

  it('변경 종류와 효력일시를 비우면 필드 옆 오류와 첫 오류 포커스만 보이고 호출하지 않는다', async () => {
    const { actions } = renderPage()
    const dialog = await openModal(actions)

    await actions.click(submit(dialog))

    expect(within(dialog).getByText('변경 종류를 선택하세요.')).toBeInTheDocument()
    expect(within(dialog).getByText('올바른 일시를 입력해 주세요.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/변경 종류/)).toHaveFocus()
    expect(posts()).toHaveLength(0)
  })

  it('허용 전이만 선택지로 보이고 끝 상태 계약은 미리보기를 잠그고 이유를 알린다', async () => {
    const { actions } = renderPage(undefined, { detail: () => ({ ...contract, allowedNextStatuses: [] }) })
    const dialog = await openModal(actions)

    expect(within(dialog).getByText('끝 상태 계약이라 바꿀 수 있는 상태가 없습니다.')).toBeInTheDocument()
    expect(submit(dialog)).toBeDisabled()
  })

  it('입력을 서울 시각 오프셋으로 보내고 전후 회차, 보류, 기지급, 환수 근거, 스냅샷 시각을 보인다. 쓰기 API 는 부르지 않는다', async () => {
    const { actions } = renderPage()
    const dialog = await openModal(actions)
    fill(dialog)

    await actions.click(submit(dialog))

    expect(await within(dialog).findByRole('table', { name: '멈추는 회차' })).toHaveTextContent('예정 → 취소')
    expect(posts()[0].options).toMatchObject({
      method: 'POST',
      body: { eventType: 'TERMINATED', effectiveAt: '2026-01-15T09:00:00+09:00' },
    })
    const resumed = within(dialog).getByRole('table', { name: '재개되는 회차' })
    expect(resumed).toHaveTextContent('보류 → 예정')
    expect(resumed).toHaveTextContent('2026-02-25 → 2026-03-05')
    expect(within(dialog).getByRole('table', { name: '바꾸지 않는 회차' })).toHaveTextContent('회차 13 근거 문구')
    expect(within(dialog).getByRole('table', { name: '미확정 지급 건 영향' })).toHaveTextContent('작성중 → 보류')
    expect(within(dialog).getByText(/변경 전 1,000원, 변경 후 4,000원/)).toBeInTheDocument()
    expect(within(dialog).getByText(/보험회사→GA 7,000원/)).toBeInTheDocument()
    expect(within(dialog).getByText(/GA→설계사 5,000원/)).toBeInTheDocument()
    expect(within(dialog).getByText('예상 2,500원')).toBeInTheDocument()
    expect(within(dialog).getByText(/적용 정책 v1/)).toBeInTheDocument()
    expect(within(dialog).getByRole('table', { name: '예상 환수액 계산 근거' })).toHaveTextContent('50.0%')
    expect(within(dialog).getByText(/계산 시각 2026-01-20 10:30:15 기준/)).toBeInTheDocument()
    expect(within(dialog).getByText(/계약 유지 확률이 아니며/)).toBeInTheDocument()
    expect(writes()).toHaveLength(0)
  })

  it('미계산과 자료 없음과 실제 0원을 구분한다', async () => {
    const { actions } = renderPage(() => ({
      ...preview,
      stoppedLines: [],
      resumedLines: [],
      excludedLines: [],
      payments: [],
      heldAmount: { before: 0, after: 0 },
      paidAmount: [
        { paymentStage: 'INSURER_TO_GA', amount: 0 },
        { paymentStage: 'GA_TO_FC', amount: null },
      ],
      clawbackReview: {
        candidate: true,
        amount: null,
        reason: '근거 없음(회사 약정 필요). 적용할 환수 정책이 없어 계산하지 않았습니다.',
        reasonCodes: ['POLICY_MISSING'],
        elapsedMonths: 6,
        policyVersionId: null,
        policyCode: null,
        policyVersionNo: null,
        lines: [],
      },
    }))
    const dialog = await openModal(actions)
    fill(dialog)

    await actions.click(submit(dialog))

    expect(await within(dialog).findByText('미계산')).toBeInTheDocument()
    expect(within(dialog).getByText(/근거 없음\(회사 약정 필요\)/)).toBeInTheDocument()
    expect(within(dialog).getByText('유효한 환수 정책 없음')).toBeInTheDocument()
    expect(within(dialog).getByText(/변경 전 0원, 변경 후 0원/)).toBeInTheDocument()
    expect(within(dialog).getByText(/보험회사→GA 0원/)).toBeInTheDocument()
    expect(within(dialog).getByText(/GA→설계사 자료 없음/)).toBeInTheDocument()
    expect(within(dialog).getByText('변경되는 예정 회차가 없습니다')).toBeInTheDocument()
    expect(within(dialog).queryByText(/예상 0원/)).not.toBeInTheDocument()
  })

  it('규칙이 0% 면 예상 0원을 보이고 후보가 아닌 사건은 해당 없음이다', async () => {
    let review: ContractStatusPreview['clawbackReview'] = { ...preview.clawbackReview, amount: 0, lines: [] }
    const { actions } = renderPage(() => ({ ...preview, clawbackReview: review }))
    const dialog = await openModal(actions)
    fill(dialog)

    await actions.click(submit(dialog))
    expect(await within(dialog).findByText('예상 0원')).toBeInTheDocument()

    review = { candidate: false, amount: null, reason: '이 변경은 환수 검토 후보가 되지 않습니다.', reasonCodes: [], lines: [] }
    fill(dialog, 'UNPAID')
    await actions.click(submit(dialog))
    expect(await within(dialog).findByText(/해당 없음. 이 변경은 환수 검토 후보가 되지 않습니다./)).toBeInTheDocument()
  })

  it('입력이 바뀌면 결과를 낡음으로 표시하고 같은 입력으로 다시 미리보면 지운다', async () => {
    const { actions } = renderPage()
    const dialog = await openModal(actions)
    fill(dialog)
    await actions.click(submit(dialog))
    await within(dialog).findByRole('table', { name: '멈추는 회차' })
    expect(within(dialog).queryByText('최신 아님')).not.toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText(/효력일시/), { target: { value: '2026-01-16T09:00' } })

    expect(within(dialog).getByText('최신 아님')).toBeInTheDocument()
    await actions.click(submit(dialog))
    await waitFor(() => expect(posts()).toHaveLength(2))
    await waitFor(() => expect(within(dialog).queryByText('최신 아님')).not.toBeInTheDocument())
  })

  it('대상 계약이 바뀌면 입력이 같아도 결과를 낡음으로 표시한다', async () => {
    let current = contract
    const { actions, client } = renderPage(undefined, { detail: () => current })
    const dialog = await openModal(actions)
    fill(dialog)
    await actions.click(submit(dialog))
    await within(dialog).findByRole('table', { name: '멈추는 회차' })

    current = { ...contract, updatedAt: '2026-01-21T00:00:00Z' }
    await act(() => client.invalidateQueries({ queryKey: ['contracts', 'detail', '1'], exact: true }))

    expect(await within(dialog).findByText('최신 아님')).toBeInTheDocument()
    expect(posts()).toHaveLength(1)
  })

  it('처리 중에는 같은 동작을 잠그고 입력이 바뀐 뒤 도착한 늦은 응답은 무시한다', async () => {
    let resolveFirst: (value: ContractStatusPreview) => void = () => {}
    let call = 0
    const { actions } = renderPage(() => {
      call += 1
      return call === 1
        ? new Promise<ContractStatusPreview>((resolve) => {
            resolveFirst = resolve
          })
        : { ...preview, stoppedLines: [line(21, 'PLANNED', 'HOLD', 'STATUS_UNPAID')] }
    })
    const dialog = await openModal(actions)
    fill(dialog)

    fireEvent.click(submit(dialog))
    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(submit(dialog)).toBeDisabled()
    fireEvent.submit(within(dialog).getByRole('form', { name: '미리볼 상태 변경' }))
    expect(posts()).toHaveLength(1)
    expect(within(dialog).getByRole('status')).toHaveTextContent('영향을 계산하는 중입니다.')

    fireEvent.change(within(dialog).getByLabelText(/효력일시/), { target: { value: '2026-01-16T09:00' } })
    await act(async () => resolveFirst(preview))

    expect(within(dialog).queryByRole('table', { name: '멈추는 회차' })).not.toBeInTheDocument()
    await actions.click(submit(dialog))
    expect(await within(dialog).findByRole('table', { name: '멈추는 회차' })).toHaveTextContent('회차 21 근거 문구')
  })

  it('403 은 권한 없음이고 결과를 보이지 않는다', async () => {
    const { actions } = renderPage(() => {
      throw apiError(403, 'FGC-AUTH-003')
    })
    const dialog = await openModal(actions)
    fill(dialog)

    await actions.click(submit(dialog))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('영향 미리보기 권한이 없습니다.')
    expect(within(dialog).queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('409 전이 불가는 최신 상태 안내와 다시 조회를 보이고 입력을 지우지 않는다', async () => {
    const { actions } = renderPage(() => {
      throw apiError(409, 'FGC-IMPL-TRANSITION_DENIED', { params: { from: '정상', to: '해지' } })
    })
    const dialog = await openModal(actions)
    fill(dialog)

    await actions.click(submit(dialog))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('지금 정상에서 해지(으)로 바꿀 수 없습니다')
    expect(within(dialog).getByLabelText(/변경 종류/)).toHaveValue('TERMINATED')
    expect(within(dialog).getByLabelText(/효력일시/)).toHaveValue(AT)
    const details = requested.filter(({ url }) => url === '/api/v1/contracts/1').length

    await actions.click(within(dialog).getByRole('button', { name: '다시 조회' }))

    await waitFor(() => expect(requested.filter(({ url }) => url === '/api/v1/contracts/1').length).toBeGreaterThan(details))
    await waitFor(() => expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument())
    expect(within(dialog).getByLabelText(/효력일시/)).toHaveValue(AT)
    expect(writes()).toHaveLength(0)
  })

  it('다시 조회한 계약에서 고를 수 없게 된 사건은 선택에서 빼고 효력일시는 남긴다', async () => {
    let current = contract
    const { actions } = renderPage(
      () => {
        throw apiError(409, 'FGC-IMPL-TRANSITION_DENIED', { params: { from: '해지', to: '미납' } })
      },
      { detail: () => current },
    )
    const dialog = await openModal(actions)
    fill(dialog, 'UNPAID')
    await actions.click(submit(dialog))
    await within(dialog).findByRole('alert')

    current = { ...contract, contractStatus: 'TERMINATED', allowedNextStatuses: [], updatedAt: '2026-01-21T00:00:00Z' }
    await actions.click(within(dialog).getByRole('button', { name: '다시 조회' }))

    await waitFor(() => expect(within(dialog).getByLabelText(/변경 종류/)).toHaveValue(''))
    expect(within(dialog).getByLabelText(/효력일시/)).toHaveValue(AT)
    expect(within(dialog).getByText('끝 상태 계약이라 바꿀 수 있는 상태가 없습니다.')).toBeInTheDocument()
  })

  it('422 일자 범위는 효력일시 옆 오류로 보이고 입력을 보존한다', async () => {
    const { actions } = renderPage(() => {
      throw apiError(422, 'FGC-IMPL-EVENT_ORDER', { field: 'effectiveAt' })
    })
    const dialog = await openModal(actions)
    fill(dialog)

    await actions.click(submit(dialog))

    expect(await within(dialog).findByText('효력일시는 최신 상태 변경의 효력일시 이후이고 현재 시각을 넘을 수 없습니다.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/효력일시/)).toHaveValue(AT)
    expect(within(dialog).getByLabelText(/효력일시/)).toHaveFocus()
  })

  it('422 근거 부족은 사유를 보이고 금액을 0원으로 표시하지 않는다', async () => {
    const { actions } = renderPage(() => {
      throw apiError(422, 'FGC-IMPL-POLICY_MISSING', { field: 'contractDate' })
    })
    const dialog = await openModal(actions)
    fill(dialog)

    await actions.click(submit(dialog))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('상태별 지급조건 정책을 확인할 수 없어 계산하지 않았습니다')
    expect(within(dialog).queryByText(/변경 전/)).not.toBeInTheDocument()
  })

  it('서버 실패와 연결 끊김은 다시 시도로 복구하고 실패 뒤 이전 결과를 남기지 않는다', async () => {
    let fail: Error | null = null
    const { actions } = renderPage(() => {
      if (fail) throw fail
      return preview
    })
    const dialog = await openModal(actions)
    fill(dialog)
    await actions.click(submit(dialog))
    await within(dialog).findByRole('table', { name: '멈추는 회차' })

    fail = apiError(500, 'FGC-COMMON-500')
    await actions.click(submit(dialog))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('요청번호 req-1')
    expect(within(dialog).queryByRole('table', { name: '멈추는 회차' })).not.toBeInTheDocument()

    fail = new TypeError('Failed to fetch')
    await actions.click(within(dialog).getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(within(dialog).getByRole('alert')).toHaveTextContent('저장된 것은 없습니다'))

    fail = null
    await actions.click(within(dialog).getByRole('button', { name: '다시 시도' }))
    expect(await within(dialog).findByRole('table', { name: '멈추는 회차' })).toBeInTheDocument()
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('닫기는 호출 화면의 선택을 보존하고 다시 열면 입력과 결과가 남아 있으며 쓰기 API 를 부르지 않는다', async () => {
    const { actions, router } = renderPage()
    const dialog = await openModal(actions)
    fill(dialog)
    await actions.click(submit(dialog))
    await within(dialog).findByRole('table', { name: '멈추는 회차' })
    const url = router.state.location.search

    await actions.click(within(dialog).getByRole('button', { name: '닫기' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(router.state.location.search).toBe(url)
    const reopened = await openModal(actions)
    expect(within(reopened).getByLabelText(/변경 종류/)).toHaveValue('TERMINATED')
    expect(within(reopened).getByRole('table', { name: '멈추는 회차' })).toBeInTheDocument()
    expect(writes()).toHaveLength(0)
    expect(posts()).toHaveLength(1)
  })

  it('입력 초기화는 입력과 결과를 비우고 저장하지 않는다', async () => {
    const { actions } = renderPage()
    const dialog = await openModal(actions)
    fill(dialog)
    await actions.click(submit(dialog))
    await within(dialog).findByRole('table', { name: '멈추는 회차' })

    await actions.click(within(dialog).getByRole('button', { name: '입력 초기화' }))

    expect(within(dialog).getByLabelText(/변경 종류/)).toHaveValue('')
    expect(within(dialog).getByLabelText(/효력일시/)).toHaveValue('')
    expect(within(dialog).queryByRole('table', { name: '멈추는 회차' })).not.toBeInTheDocument()
    expect(writes()).toHaveLength(0)
  })

  it('계약 수정으로 이동은 상태와 효력일시를 초안으로 넘기고 저장하지 않는다', async () => {
    const { actions, router } = renderPage()
    const dialog = await openModal(actions)
    const move = within(dialog).getByRole('button', { name: '계약 수정으로 이동' })
    expect(move).toBeDisabled()
    fill(dialog)

    await actions.click(move)

    expect(await screen.findByRole('heading', { name: '계약 수정 화면' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/contracts/1/edit')
    expect(new URLSearchParams(router.state.location.search).get('returnTo')).toBe('/contracts?page=3')
    expect(router.state.location.state).toEqual({ statusDraft: { contractStatus: 'TERMINATED', effectiveAt: AT } })
    expect(writes()).toHaveLength(0)
    expect(posts()).toHaveLength(0)
  })

  it('수정 권한이 없는 역할은 이동 버튼 대신 이유를 본다', async () => {
    const { actions } = renderPage(undefined, { roleCode: 'GA_ADMIN', canProcess: false })
    const dialog = await openModal(actions)

    expect(within(dialog).queryByRole('button', { name: '계약 수정으로 이동' })).not.toBeInTheDocument()
    expect(within(dialog).getByText('계약 수정은 정산담당자와 시스템관리자만 할 수 있습니다.')).toBeInTheDocument()
  })
})

describe('미리보기 입력 규칙', () => {
  it('효력일시 형식과 미래 시각을 거른다', () => {
    expect(previewEffectiveAtError('', '2026-01-20T10:00')).toBe('올바른 일시를 입력해 주세요.')
    expect(previewEffectiveAtError('2026-13-01T00:00', '2026-01-20T10:00')).toBe('올바른 일시를 입력해 주세요.')
    expect(previewEffectiveAtError('2026-01-20T10:01', '2026-01-20T10:00')).toBe('효력일시는 현재 시각보다 늦을 수 없습니다.')
    expect(previewEffectiveAtError('2026-01-20T10:00', '2026-01-20T10:00')).toBeUndefined()
  })

  it('수정 화면 초안은 지금 고를 수 있는 상태만 받는다', () => {
    const state = { statusDraft: { contractStatus: 'TERMINATED', effectiveAt: AT } }
    expect(statusDraftFrom(state, ['TERMINATED'])).toEqual({ contractStatus: 'TERMINATED', effectiveAt: AT })
    expect(statusDraftFrom(state, ['UNPAID'])).toBeUndefined()
    expect(statusDraftFrom(null, ['TERMINATED'])).toBeUndefined()
    expect(statusDraftFrom({ statusDraft: { contractStatus: 'TERMINATED', effectiveAt: 'bad' } }, ['TERMINATED'])).toEqual({
      contractStatus: 'TERMINATED',
      effectiveAt: '',
    })
  })
})
