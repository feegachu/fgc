import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import { PolicyDraftEditor } from './PolicyDraftEditor'
import { PolicyManagement } from './PolicyManagement'
import { emptyDraft } from './management'
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

beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-10-29T00:00:00Z') }) })
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

const updatedAt = '2026-10-28T04:05:06.123456Z'
const result: PolicyWriteResult = {
  policyVersionId: 17, policyCode: 'WORKFLOW-TEST', versionNo: 1, status: 'DRAFT',
  updatedAt, approvedBy: null, approvedAt: null, allowedActions: ['EDIT', 'SUBMIT'],
}
const envelope = (data = result) => ({ data, error: null, requestId: 'workflow-test' })

// This fixture uses the wire contract, including nullable fields and decimal strings.
// It deliberately does not use the request serializer to construct a matching response.
function wireDetail(status: PolicyWriteResult['status'] = 'DRAFT', allowedActions = ['EDIT', 'SUBMIT']): PolicyDetailResponse {
  return {
    header: {
      policyVersionId: 17, policyCode: 'WORKFLOW-TEST', policyName: '분급 정책',
      policyType: 'FOUR_YEAR_COMMISSION', sourceClass: 'GA_POLICY', versionNo: 1,
      status, effectiveFrom: '2027-01-01', effectiveTo: null, createdBy: 'author',
    },
    sourceRefs: ['SRC-022'], commissionRules: [], capRuleSets: [], refundRateTables: [],
    allowedActions,
    editDocument: {
      policyCode: 'WORKFLOW-TEST', policyName: '분급 정책', policyType: 'FOUR_YEAR_COMMISSION',
      sourceClass: 'GA_POLICY', effectiveFrom: '2027-01-01', effectiveTo: null,
      feeRegimeCode: 'FOUR_YEAR_2027', basicDocumentVersion: null,
      regulationRefs: [], sourceRefs: ['SRC-022'],
      sourceDetails: [{ ref: 'SRC-022', title: '승인된 근거', explanation: null, url: null }],
      commissionRules: [{
        commissionRuleId: 9, paymentStage: 'GA_TO_FC', insurerId: null,
        productOfferingId: null, organizationId: null, agentRankCode: null,
        commissionItemId: 3, feeComponentType: 'MAINTENANCE', installmentFrom: 1,
        installmentTo: 48, calculationType: 'RATE', basisCode: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM',
        ratePct: '104.166667', fixedAmount: null, equalMonthlyYn: true,
        paymentConditionCode: null, priorityNo: 1,
      }],
      feeRegimeRules: [{
        feeRegimeRulesetId: 8, feeRegimeCode: 'FOUR_YEAR_2027',
        salesStartDateFrom: '2027-01-01', salesStartDateTo: null,
        basicDocumentVersion: null, basicDocumentDateFrom: null, basicDocumentDateTo: null,
        maxInstallments: 48, longTermMaintenanceFrom: null, longTermMaintenanceTo: null,
        reviewRequiredYn: false, sourceRefs: ['SRC-022'],
      }],
      parameters: [], clawbackRules: [], expectedUpdatedAt: updatedAt,
    },
    approvalActions: [{
      policyApprovalActionId: 31, actionSeq: 1, actionType: 'SUBMIT', actionBy: 'author',
      actionAt: updatedAt, reason: '검토를 요청합니다.', evidenceRef: 'policy:17',
    }],
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

function mountManagement(status: PolicyWriteResult['status'], actions: string[], applicationPeriod?: PolicyDetailResponse['applicationPeriod']) {
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

  it('shows the historical contract-date window separately from the immutable approval period', () => {
    mountManagement('RETIRED', ['CLONE'], { from: '2027-01-01', to: '2027-12-31', successorPolicyVersionId: 18 })
    const period = screen.getByText('계약일 적용 기간').parentElement!
    expect(period).toHaveTextContent('2027-12-31')
    expect(within(period).getByText('이후 계약은 후속 버전 적용')).toBeVisible()
  })

  it('identifies a superseded future version with an empty application window', () => {
    const wire = wireDetail('RETIRED', ['CLONE'])
    wire.approvalActions = [{ policyApprovalActionId: 32, actionSeq: 2, actionType: 'RETIRE',
      actionBy: 'reviewer', actionAt: updatedAt, reason: '적용 전 정정', evidenceRef: 'policy:18' }]
    const detail = normalizePolicyDetail(wire)
    render(<QueryClientProvider client={new QueryClient()}>
      <PolicyManagement policy={detail.header!} detail={detail} refreshing={false} onSaved={vi.fn()} />
    </QueryClientProvider>)
    expect(screen.getByText('정정·승계로 계산 적용에서 제외됨')).toBeVisible()
    expect(screen.getByText('후속 정책 #18')).toBeVisible()
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
    expect(request).toHaveBeenCalledWith('/api/v1/policies', expect.objectContaining({
      method: 'POST', idempotencyKey: expect.any(String),
      body: expect.objectContaining({
        policyCode: 'WORKFLOW-TEST', policyName: '신규 정책', feeRegimeCode: null,
        effectiveTo: null, commissionRules: [], clawbackRules: [],
      }),
    }))
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
    expect(request).toHaveBeenCalledWith('/api/v1/policies/17', expect.objectContaining({
      method: 'PUT', idempotencyKey: expect.any(String),
      body: expect.objectContaining({
        policyName: '수정된 분급 정책', expectedUpdatedAt: updatedAt,
        commissionRules: [expect.objectContaining({
          commissionRuleId: 9, ratePct: '104.166667', fixedAmount: null,
          insurerId: null, agentRankCode: null,
        })],
        feeRegimeRules: [expect.objectContaining({
          feeRegimeRulesetId: 8, longTermMaintenanceFrom: null, longTermMaintenanceTo: null,
        })],
      }),
    }))
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
    expect(request).toHaveBeenCalledWith('/api/v1/policies/17/actions', expect.objectContaining({
      method: 'POST', idempotencyKey: expect.any(String),
      body: { actionType: action, reason: '검토한 정책입니다.', expectedStatus: status, expectedUpdatedAt: updatedAt },
    }))
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
    expect(request).toHaveBeenCalledWith('/api/v1/policies/17/actions', expect.objectContaining({
      body: expect.objectContaining({ evidenceRef: '의결서-001' }),
    }))
  })

  it('maps server CLONE permission to a new-version request and shows real approval history', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope({ ...result, policyVersionId: 18, versionNo: 2 }))
    const { user, onSaved } = mountManagement('ACTIVE', ['CLONE'])
    await user.click(screen.getByRole('tab', { name: '승인 이력' }))
    expect(screen.getByText('검토를 요청합니다.')).toBeVisible()
    expect(screen.getByText(/author · policy:17/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: '새 버전 작성' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText(/새 버전 적용 시작일/)).toHaveValue('')
    expect(within(dialog).getByLabelText(/새 버전 적용 종료일/)).toHaveValue('')
    expect(within(dialog).getByText(/이전 계약은 재계산할 때도 기존 버전과 요율을 유지/)).toBeVisible()
    fireEvent.change(within(dialog).getByLabelText(/새 버전 적용 시작일/), { target: { value: '2028-01-01' } })
    await user.type(within(dialog).getByLabelText(/작업 사유/), '다음 연도 정책')
    await user.click(within(dialog).getByRole('button', { name: '새 초안 만들기' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(18))
    expect(request).toHaveBeenCalledWith('/api/v1/policies/17/versions', expect.objectContaining({
      method: 'POST', idempotencyKey: expect.any(String),
      body: { effectiveFrom: '2028-01-01', effectiveTo: null, reason: '다음 연도 정책' },
    }))
  })

  it('allows correcting a future version with the same cutover and blocks backdated dates', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope({ ...result, policyVersionId: 18, versionNo: 2 }))
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
    expect(request).toHaveBeenCalledWith('/api/v1/policies/17/versions', expect.objectContaining({
      body: { effectiveFrom: '2027-01-01', effectiveTo: null, reason: '적용 전 정정' },
    }))
  })

  it('does not use an unpublished clone source date as the current active cutover', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope({ ...result, policyVersionId: 18, versionNo: 2 }))
    const detail = normalizePolicyDetail(wireDetail('APPROVED', ['CLONE']))
    detail.header!.effectiveFrom = '2029-01-01'
    render(<QueryClientProvider client={new QueryClient()}>
      <PolicyManagement policy={detail.header!} detail={detail} refreshing={false} onSaved={vi.fn()} />
    </QueryClientProvider>)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '새 버전 작성' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/새 버전 적용 시작일/), { target: { value: '2028-01-01' } })
    await user.type(within(dialog).getByLabelText(/작업 사유/), '현재 ACTIVE를 기준으로 서버에서 검증')
    await user.click(within(dialog).getByRole('button', { name: '새 초안 만들기' }))
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/v1/policies/17/versions', expect.objectContaining({
      body: expect.objectContaining({ effectiveFrom: '2028-01-01' }),
    })))
  })

  it('focuses a bracket-indexed server field error and preserves unsaved form values', async () => {
    vi.spyOn(apiClient, 'request').mockRejectedValue(new ApiError({
      code: 'FGC-COMMON-002', field: 'feeRegimeRules[0].maxInstallments', message: '서버에서 허용하지 않는 회차입니다.',
    }, 'server-validation', 400))
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
    expect(request).toHaveBeenCalledWith('/api/v1/policies/17', expect.objectContaining({
      body: expect.objectContaining({
        sourceRefs: ['SRC-023'], sourceDetails: [],
        feeRegimeRules: [expect.objectContaining({ sourceRefs: ['SRC-022'] })],
      }),
    }))
  })

  it('reuses the idempotency key after an uncertain failure and replaces it when the draft changes', async () => {
    const request = vi.spyOn(apiClient, 'request').mockRejectedValue(new Error('연결이 끊어졌습니다.'))
    const { user, onSaved } = mountEditor({
      ...emptyDraft(), policyCode: 'RETRY', policyName: '재시도 정책', policyType: 'CURRENT_COMMISSION',
      sourceClass: 'GA_POLICY', effectiveFrom: '2027-01-01', feeRegimeCode: 'CURRENT',
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

  it('keeps clawback rule registration disabled while D-02 is unresolved', async () => {
    const request = vi.spyOn(apiClient, 'request')
    const { user } = mountEditor({ ...emptyDraft(), policyType: 'CLAWBACK' })
    await user.click(screen.getByRole('button', { name: /환수 규칙/ }))
    expect(screen.getByRole('button', { name: /환수 규칙 추가/ })).toBeDisabled()
    expect(screen.getByText(/D-02/)).toBeVisible()
    expect(request).not.toHaveBeenCalled()
  })
})
