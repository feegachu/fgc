import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import type { PolicyDetail } from './api'
import type { DraftDocument, ManagedPolicyDetail, PolicyAction } from './management'

type Schemas = components['schemas']
// Java optional reference fields are emitted as null even where springdoc only
// marks them optional. Keep that wire distinction at the adapter boundary.
type NullableOptionals<T> = { [K in keyof T]: undefined extends T[K] ? T[K] | null : T[K] }
export type PolicyWriteResult = Omit<Required<Schemas['PolicyWriteResult']>, 'approvedBy' | 'approvedAt'> & {
  approvedBy: string | null
  approvedAt: string | null
}

const nullable = <T>(value: T | ''): T | null => (value === '' ? null : value)
const cleanRefs = (refs: string[]) => [...new Set(refs.map((ref) => ref.trim()).filter(Boolean))]

// Pick only fields accepted by PolicyDraft: display metadata and server-owned fields
// must never be included in the strict write DTO.
export function serializePolicyDraft(draft: DraftDocument) {
  return {
    policyCode: draft.policyCode,
    policyName: draft.policyName,
    policyType: draft.policyType,
    sourceClass: draft.sourceClass,
    effectiveFrom: draft.effectiveFrom,
    effectiveTo: nullable(draft.effectiveTo),
    feeRegimeCode: nullable(draft.feeRegimeCode),
    basicDocumentVersion: nullable(draft.basicDocumentVersion),
    regulationRefs: cleanRefs(draft.regulationRefs),
    sourceRefs: cleanRefs(draft.sourceRefs),
    sourceDetails: draft.sourceDetails.map(({ ref, title, explanation, url }) => ({
      ref: ref.trim(),
      title,
      explanation: nullable(explanation),
      url: nullable(url),
    })),
    commissionRules: draft.commissionRules.map((rule) => ({
      commissionRuleId: rule.commissionRuleId,
      paymentStage: rule.paymentStage,
      insurerId: nullable(rule.insurerId),
      productOfferingId: nullable(rule.productOfferingId),
      organizationId: nullable(rule.organizationId),
      agentRankCode: nullable(rule.agentRankCode),
      commissionItemId: nullable(rule.commissionItemId),
      feeComponentType: rule.feeComponentType,
      installmentFrom: nullable(rule.installmentFrom),
      installmentTo: nullable(rule.installmentTo),
      calculationType: rule.calculationType,
      basisCode: rule.basisCode,
      ratePct: rule.calculationType === 'RATE' && rule.ratePct !== '' ? String(rule.ratePct) : null,
      fixedAmount: rule.calculationType === 'FIXED' && rule.fixedAmount !== '' ? String(rule.fixedAmount) : null,
      equalMonthlyYn: rule.equalMonthlyYn,
      paymentConditionCode: nullable(rule.paymentConditionCode),
      priorityNo: nullable(rule.priorityNo),
    })),
    feeRegimeRules: draft.feeRegimeRules.map((rule) => ({
      feeRegimeRulesetId: rule.feeRegimeRulesetId,
      feeRegimeCode: rule.feeRegimeCode,
      salesStartDateFrom: rule.salesStartDateFrom,
      salesStartDateTo: nullable(rule.salesStartDateTo),
      basicDocumentVersion: nullable(rule.basicDocumentVersion),
      basicDocumentDateFrom: nullable(rule.basicDocumentDateFrom),
      basicDocumentDateTo: nullable(rule.basicDocumentDateTo),
      maxInstallments: nullable(rule.maxInstallments),
      longTermMaintenanceFrom: nullable(rule.longTermMaintenanceFrom),
      longTermMaintenanceTo: nullable(rule.longTermMaintenanceTo),
      reviewRequiredYn: rule.reviewRequiredYn,
      sourceRefs: cleanRefs(rule.sourceRefs),
    })),
    parameters: draft.parameters.map(({ key, value, description }) => ({
      key,
      value,
      description: nullable(description),
    })),
    clawbackRules: draft.clawbackRules.map((rule) => ({
      insurerId: nullable(rule.insurerId),
      productOfferingId: nullable(rule.productOfferingId),
      commissionItemId: nullable(rule.commissionItemId),
      elapsedMonthFrom: nullable(rule.elapsedMonthFrom),
      elapsedMonthTo: nullable(rule.elapsedMonthTo),
      clawbackBasisCode: rule.clawbackBasisCode,
      clawbackRatePct: rule.clawbackRatePct === '' ? null : String(rule.clawbackRatePct),
    })),
    expectedUpdatedAt: draft.expectedUpdatedAt,
  }
}

