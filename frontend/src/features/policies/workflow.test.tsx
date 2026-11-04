import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import { PolicyDraftEditor } from './PolicyDraftEditor'
import { PolicyManagement } from './PolicyManagement'
import { usePolicyDetail } from './api'
import { emptyDraft, emptyCommission, emptyRegime, policyTypes } from './management'
import featureInstances from '../assistant/feature-instances.v1.json'
import type { DraftDocument } from './management'
import { normalizePolicyDetail } from './writeApi'
import type { PolicyDetailResponse, PolicyWriteResult } from './writeApi'

vi.mock('../auth/useAuth', () => ({
  useAuth: () => ({ user: { loginId: 'reviewer', roleCode: 'SYSTEM_ADMIN' } }),
}))
vi.mock('../base/api', () => ({
  useInsurerOptions: () => ({ data: [], isLoading: false }),
  useOrganizationOptions: () => ({ data: [], isLoading: false }),
  useProductOptions: () => ({ data: [], isFetching: false }),
  useCommissionItems: () => ({ data: [{ commissionItemId: 3, itemName: '유지 수수료' }], isLoading: false }),
}))

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-10-29T00:00:00Z') })
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const updatedAt = '2026-10-28T04:05:06.123456Z'
const result: PolicyWriteResult = {
  policyVersionId: 17,
  policyCode: 'WORKFLOW-TEST',
  versionNo: 1,
  status: 'DRAFT',
  updatedAt,
  approvedBy: null,
  approvedAt: null,
  allowedActions: ['EDIT', 'SUBMIT'],
}
const envelope = (data = result) => ({ data, error: null, requestId: 'workflow-test' })

// This fixture uses the wire contract, including nullable fields and decimal strings.
// It deliberately does not use the request serializer to construct a matching response.
function wireDetail(
  status: PolicyWriteResult['status'] = 'DRAFT',
  allowedActions = ['EDIT', 'SUBMIT'],
): PolicyDetailResponse {
  return {
    header: {
      policyVersionId: 17,
      policyCode: 'WORKFLOW-TEST',
      policyName: '분급 정책',
      policyType: 'FOUR_YEAR_COMMISSION',
      sourceClass: 'GA_POLICY',
      versionNo: 1,
      status,
      effectiveFrom: '2027-01-01',
      effectiveTo: null,
      createdBy: 'author',
    },
    sourceRefs: ['SRC-022'],
    commissionRules: [],
    capRuleSets: [],
    refundRateTables: [],
    allowedActions,
    editDocument: {
      policyCode: 'WORKFLOW-TEST',
      policyName: '분급 정책',
      policyType: 'FOUR_YEAR_COMMISSION',
      sourceClass: 'GA_POLICY',
      effectiveFrom: '2027-01-01',
      effectiveTo: null,
      feeRegimeCode: 'FOUR_YEAR_2027',
      basicDocumentVersion: null,
      regulationRefs: [],
      sourceRefs: ['SRC-022'],
      sourceDetails: [{ ref: 'SRC-022', title: '승인된 근거', explanation: null, url: null }],
      commissionRules: [
        {
          commissionRuleId: 9,
          paymentStage: 'GA_TO_FC',
          insurerId: null,
          productOfferingId: null,
          organizationId: null,
          agentRankCode: null,
          commissionItemId: 3,
          feeComponentType: 'MAINTENANCE',
          installmentFrom: 1,
          installmentTo: 48,
          calculationType: 'RATE',
          basisCode: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM',
          ratePct: '104.166667',
          fixedAmount: null,
          equalMonthlyYn: true,
          paymentConditionCode: null,
          priorityNo: 1,
        },
      ],
      feeRegimeRules: [
        {
          feeRegimeRulesetId: 8,
          feeRegimeCode: 'FOUR_YEAR_2027',
          salesStartDateFrom: '2027-01-01',
          salesStartDateTo: null,
          basicDocumentVersion: null,
          basicDocumentDateFrom: null,
          basicDocumentDateTo: null,
          maxInstallments: 48,
          longTermMaintenanceFrom: null,
          longTermMaintenanceTo: null,
          reviewRequiredYn: false,
          sourceRefs: ['SRC-022'],
        },
      ],
      parameters: [],
      clawbackRules: [],
      expectedUpdatedAt: updatedAt,
    },
    approvalActions: [
      {
        policyApprovalActionId: 31,
        actionSeq: 1,
        actionType: 'SUBMIT',
        actionBy: 'author',
        actionAt: updatedAt,
        reason: '검토를 요청합니다.',
        evidenceRef: 'policy:17',
      },
    ],
  }
}

