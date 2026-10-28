import type { Policy } from '../features/policies/api'
import {
  emptyCommission,
  emptyDraft,
  emptyRegime,
  policyTypes,
  regimeFor,
  sources,
  statusLabels,
} from '../features/policies/management'
import type { DraftDocument, ManagedPolicyDetail, PolicyAction } from '../features/policies/management'

// Development-only, explicitly labelled examples. Never imported by the application entry point.
export const previewUser = {
  loginId: 'preview.editor',
  userName: 'UI 검토자',
  roleCode: 'GA_ADMIN',
  demoMonth: '2029-01',
  canProcess: true,
  canViewAuditLog: true,
  canHandleException: true,
  canReverseJournal: true,
  canFinalizeValidation: true,
}
export const actionsByStatus: Record<string, string[]> = {
  DRAFT: ['EDIT', 'SUBMIT'],
  REVIEW: ['APPROVE', 'REJECT'],
  APPROVED: ['ACTIVATE', 'NEW_VERSION'],
  ACTIVE: ['NEW_VERSION'],
  RETIRED: ['NEW_VERSION'],
}
export function createPreviewPolicies(): ManagedPolicyDetail[] {
  const samples: [string, string, Policy['policyType'], Policy['status'], string][] = [
    ['UI-SEVEN-YEAR', '7년 분급 수수료 정책', 'SEVEN_YEAR_COMMISSION', 'DRAFT', previewUser.loginId],
    ['UI-FOUR-YEAR', '4년 분급 유지관리 정책', 'FOUR_YEAR_COMMISSION', 'ACTIVE', 'preview.author'],
    ['UI-STATUS', '계약상태별 지급조건', 'SCHEDULE_ELIGIBILITY', 'REVIEW', 'preview.author'],
    ['UI-CLAWBACK', '환수 정책 검토 초안', 'CLAWBACK', 'DRAFT', previewUser.loginId],
    ['UI-CURRENT', '현행 수수료 지급 기준', 'CURRENT_COMMISSION', 'APPROVED', 'preview.author'],
  ]
  return samples.map(([policyCode, policyName, policyType, status, createdBy], index) => {
    const draft: DraftDocument = {
      ...emptyDraft(),
      policyCode,
      policyName,
      policyType: policyType!,
      sourceClass: 'PROJECT_ASSUMPTION',
      effectiveFrom: '2029-01-01',
      feeRegimeCode: regimeFor(policyType!),
      basicDocumentVersion: 'UI-EXAMPLE-01',
    }
    draft.sourceDetails = [
      {
        ref: 'SRC-UI-EXAMPLE',
        title: '화면 검토용 예시 근거',
        explanation:
          '정책 작성과 근거 패널의 배치를 확인하기 위한 예시입니다. 실제 업무 근거 또는 지급 기준이 아닙니다.',
        url: null,
      },
    ]
    draft.sourceRefs = ['SRC-UI-EXAMPLE']
    if (policyType?.endsWith('_COMMISSION'))
      draft.commissionRules = [
        {
          ...emptyCommission(),
          paymentStage: 'GA_TO_FC',
          insurerId: 1,
          productOfferingId: 101,
          commissionItemId: 1,
          feeComponentType: 'MAINTENANCE',
          installmentFrom: 1,
          installmentTo: 12,
          basisCode: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM',
          ratePct: 12.5,
          equalMonthlyYn: true,
          priorityNo: 1,
        },
      ]
    if (policyType === 'FOUR_YEAR_COMMISSION' || policyType === 'SEVEN_YEAR_COMMISSION')
      draft.feeRegimeRules = [
        {
          ...emptyRegime(policyType),
          salesStartDateFrom: '2029-01-01',
          maxInstallments: policyType === 'FOUR_YEAR_COMMISSION' ? 48 : 84,
          reviewRequiredYn: policyType === 'SEVEN_YEAR_COMMISSION',
          sourceRefs: ['SRC-UI-EXAMPLE'],
        },
      ]
    if (policyType === 'SCHEDULE_ELIGIBILITY')
      draft.parameters = [
        {
          key: 'STATUS_MONTH_RULE',
          value: 'PAY_IF_ACTIVE_ON_DUE_DATE',
          description: '지급예정일의 계약상태를 기준으로 지급 여부 판단',
        },
      ]
    const header: Policy = {
      policyVersionId: index + 1,
      policyCode,
      policyName,
      policyType,
      policyTypeLabel: policyTypes[policyType as keyof typeof policyTypes],
      sourceClass: 'PROJECT_ASSUMPTION',
      sourceClassLabel: sources.PROJECT_ASSUMPTION,
      versionNo: index === 1 ? 2 : 1,
      status,
      statusLabel: statusLabels[status!],
      effectiveFrom: draft.effectiveFrom,
      effectiveTo: null,
      regulationRefs: [],
      createdBy,
      approvedBy: ['ACTIVE', 'APPROVED'].includes(status!) ? 'preview.approver' : null,
      approvedAt: ['ACTIVE', 'APPROVED'].includes(status!) ? '2028-12-20T10:00:00+09:00' : null,
    }
    return {
      header,
      editDocument: draft,
      allowedActions: actionsByStatus[status!],
      sourceDetails: draft.sourceDetails,
      sourceRefs: draft.sourceRefs,
      commissionRules: previewCommissionRules(draft),
      capRuleSets: [],
      refundRateTables: [],
      createdBy,
      approvalActions: [
        {
          actionType: 'CREATE',
          previousStatus: null,
          status: 'DRAFT',
          actor: createdBy,
          actedAt: '2028-12-18T09:30:00+09:00',
          reason: '화면 검토용 예시 초안 작성',
        },
        ...(status !== 'DRAFT'
          ? [
              {
                actionType: 'SUBMIT',
                previousStatus: 'DRAFT',
                status: 'REVIEW',
                actor: createdBy,
                actedAt: '2028-12-19T14:20:00+09:00',
                reason: '규칙과 근거 검토 요청 (예시)',
              },
            ]
          : []),
        ...(['APPROVED', 'ACTIVE'].includes(status!)
          ? [
              {
                actionType: 'APPROVE',
                previousStatus: 'REVIEW',
                status: 'APPROVED',
                actor: 'preview.approver',
                actedAt: '2028-12-20T10:00:00+09:00',
                reason: '정책 검토 완료 (예시)',
              },
            ]
          : []),
        ...(status === 'ACTIVE'
          ? [
              {
                actionType: 'ACTIVATE',
                previousStatus: 'APPROVED',
                status: 'ACTIVE',
                actor: 'preview.approver',
                actedAt: '2028-12-21T10:00:00+09:00',
                reason: '적용 승인 (예시)',
              },
            ]
          : []),
      ],
    }
  })
}
export function previewCommissionRules(draft: DraftDocument): ManagedPolicyDetail['commissionRules'] {
  return draft.commissionRules.map((rule, index) => ({
    ...rule,
    priorityNo: rule.priorityNo === '' ? undefined : rule.priorityNo,
    paymentStage: rule.paymentStage === 'GA_TO_FC' ? 'GA_TO_FC' : 'INSURER_TO_GA',
    commissionRuleId: index + 1,
    paymentStageLabel: rule.paymentStage === 'GA_TO_FC' ? 'GA → 설계사' : '보험사 → GA',
    insurerName: rule.insurerId ? '예시생명' : '전체',
    productName: rule.productOfferingId
      ? `예시 종신보험 · ${rule.productOfferingId === 101 ? '2029-A' : '2029-B'}`
      : '전체',
    itemName: rule.commissionItemId === 1 ? '유지관리수수료' : '판매수수료',
    installmentFrom: Number(rule.installmentFrom),
    installmentTo: Number(rule.installmentTo),
    ratePct: rule.ratePct === '' ? undefined : String(rule.ratePct),
    fixedAmount: rule.fixedAmount === '' ? undefined : rule.fixedAmount,
  }))
}
export const nextStatus: Partial<Record<PolicyAction, Policy['status']>> = {
  SUBMIT: 'REVIEW',
  APPROVE: 'APPROVED',
  REJECT: 'DRAFT',
  ACTIVATE: 'ACTIVE',
}