export type PolicyDraftPayload = ReturnType<typeof serializePolicyDraft>
type PolicyDraftResponse = Omit<
  NullableOptionals<Schemas['PolicyDraft']>,
  'sourceDetails' | 'commissionRules' | 'feeRegimeRules' | 'parameters' | 'clawbackRules'
> & {
  sourceDetails?: NullableOptionals<Schemas['SourceDetailDraft']>[] | null
  commissionRules: NullableOptionals<Schemas['CommissionRuleDraft']>[]
  feeRegimeRules: NullableOptionals<Schemas['FeeRegimeRuleDraft']>[]
  // JsonNode is represented as an object in springdoc; these supported policy
  // parameters are JSON scalars and must retain their original JSON types.
  parameters: (Omit<NullableOptionals<Schemas['ParameterDraft']>, 'value'> & {
    value: DraftDocument['parameters'][number]['value']
  })[]
  clawbackRules: (Omit<NullableOptionals<Schemas['ClawbackRuleDraft']>, 'clawbackRatePct'> & {
    clawbackRatePct: string | number
  })[]
}
export interface PolicyDetailResponse extends Omit<
  PolicyDetail,
  'editDocument' | 'approvalActions' | 'allowedActions'
> {
  editDocument?: PolicyDraftResponse | null
  allowedActions?: string[]
  approvalActions?: (Omit<Required<Schemas['PolicyApprovalActionResponse']>, 'evidenceRef'> & {
    evidenceRef: string | null
  })[]
}

export function normalizePolicyDetail(detail: PolicyDetailResponse | ManagedPolicyDetail): ManagedPolicyDetail {
  const draft = detail.editDocument
  return {
    ...detail,
    allowedActions: detail.allowedActions?.map((action) => (action === 'CLONE' ? 'NEW_VERSION' : action)),
    updatedAt: draft?.expectedUpdatedAt ?? ('updatedAt' in detail ? detail.updatedAt : undefined),
    editDocument: draft
      ? {
          ...draft,
          expectedUpdatedAt: draft.expectedUpdatedAt ?? undefined,
          effectiveTo: draft.effectiveTo ?? '',
          feeRegimeCode: draft.feeRegimeCode ?? '',
          basicDocumentVersion: draft.basicDocumentVersion ?? '',
          sourceDetails: (draft.sourceDetails ?? []).map((source) => ({
            ...source,
            explanation: source.explanation ?? '',
            url: source.url ?? null,
          })),
          commissionRules: draft.commissionRules.map((rule) => ({
            ...rule,
            commissionRuleId: rule.commissionRuleId ?? undefined,
            insurerId: rule.insurerId ?? '',
            productOfferingId: rule.productOfferingId ?? '',
            organizationId: rule.organizationId ?? '',
            agentRankCode: rule.agentRankCode ?? '',
            commissionItemId: rule.commissionItemId ?? '',
            installmentFrom: rule.installmentFrom ?? '',
            installmentTo: rule.installmentTo ?? '',
            ratePct: rule.ratePct ?? '',
            fixedAmount: rule.fixedAmount ?? '',
            equalMonthlyYn: rule.equalMonthlyYn ?? false,
            paymentConditionCode: rule.paymentConditionCode ?? '',
            priorityNo: rule.priorityNo ?? '',
          })),
          feeRegimeRules: draft.feeRegimeRules.map((rule) => ({
            ...rule,
            feeRegimeRulesetId: rule.feeRegimeRulesetId ?? undefined,
            salesStartDateTo: rule.salesStartDateTo ?? '',
            basicDocumentVersion: rule.basicDocumentVersion ?? '',
            basicDocumentDateFrom: rule.basicDocumentDateFrom ?? '',
            basicDocumentDateTo: rule.basicDocumentDateTo ?? '',
            maxInstallments: rule.maxInstallments ?? '',
            longTermMaintenanceFrom: rule.longTermMaintenanceFrom ?? '',
            longTermMaintenanceTo: rule.longTermMaintenanceTo ?? '',
            reviewRequiredYn: rule.reviewRequiredYn ?? false,
          })),
          parameters: draft.parameters.map((parameter) => ({ ...parameter, description: parameter.description ?? '' })),
          clawbackRules: draft.clawbackRules.map((rule) => ({
            ...rule,
            insurerId: rule.insurerId ?? '',
            productOfferingId: rule.productOfferingId ?? '',
            commissionItemId: rule.commissionItemId ?? '',
            elapsedMonthFrom: rule.elapsedMonthFrom ?? '',
            elapsedMonthTo: rule.elapsedMonthTo ?? '',
            clawbackRatePct: rule.clawbackRatePct ?? '',
          })),
        }
      : undefined,
    approvalActions: detail.approvalActions?.map((entry) =>
      'actionBy' in entry
        ? {
            actionType: entry.actionType === 'CLONE' ? 'NEW_VERSION' : entry.actionType,
            actor: entry.actionBy,
            actedAt: entry.actionAt,
            reason: entry.reason,
            evidenceRefs: entry.evidenceRef ? [entry.evidenceRef] : [],
          }
        : entry,
    ),
  }
}