function mountEditor(initial?: DraftDocument, policyId?: number) {
  const onSaved = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <PolicyDraftEditor initial={initial} policyId={policyId} onClose={vi.fn()} onSaved={onSaved} />
    </QueryClientProvider>,
  )
  return { user: userEvent.setup(), onSaved }
}

function mountManagement(
  status: PolicyWriteResult['status'],
  actions: string[],
  applicationPeriod?: PolicyDetailResponse['applicationPeriod'],
) {
  const detail = normalizePolicyDetail({ ...wireDetail(status, actions), applicationPeriod })
  const onSaved = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <PolicyManagement policy={detail.header!} detail={detail} refreshing={false} onSaved={onSaved} />
    </QueryClientProvider>,
  )
  return { user: userEvent.setup(), onSaved }
}

describe('production policy workflow', () => {
  it('reopens with the latest token and child IDs after a conflict reload and saves successfully', async () => {
    const latest = wireDetail()
    const latestUpdatedAt = '2026-10-29T03:04:05.654321Z'
    latest.header!.policyName = '다른 작성자의 최신 정책'
    latest.editDocument!.policyName = '다른 작성자의 최신 정책'
    latest.editDocument!.expectedUpdatedAt = latestUpdatedAt
    latest.editDocument!.commissionRules[0].commissionRuleId = 119
    latest.editDocument!.feeRegimeRules[0].feeRegimeRulesetId = 118
    const request = vi
      .spyOn(apiClient, 'request')
      .mockRejectedValueOnce(new ApiError({ code: 'FGC-POL-003', message: '정책이 변경됐습니다.' }, 'conflict', 409))
      .mockResolvedValueOnce({ data: latest, error: null, requestId: 'latest' })
      .mockResolvedValueOnce(envelope({ ...result, updatedAt: latestUpdatedAt }))
    const onSaved = vi.fn()
    function ManagementWithQuery() {
      const detail = usePolicyDetail(17, true)
      return detail.data ? (
        <PolicyManagement
          policy={detail.data.header!}
          detail={detail.data}
          refreshing={detail.isFetching}
          onSaved={onSaved}
        />
      ) : null
    }
    const cache = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    cache.setQueryData(['policies', 'detail', 17], normalizePolicyDetail(wireDetail()))
    render(
      <QueryClientProvider client={cache}>
        <ManagementWithQuery />
      </QueryClientProvider>,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '초안 수정' }))
    await user.clear(screen.getByLabelText(/정책명/))
    await user.type(screen.getByLabelText(/정책명/), '저장하지 않은 내 입력')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await user.click(await screen.findByRole('button', { name: '다시 조회' }))
    await screen.findByText(/최신 정책:/)
    expect(screen.getByLabelText(/정책명/)).toHaveValue('저장하지 않은 내 입력')
    await user.click(screen.getByRole('button', { name: '취소' }))
    await user.click(screen.getByRole('button', { name: '편집 취소' }))
    await user.click(screen.getByRole('button', { name: '초안 수정' }))
    expect(screen.getByLabelText(/정책명/)).toHaveValue('다른 작성자의 최신 정책')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(17))
    expect(request.mock.calls[2][1]?.body).toMatchObject({
      expectedUpdatedAt: latestUpdatedAt,
      commissionRules: [{ commissionRuleId: 119 }],
      feeRegimeRules: [{ feeRegimeRulesetId: 118 }],
    })
  })

  it('keeps input and concurrency token after a 409 while querying the latest header', async () => {
    const request = vi
      .spyOn(apiClient, 'request')
      .mockRejectedValueOnce(new ApiError({ code: 'FGC-POL-003', message: '정책이 변경됐습니다.' }, 'conflict', 409))
      .mockResolvedValueOnce({ data: wireDetail(), error: null, requestId: 'reload' })
    const { user } = mountEditor(normalizePolicyDetail(wireDetail()).editDocument, 17)
    await user.clear(screen.getByLabelText(/정책명/))
    await user.type(screen.getByLabelText(/정책명/), '내 입력 보존')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await user.click(await screen.findByRole('button', { name: '다시 조회' }))
    await screen.findByText(/최신 정책:/)
    expect(screen.getByLabelText(/정책명/)).toHaveValue('내 입력 보존')
    expect(request).toHaveBeenLastCalledWith('/api/v1/policies/17')
    expect(request.mock.calls[0][1]?.body).toMatchObject({ expectedUpdatedAt: updatedAt })
  })

  it('정책 처리 오류는 다른 화면처럼 공통 문구 정리와 요청번호를 거친다', async () => {
    vi.spyOn(apiClient, 'request').mockRejectedValue(
      new ApiError({ code: 'FGC-POL-003', message: '처리 불가 — 정책 상태가 바뀌었습니다.' }, 'req-pol-1', 409),
    )
    const { user } = mountManagement('DRAFT', ['EDIT', 'SUBMIT'])
    await user.click(screen.getByRole('button', { name: '제출' }))
    await user.type(screen.getByLabelText(/작업 사유/), '검토 요청')
    await user.click(screen.getByRole('button', { name: '제출 확인' }))
    expect(await screen.findByText('처리할 수 없습니다. 정책 상태가 바뀌었습니다. (요청번호: req-pol-1)')).toBeInTheDocument()
    expect(screen.queryByText(/FGC-POL-003/)).not.toBeInTheDocument()
  }, 15_000)

  it('opens the draft evidence field when submission returns a missing-source error', async () => {
    vi.spyOn(apiClient, 'request').mockRejectedValue(
      new ApiError(
        {
          code: 'FGC-POL-004',
          field: 'sourceRefs',
          message: '승인된 근거가 필요합니다.',
        },
        'missing-source',
        422,
      ),
    )
    const { user } = mountManagement('DRAFT', ['EDIT', 'SUBMIT'])
    await user.click(screen.getByRole('button', { name: '제출' }))
    await user.type(screen.getByLabelText(/작업 사유/), '검토 요청')
    await user.click(screen.getByRole('button', { name: '제출 확인' }))
    await waitFor(() => expect(screen.getByLabelText('출처 근거 코드')).toHaveFocus())
    expect(screen.getByRole('heading', { name: '정책 초안 수정' })).toBeVisible()
  })

  it('shows the saved offering document conditions alongside the installment limit', () => {
    const wire = wireDetail('ACTIVE', ['CLONE'])
    Object.assign(wire.editDocument!.feeRegimeRules[0], {
      basicDocumentVersion: 'BD-2027-01',
      basicDocumentDateFrom: '2026-03-01',
      basicDocumentDateTo: '2027-12-31',
    })
    const detail = normalizePolicyDetail(wire)
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyManagement policy={detail.header!} detail={detail} refreshing={false} onSaved={vi.fn()} />
      </QueryClientProvider>,
    )
    const summary = within(screen.getByText('분급 룰셋 1').closest('section')!)
    const version = summary.getByText('기초서류 버전').parentElement!
    expect(version).toHaveTextContent('BD-2027-01')
    const period = summary.getByText('기초서류 일자 범위').parentElement!
    expect(period).toHaveTextContent('2026-03-01')
    expect(period).toHaveTextContent('2027-12-31')
    expect(summary.getByText('최대 회차').parentElement).toHaveTextContent('48회차')
  })

  it('환수 규칙 요약은 저장된 번호 대신 수수료 항목 이름을 보인다', () => {
    const wire = wireDetail('ACTIVE', ['CLONE'])
    wire.editDocument!.clawbackRules = [
      {
        clawbackRuleId: 1,
        insurerId: null,
        productOfferingId: null,
        commissionItemId: 3,
        elapsedMonthFrom: 0,
        elapsedMonthTo: 11,
        clawbackBasisCode: 'PAID_ATTRIBUTED_AMOUNT',
        clawbackRatePct: '100',
      },
    ]
    const detail = normalizePolicyDetail(wire)
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyManagement policy={detail.header!} detail={detail} refreshing={false} onSaved={vi.fn()} />
      </QueryClientProvider>,
    )
    const table = screen.getByRole('table', { name: '환수 규칙' })
    expect(within(table).getByText('유지 수수료')).toBeVisible()
    expect(within(table).getAllByText('전체')).toHaveLength(2)
    expect(within(table).queryByRole('columnheader', { name: /ID/ })).toBeNull()
  })

  it('shows the historical contract-date window separately from the immutable approval period', () => {
    mountManagement('RETIRED', ['CLONE'], { from: '2027-01-01', to: '2027-12-31', successorPolicyVersionId: 18 })
    const period = screen.getByText('계약일 적용 기간').parentElement!
    expect(period).toHaveTextContent('2027-12-31')
    expect(within(period).getByText('이후 계약은 다음 버전 적용')).toBeVisible()
  })

  it('identifies a superseded future version with an empty application window', () => {
    const wire = wireDetail('RETIRED', ['CLONE'])
    wire.approvalActions = [
      {
        policyApprovalActionId: 32,
        actionSeq: 2,
        actionType: 'RETIRE',
        actionBy: 'reviewer',
        actionAt: updatedAt,
        reason: '적용 전 정정',
        evidenceRef: 'policy:18',
      },
    ]
    const detail = normalizePolicyDetail(wire)
    const { rerender } = render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyManagement policy={detail.header!} detail={detail} refreshing={false} onSaved={vi.fn()} />
      </QueryClientProvider>,
    )
    expect(screen.getByText('정정/승계로 계산 적용에서 제외됨')).toBeVisible()
    // 18은 내부 번호라 그대로 보이지 않는다. 조회 목록에 다음 버전이 있으면 정책 코드와 판으로 보인다.
    expect(screen.getByText('다음 정책 버전')).toBeVisible()
    expect(screen.queryByText(/다음 정책 버전 18/)).not.toBeInTheDocument()
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyManagement
          policy={detail.header!}
          detail={detail}
          refreshing={false}
          onSaved={vi.fn()}
          versions={[{ policyVersionId: 18, policyCode: 'WORKFLOW-TEST', versionNo: 2 }]}
        />
      </QueryClientProvider>,
    )
    expect(screen.getByText('다음 정책 버전 WORKFLOW-TEST v2')).toBeVisible()
  })

  it('creates a real draft from the production form and returns its server ID', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope())
    const { user, onSaved } = mountEditor()
    await user.type(screen.getByLabelText(/정책코드/), 'WORKFLOW-TEST')
    await user.type(screen.getByLabelText(/정책명/), '신규 정책')
    await user.selectOptions(screen.getByLabelText(/정책 유형/), 'SCHEDULE_ELIGIBILITY')
    await user.selectOptions(screen.getByLabelText(/출처 분류/), 'GA_POLICY')
    fireEvent.change(screen.getByLabelText(/적용 시작일/), { target: { value: '2027-01-01' } })
    const save = screen.getByRole('button', { name: '초안 저장' })
    expect(save).toBeEnabled()
    await user.click(save)
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(17))
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies',
      expect.objectContaining({
        method: 'POST',
        idempotencyKey: expect.any(String),
        body: expect.objectContaining({
          policyCode: 'WORKFLOW-TEST',
          policyName: '신규 정책',
          feeRegimeCode: null,
          effectiveTo: null,
          commissionRules: [],
          clawbackRules: [],
        }),
      }),
    )
    expect(request.mock.calls[0][1]?.body).not.toHaveProperty('expectedUpdatedAt')
    expect(request.mock.calls[0][1]?.body).not.toHaveProperty('status')
  })

  it('edits a nullable four-year response without inventing long-term limits or losing child IDs', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope())
    const detail = normalizePolicyDetail(wireDetail())
    const { user, onSaved } = mountEditor(detail.editDocument, 17)
    await user.clear(screen.getByLabelText(/정책명/))
    await user.type(screen.getByLabelText(/정책명/), '수정된 분급 정책')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(17))
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies/17',
      expect.objectContaining({
        method: 'PUT',
        idempotencyKey: expect.any(String),
        body: expect.objectContaining({
          policyName: '수정된 분급 정책',
          expectedUpdatedAt: updatedAt,
          commissionRules: [
            expect.objectContaining({
              commissionRuleId: 9,
              ratePct: '104.166667',
              fixedAmount: null,
              insurerId: null,
              agentRankCode: null,
            }),
          ],
          feeRegimeRules: [
            expect.objectContaining({
              feeRegimeRulesetId: 8,
              longTermMaintenanceFrom: null,
              longTermMaintenanceTo: null,
            }),
          ],
        }),
      }),
    )
  })

  it.each([
    ['DRAFT', 'SUBMIT', '제출'],
    ['REVIEW', 'APPROVE', '승인'],
    ['REVIEW', 'REJECT', '반려'],
    ['APPROVED', 'ACTIVATE', '활성화'],
  ] as const)('sends %s → %s with the displayed version timestamp', async (status, action, label) => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope())
    const { user, onSaved } = mountManagement(status, [action])
    await user.click(screen.getByRole('button', { name: label }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText(/사유/), '검토한 정책입니다.')
    await user.click(within(dialog).getByRole('button', { name: `${label} 확인` }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(17))
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies/17/actions',
      expect.objectContaining({
        method: 'POST',
        idempotencyKey: expect.any(String),
        body: {
          actionType: action,
          reason: '검토한 정책입니다.',
          expectedStatus: status,
          expectedUpdatedAt: updatedAt,
        },
      }),
    )
  })

  it('explains the self-approval block to an approver who edited or submitted the draft', () => {
    // 서버는 작성·수정·제출에 참여한 승인자에게 REJECT만 준다. 이 사용자는 생성자가 아니다.
    mountManagement('REVIEW', ['REJECT'])
    expect(screen.queryByRole('button', { name: '승인' })).toBeNull()
    expect(
      screen.getByText('이 정책을 수정하거나 제출한 사용자는 승인할 수 없습니다. 다른 승인자의 검토가 필요합니다.'),
    ).toBeVisible()
  })

  it('includes optional approval evidence in the real approval request', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope())
    const { user, onSaved } = mountManagement('REVIEW', ['APPROVE'])
    await user.click(screen.getByRole('button', { name: '승인' }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText(/사유/), '위원회 심의 확인')
    await user.type(within(dialog).getByLabelText('승인 근거 문서'), '의결서-001')
    await user.click(within(dialog).getByRole('button', { name: '승인 확인' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(17))
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies/17/actions',
      expect.objectContaining({
        body: expect.objectContaining({ evidenceRef: '의결서-001' }),
      }),
    )
  })

  it('maps server CLONE permission to a new-version request and shows real approval history', async () => {
    const request = vi
      .spyOn(apiClient, 'request')
      .mockResolvedValue(envelope({ ...result, policyVersionId: 18, versionNo: 2 }))
    const { user, onSaved } = mountManagement('ACTIVE', ['CLONE'])
    await user.click(screen.getByRole('tab', { name: '승인 이력' }))
    expect(screen.getByText('검토를 요청합니다.')).toBeVisible()
    expect(screen.getByText('author, 다음 정책 버전')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '새 버전 작성' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText(/새 버전 적용 시작일/)).toHaveValue('')
    expect(within(dialog).getByLabelText(/새 버전 적용 종료일/)).toHaveValue('')
    expect(within(dialog).getByText(/이전 계약은 재계산할 때도 기존 버전과 요율을 유지/)).toBeVisible()
    fireEvent.change(within(dialog).getByLabelText(/새 버전 적용 시작일/), { target: { value: '2028-01-01' } })
    await user.type(within(dialog).getByLabelText(/작업 사유/), '다음 연도 정책')
    await user.click(within(dialog).getByRole('button', { name: '새 초안 만들기' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(18))
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies/17/versions',
      expect.objectContaining({
        method: 'POST',
        idempotencyKey: expect.any(String),
        body: { effectiveFrom: '2028-01-01', effectiveTo: null, reason: '다음 연도 정책' },
      }),
    )
  })

  it('allows correcting a future version with the same cutover and blocks backdated dates', async () => {
    const request = vi
      .spyOn(apiClient, 'request')
      .mockResolvedValue(envelope({ ...result, policyVersionId: 18, versionNo: 2 }))
    const { user, onSaved } = mountManagement('ACTIVE', ['CLONE'])
    await user.click(screen.getByRole('button', { name: '새 버전 작성' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/새 버전 적용 시작일/), { target: { value: '2026-10-29' } })
    await user.type(within(dialog).getByLabelText(/작업 사유/), '적용 전 정정')
    await user.click(within(dialog).getByRole('button', { name: '새 초안 만들기' }))
    expect(request).not.toHaveBeenCalled()
    fireEvent.change(within(dialog).getByLabelText(/새 버전 적용 시작일/), { target: { value: '2027-01-01' } })
    await user.click(within(dialog).getByRole('button', { name: '새 초안 만들기' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(18))
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies/17/versions',
      expect.objectContaining({
        body: { effectiveFrom: '2027-01-01', effectiveTo: null, reason: '적용 전 정정' },
      }),
    )
  })

  it('does not use an unpublished clone source date as the current active cutover', async () => {
    const request = vi
      .spyOn(apiClient, 'request')
      .mockResolvedValue(envelope({ ...result, policyVersionId: 18, versionNo: 2 }))
    const detail = normalizePolicyDetail(wireDetail('APPROVED', ['CLONE']))
    detail.header!.effectiveFrom = '2029-01-01'
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyManagement policy={detail.header!} detail={detail} refreshing={false} onSaved={vi.fn()} />
      </QueryClientProvider>,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '새 버전 작성' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/새 버전 적용 시작일/), { target: { value: '2028-01-01' } })
    await user.type(within(dialog).getByLabelText(/작업 사유/), '현재 ACTIVE를 기준으로 서버에서 검증')
    await user.click(within(dialog).getByRole('button', { name: '새 초안 만들기' }))
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(
        '/api/v1/policies/17/versions',
        expect.objectContaining({
          body: expect.objectContaining({ effectiveFrom: '2028-01-01' }),
        }),
      ),
    )
  })

  it.each([false, true])('focuses a server field after rendering, including an early animation frame: %s', async (earlyFrame) => {
    if (earlyFrame) vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 0 })
    vi.spyOn(apiClient, 'request').mockRejectedValue(
      new ApiError(
        {
          code: 'FGC-COMMON-002',
          field: 'feeRegimeRules[0].maxInstallments',
          message: '서버에서 허용하지 않는 회차입니다.',
        },
        'server-validation',
        400,
      ),
    )
    const { user, onSaved } = mountEditor(normalizePolicyDetail(wireDetail()).editDocument, 17)
    await user.clear(screen.getByLabelText(/정책명/))
    await user.type(screen.getByLabelText(/정책명/), '아직 저장하지 않은 이름')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    const limit = await screen.findByLabelText(/최대 회차/)
    await waitFor(() => expect(limit).toHaveFocus())
    expect(limit).toHaveValue(48)
    expect(screen.getAllByText('서버에서 허용하지 않는 회차입니다.').length).toBeGreaterThan(0)
    expect(onSaved).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: /기본정보/ }))
    expect(screen.getByLabelText(/정책명/)).toHaveValue('아직 저장하지 않은 이름')
  })

  it('unlinks a deleted source detail while preserving other references and rule-specific evidence', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope())
    const draft = normalizePolicyDetail(wireDetail()).editDocument!
    draft.sourceRefs.push('SRC-023')
    const { user, onSaved } = mountEditor(draft, 17)
    await user.click(screen.getByRole('button', { name: /근거 자료/ }))
    await user.click(screen.getByRole('button', { name: '행 삭제' }))
    expect(screen.getByLabelText('출처 근거 코드')).toHaveValue('SRC-023')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(17))
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies/17',
      expect.objectContaining({
        body: expect.objectContaining({
          sourceRefs: ['SRC-023'],
          sourceDetails: [],
          feeRegimeRules: [expect.objectContaining({ sourceRefs: ['SRC-022'] })],
        }),
      }),
    )
  })

  it('reuses the idempotency key after an uncertain failure and replaces it when the draft changes', async () => {
    const request = vi.spyOn(apiClient, 'request').mockRejectedValue(new Error('연결이 끊어졌습니다.'))
    const { user, onSaved } = mountEditor({
      ...emptyDraft(),
      policyCode: 'RETRY',
      policyName: '재시도 정책',
      policyType: 'CURRENT_COMMISSION',
      sourceClass: 'GA_POLICY',
      effectiveFrom: '2027-01-01',
      feeRegimeCode: 'CURRENT',
    })
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByRole('button', { name: '초안 저장' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2))
    const firstKey = request.mock.calls[0][1]?.idempotencyKey
    expect(firstKey).toEqual(expect.any(String))
    expect(request.mock.calls[1][1]?.idempotencyKey).toBe(firstKey)
    await user.type(screen.getByLabelText(/정책명/), ' 수정')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(request).toHaveBeenCalledTimes(3))
    expect(request.mock.calls[2][1]?.idempotencyKey).not.toBe(firstKey)
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('opens clawback inputs without guessing a rate and focuses missing fields', async () => {
    const request = vi.spyOn(apiClient, 'request')
    const { user } = mountEditor({ ...emptyDraft(), policyType: 'CLAWBACK' })
    await user.click(screen.getByRole('button', { name: /환수 규칙/ }))
    expect(screen.getByRole('button', { name: /환수 규칙 추가/ })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: /환수 규칙 추가/ }))
    expect(screen.getByLabelText(/환수율/)).toHaveValue(null)
    expect(screen.getByLabelText(/기준금액/)).toHaveValue('')
    expect(screen.getByText(/환수는 확정 지급 귀속액/)).toBeVisible()
    expect(request).not.toHaveBeenCalled()
  })
})


