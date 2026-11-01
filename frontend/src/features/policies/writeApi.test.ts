import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { emptyCommission, emptyDraft, emptyRegime } from './management'
import { normalizePolicyDetail, performPolicyAction, savePolicyDraft, serializePolicyDraft } from './writeApi'
import type { PolicyDetailResponse, PolicyWriteResult } from './writeApi'

const timestamp = '2026-10-28T01:02:03.123456Z'
const result: PolicyWriteResult = {
  policyVersionId: 42,
  policyCode: 'UI-POLICY',
  versionNo: 1,
  status: 'DRAFT',
  updatedAt: timestamp,
  approvedBy: null,
  approvedAt: null,
  allowedActions: ['EDIT', 'SUBMIT'],
}
const envelope = { data: result, error: null, requestId: 'test-request' }

function wireDetail(): PolicyDetailResponse {
  return {
    header: { policyVersionId: 42, status: 'ACTIVE', createdBy: 'author' },
    sourceRefs: ['SRC-022'],
    commissionRules: [],
    capRuleSets: [],
    refundRateTables: [],
    allowedActions: ['CLONE'],
    editDocument: {
      policyCode: 'UI-POLICY',
      policyName: '연결 확인',
      policyType: 'FOUR_YEAR_COMMISSION',
      sourceClass: 'PROJECT_ASSUMPTION',
      effectiveFrom: '2027-01-01',
      effectiveTo: null,
      feeRegimeCode: 'FOUR_YEAR_2027',
      basicDocumentVersion: null,
      regulationRefs: [],
      sourceRefs: ['SRC-022'],
      sourceDetails: [{ ref: 'SRC-022', title: '근거', explanation: null, url: null }],
      commissionRules: [
        {
          commissionRuleId: 101,
          paymentStage: 'GA_TO_FC',
          insurerId: null,
          productOfferingId: null,
          organizationId: null,
          agentRankCode: null,
          commissionItemId: 1,
          feeComponentType: 'UPFRONT',
          installmentFrom: 1,
          installmentTo: 48,
          calculationType: 'RATE',
          basisCode: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM',
          ratePct: '0.123456',
          fixedAmount: null,
          equalMonthlyYn: false,
          paymentConditionCode: null,
          priorityNo: 0,
        },
      ],
      feeRegimeRules: [
        {
          feeRegimeRulesetId: 202,
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
      expectedUpdatedAt: timestamp,
    },
    approvalActions: [
      {
        policyApprovalActionId: 11,
        actionSeq: 1,
        actionType: 'APPROVE',
        actionBy: 'reviewer',
        actionAt: timestamp,
        reason: '확인 완료',
        evidenceRef: 'audit:11',
      },
    ],
  }
}

afterEach(() => vi.restoreAllMocks())

describe('policy API contract adapter', () => {
  it('maps real detail nulls, concurrency token, clone action and approval history to the UI', () => {
    const detail = normalizePolicyDetail(wireDetail())
    expect(detail.allowedActions).toEqual(['NEW_VERSION'])
    expect(detail.updatedAt).toBe(timestamp)
    expect(detail.editDocument?.expectedUpdatedAt).toBe(timestamp)
    expect(detail.editDocument?.commissionRules[0]).toMatchObject({
      commissionRuleId: 101,
      insurerId: '',
      agentRankCode: '',
      fixedAmount: '',
      ratePct: '0.123456',
      priorityNo: 0,
    })
    expect(detail.editDocument?.feeRegimeRules[0]).toMatchObject({
      salesStartDateTo: '',
      basicDocumentDateFrom: '',
      longTermMaintenanceFrom: '',
    })
    expect(detail.approvalActions).toEqual([
      {
        actionType: 'APPROVE',
        actor: 'reviewer',
        actedAt: timestamp,
        reason: '확인 완료',
        evidenceRefs: ['audit:11'],
      },
    ])
    expect(detail.approvalActions?.[0]).not.toHaveProperty('previousStatus')
  })

  it('preserves development preview history and source tags without requiring server-only fields', () => {
    const detail = normalizePolicyDetail({
      editDocument: emptyDraft(),
      allowedActions: ['NEW_VERSION'],
      approvalActions: [
        {
          actionType: 'CREATE',
          actor: 'preview',
          actedAt: timestamp,
          reason: '예시',
          previousStatus: null,
          status: 'DRAFT',
        },
      ],
      sourceDetails: [{ ref: 'SRC-UI', title: '예시', explanation: '', url: null, statusTags: ['회사정책'] }],
    })
    expect(detail.approvalActions?.[0].actor).toBe('preview')
    expect(detail.approvalActions?.[0].status).toBe('DRAFT')
    expect(detail.sourceDetails?.[0].statusTags).toEqual(['회사정책'])
  })

  it('whitelists strict DTO fields, preserves decimals and zero, and converts blanks to null', () => {
    const draft = normalizePolicyDetail(wireDetail()).editDocument!
    draft.sourceDetails[0].statusTags = ['회사정책']
    const body = serializePolicyDraft(draft)
    expect(body.sourceDetails[0]).toEqual({
      ref: 'SRC-022',
      title: '근거',
      explanation: null,
      url: null,
      statusTags: ['회사정책'],
    })
    expect(body.commissionRules[0]).toMatchObject({
      insurerId: null,
      ratePct: '0.123456',
      fixedAmount: null,
      priorityNo: 0,
    })
    expect(body.feeRegimeRules[0].longTermMaintenanceFrom).toBeNull()
    expect(body.expectedUpdatedAt).toBe(timestamp)
    draft.commissionRules[0] = {
      ...draft.commissionRules[0],
      calculationType: 'FIXED',
      fixedAmount: '9999999999999.99',
    }
    expect(serializePolicyDraft(draft).commissionRules[0]).toMatchObject({
      ratePct: null,
      fixedAmount: '9999999999999.99',
    })
  })

  it('creates with a caller-owned retry key and strips IDs/timestamp; updates preserve them', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope)
    const draft = normalizePolicyDetail(wireDetail()).editDocument!
    await expect(savePolicyDraft(draft, undefined, 'create-123')).resolves.toEqual(result)
    const create = request.mock.calls[0]
    expect(create[0]).toBe('/api/v1/policies')
    expect(create[1]).toMatchObject({ method: 'POST', idempotencyKey: 'create-123' })
    const body = create[1]?.body as ReturnType<typeof serializePolicyDraft>
    expect(body).not.toHaveProperty('expectedUpdatedAt')
    expect(body.commissionRules[0]).not.toHaveProperty('commissionRuleId')
    expect(body.feeRegimeRules[0]).not.toHaveProperty('feeRegimeRulesetId')
    await savePolicyDraft(draft, 42, 'update-123')
    expect(request.mock.calls[1]).toEqual([
      '/api/v1/policies/42',
      {
        method: 'PUT',
        idempotencyKey: 'update-123',
        body: serializePolicyDraft(draft),
      },
    ])
  })

  it('normalizes comma-separated reference entries without sending empty or duplicate codes', () => {
    const draft = normalizePolicyDetail(wireDetail()).editDocument!
    draft.regulationRefs = [' REG-01 ', '', 'REG-01', 'REG-02']
    draft.sourceRefs = ['SRC-022', ' ', ' SRC-022 ']
    draft.sourceDetails[0].ref = ' SRC-022 '
    draft.feeRegimeRules[0].sourceRefs = [' SRC-022 ', '', 'SRC-022']
    const body = serializePolicyDraft(draft)
    expect(body.regulationRefs).toEqual(['REG-01', 'REG-02'])
    expect(body.sourceRefs).toEqual(['SRC-022'])
    expect(body.sourceDetails[0].ref).toBe('SRC-022')
    expect(body.feeRegimeRules[0].sourceRefs).toEqual(['SRC-022'])
  })

  it('reuses the provided key for an exact retry after an unknown network outcome', async () => {
    const request = vi
      .spyOn(apiClient, 'request')
      .mockRejectedValueOnce(new TypeError('Network error'))
      .mockResolvedValueOnce(envelope)
    const draft = {
      ...emptyDraft(),
      commissionRules: [emptyCommission()],
      feeRegimeRules: [emptyRegime('FOUR_YEAR_COMMISSION')],
    }
    await expect(savePolicyDraft(draft, undefined, 'retry-key')).rejects.toThrow('Network error')
    await savePolicyDraft(draft, undefined, 'retry-key')
    expect(request.mock.calls[1]).toEqual(request.mock.calls[0])
  })

  it.each(['SUBMIT', 'APPROVE', 'REJECT', 'ACTIVATE'] as const)(
    'sends %s with expected state and exact timestamp',
    async (action) => {
      const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope)
      await performPolicyAction(
        42,
        action,
        '검토 사유',
        { expectedStatus: 'REVIEW', expectedUpdatedAt: timestamp },
        'action-key',
      )
      expect(request).toHaveBeenCalledWith('/api/v1/policies/42/actions', {
        method: 'POST',
        idempotencyKey: 'action-key',
        body: { actionType: action, reason: '검토 사유', expectedStatus: 'REVIEW', expectedUpdatedAt: timestamp },
      })
    },
  )

  it('maps new-version to the clone route and its separate request DTO', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope)
    await performPolicyAction(42, 'NEW_VERSION', '개정', { effectiveFrom: '2028-01-01', effectiveTo: '' }, 'clone-key')
    expect(request).toHaveBeenCalledWith('/api/v1/policies/42/versions', {
      method: 'POST',
      idempotencyKey: 'clone-key',
      body: { effectiveFrom: '2028-01-01', effectiveTo: null, reason: '개정' },
    })
  })

  it('refuses writes without a concurrency token and serializes supported clawback rules', async () => {
    const request = vi.spyOn(apiClient, 'request')
    await expect(savePolicyDraft(emptyDraft(), 42, 'key')).rejects.toThrow('수정 시각')
    await expect(performPolicyAction(42, 'APPROVE', '검토', { expectedStatus: 'REVIEW' }, 'key')).rejects.toThrow(
      '수정 시각',
    )
    expect(request).not.toHaveBeenCalled()
    request.mockResolvedValueOnce({ data: { policyVersionId: 43 } } as never)
    await savePolicyDraft(
      {
        ...emptyDraft(),
        clawbackRules: [
          {
            insurerId: '',
            productOfferingId: '',
            commissionItemId: '',
            elapsedMonthFrom: 0,
            elapsedMonthTo: 12,
            clawbackBasisCode: 'PAID_ATTRIBUTED_AMOUNT',
            clawbackRatePct: '50.000000',
          },
        ],
      },
      undefined,
      'key',
    )
    expect(request).toHaveBeenCalledWith(
      '/api/v1/policies',
      expect.objectContaining({
        body: expect.objectContaining({
          clawbackRules: [
            expect.objectContaining({
              insurerId: null,
              productOfferingId: null,
              commissionItemId: null,
              clawbackBasisCode: 'PAID_ATTRIBUTED_AMOUNT',
              clawbackRatePct: '50.000000',
            }),
          ],
        }),
      }),
    )
  })
})