export async function savePolicyDraft(draft: DraftDocument, id: number | undefined, idempotencyKey: string) {
  if (id !== undefined && !draft.expectedUpdatedAt) {
    throw new Error('수정 시각 정보가 없습니다. 정책을 다시 조회한 후 저장하세요.')
  }
  if (draft.clawbackRules.length) {
    throw new Error('환수 기준(D-02)이 확정되지 않아 환수 규칙은 등록할 수 없습니다.')
  }
  const body = serializePolicyDraft(draft)
  if (id === undefined) {
    delete body.expectedUpdatedAt
    for (const rule of body.commissionRules) delete rule.commissionRuleId
    for (const rule of body.feeRegimeRules) delete rule.feeRegimeRulesetId
  }
  const response = await apiClient.request<PolicyWriteResult>(
    id === undefined ? '/api/v1/policies' : `/api/v1/policies/${id}`,
    { method: id === undefined ? 'POST' : 'PUT', body, idempotencyKey },
  )
  return response.data
}

export type PolicyActionOptions = Partial<
  Pick<Schemas['PolicyActionRequest'], 'expectedStatus' | 'expectedUpdatedAt' | 'evidenceRef'> &
    Pick<Schemas['PolicyCloneRequest'], 'effectiveFrom' | 'effectiveTo'>
>

export async function performPolicyAction(
  id: number,
  action: PolicyAction,
  reason: string,
  options: PolicyActionOptions,
  idempotencyKey: string,
) {
  if (action === 'NEW_VERSION') {
    if (!options.effectiveFrom) throw new Error('새 버전 적용 시작일을 입력하세요.')
    const body: NullableOptionals<Schemas['PolicyCloneRequest']> = {
      effectiveFrom: options.effectiveFrom,
      effectiveTo: nullable(options.effectiveTo ?? ''),
      reason,
    }
    const response = await apiClient.request<PolicyWriteResult>(`/api/v1/policies/${id}/versions`, {
      method: 'POST',
      idempotencyKey,
      body,
    })
    return response.data
  }
  if (!options.expectedStatus || !options.expectedUpdatedAt) {
    throw new Error('정책 상태 또는 수정 시각 정보가 없습니다. 다시 조회한 후 처리하세요.')
  }
  const body: Schemas['PolicyActionRequest'] = {
    actionType: action,
    reason,
    expectedStatus: options.expectedStatus,
    expectedUpdatedAt: options.expectedUpdatedAt,
    ...(action === 'APPROVE' && options.evidenceRef?.trim() ? { evidenceRef: options.evidenceRef.trim() } : {}),
  }
  const response = await apiClient.request<PolicyWriteResult>(`/api/v1/policies/${id}/actions`, {
    method: 'POST',
    idempotencyKey,
    body,
  })
  return response.data
}