it('registers exactly the draft field targets rendered by all five policy types', async () => {
  const seen = new Set<string>()
  const registered = new Set(['policy.draft-input', 'policy.draft-select'].flatMap((group) =>
    featureInstances[group as 'policy.draft-input' | 'policy.draft-select'].map((value) => `${group}.${value}`)))
  for (const policyType of Object.keys(policyTypes)) {
    const initial: DraftDocument = {
      ...emptyDraft(), policyType, effectiveFrom: '2027-01-01',
      commissionRules: [emptyCommission()], feeRegimeRules: [emptyRegime(policyType)],
      sourceDetails: [{ ref: 'SRC-022', title: '승인 근거', explanation: '', url: null }],
      clawbackRules: [{ insurerId: '', productOfferingId: '', commissionItemId: '', elapsedMonthFrom: '', elapsedMonthTo: '', clawbackBasisCode: '', clawbackRatePct: '' }],
    }
    const { user } = mountEditor(initial)
    const collect = () => {
      const fields = [...document.querySelectorAll<HTMLInputElement | HTMLSelectElement>('input[id^="policy-"], select[id^="policy-"]')]
      for (const field of fields) {
        // Only the shared DraftInput/DraftSelect contract uses the finite groups.
        if (field.id === 'policy-policyName' || !field.closest('.field')) continue
        const id = `policy.draft-${field.tagName.toLowerCase()}.${field.id.slice('policy-'.length).replace(/\.\d+(?=\.|$)/g, '').toLowerCase()}`
        expect(field.getAttribute('data-guide-feature'), `${policyType}: ${field.id}`).toBe(id)
        expect(registered.has(id), `${policyType}: ${id}`).toBe(true)
        seen.add(id)
      }
    }
    collect()
    const tabs = within(screen.getByRole('navigation', { name: '정책 작성 단계' })).getAllByRole('button')
    for (const tab of tabs) { await user.click(tab); collect() }
    cleanup()
  }
  expect([...seen].sort()).toEqual([...registered].sort())
})
