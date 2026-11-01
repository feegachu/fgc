import { createContext } from 'react'
import type { Policy, PolicyDetail } from './api'

// Editable UI values are converted to the API contract in writeApi.ts.
export const policyTypes = {
  CURRENT_COMMISSION: '현행 수수료',
  FOUR_YEAR_COMMISSION: '4년 분급',
  SEVEN_YEAR_COMMISSION: '7년 분급',
  SCHEDULE_ELIGIBILITY: '상태별 지급조건',
  CLAWBACK: '환수',
} as const
export const sources = {
  REGULATORY: '규제',
  INSURER_RULE: '보험사 규정',
  GA_POLICY: 'GA 정책',
  PROJECT_ASSUMPTION: '프로젝트 가정',
}
export const sourceStatusTags = ['현행규정', '개정고시', '시행세칙', 'FAQ', '회사정책'] as const
export const sourceReferenceNotice = 'FGC의 근거 정보는 참조용이며 상품위원회 등 법정 심의·의결을 대체하지 않습니다.'
export const statusLabels: Record<string, string> = {
  DRAFT: '초안',
  REVIEW: '검토 중',
  APPROVED: '승인',
  ACTIVE: '적용 중',
  RETIRED: '종료',
}
export const actionLabels = {
  SUBMIT: '제출',
  APPROVE: '승인',
  REJECT: '반려',
  ACTIVATE: '활성화',
  NEW_VERSION: '새 버전 작성',
} as const
export type PolicyAction = keyof typeof actionLabels
export type NumberInput = number | ''
export type DecimalInput = number | string
export const numberValue = (value: string): NumberInput => (value === '' ? '' : Number(value))
export const optionsFor = (record: Record<string, string>) =>
  Object.entries(record).map(([value, label]) => ({ value, label }))
export interface SourceDetail {
  ref: string
  title: string
  explanation: string
  url: string | null
  // Older responses may omit the persisted source status tags.
  statusTags?: string[]
}
export interface CommissionDraft {
  commissionRuleId?: number
  paymentStage: string
  insurerId: NumberInput
  productOfferingId: NumberInput
  organizationId: NumberInput
  agentRankCode: string
  commissionItemId: NumberInput
  feeComponentType: string
  installmentFrom: NumberInput
  installmentTo: NumberInput
  calculationType: 'RATE' | 'FIXED'
  basisCode: string
  ratePct: DecimalInput
  fixedAmount: DecimalInput
  equalMonthlyYn: boolean
  paymentConditionCode: string
  priorityNo: NumberInput
}
export interface FeeRegimeDraft {
  feeRegimeRulesetId?: number
  feeRegimeCode: string
  salesStartDateFrom: string
  salesStartDateTo: string
  basicDocumentVersion: string
  basicDocumentDateFrom: string
  basicDocumentDateTo: string
  maxInstallments: NumberInput
  longTermMaintenanceFrom: NumberInput
  longTermMaintenanceTo: NumberInput
  reviewRequiredYn: boolean
  sourceRefs: string[]
}
export interface ClawbackDraft {
  insurerId: NumberInput
  productOfferingId: NumberInput
  commissionItemId: NumberInput
  elapsedMonthFrom: NumberInput
  elapsedMonthTo: NumberInput
  clawbackBasisCode: string
  clawbackRatePct: DecimalInput
}
export interface DraftDocument {
  policyCode: string
  policyName: string
  policyType: string
  sourceClass: string
  effectiveFrom: string
  effectiveTo: string
  feeRegimeCode: string
  basicDocumentVersion: string
  regulationRefs: string[]
  sourceRefs: string[]
  sourceDetails: SourceDetail[]
  commissionRules: CommissionDraft[]
  feeRegimeRules: FeeRegimeDraft[]
  parameters: { key: string; value: string | number | boolean | null; description: string }[]
  clawbackRules: ClawbackDraft[]
  expectedUpdatedAt?: string
}
export interface ApprovalAction {
  actionType: string
  previousStatus?: string | null
  status?: string
  actor: string
  actedAt: string
  reason: string
  evidenceRefs?: string[]
}
export interface ManagedPolicyDetail extends Omit<PolicyDetail, 'editDocument' | 'approvalActions'> {
  editDocument?: DraftDocument
  allowedActions?: string[]
  sourceDetails?: SourceDetail[]
  approvalActions?: ApprovalAction[]
  createdBy?: string
  approvedBy?: string | null
  approvedAt?: string | null
  updatedAt?: string
}
export interface PolicyWritePreview {
  save: (draft: DraftDocument, id?: number) => Promise<number>
  action: (
    id: number,
    action: PolicyAction,
    reason: string,
    dates?: { effectiveFrom: string; effectiveTo: string },
  ) => Promise<number>
}
// Only the development preview provides these callbacks. Production never simulates a successful write.
export const PolicyPreviewContext = createContext<PolicyWritePreview | null>(null)
export const conditionOptions = [
  {
    key: 'STATUS_MONTH_RULE',
    label: '월중 상태 판단',
    value: 'PAY_IF_ACTIVE_ON_DUE_DATE',
    valueLabel: '지급예정일에 정상인 경우 지급',
    description: '지급예정일의 계약상태를 기준으로 회차 지급 여부를 판단합니다.',
  },
  {
    key: 'PRORATION_RULE',
    label: '일할 계산',
    value: 'NO_PRORATION',
    valueLabel: '일할 계산하지 않음',
    description: '월중 상태 변경 시 지급액을 일수로 나누어 계산하지 않습니다.',
  },
  {
    key: 'REVIVAL_BACKFILL_RULE',
    label: '부활 소급 지급',
    value: 'NO_RETROACTIVE',
    valueLabel: '실효 기간 소급 지급 없음',
    description: '부활하더라도 실효 기간의 회차를 소급하여 지급하지 않습니다.',
  },
] as const
export function regimeFor(type: string) {
  return type === 'FOUR_YEAR_COMMISSION'
    ? 'FOUR_YEAR_2027'
    : type === 'SEVEN_YEAR_COMMISSION'
      ? 'SEVEN_YEAR_2029'
      : type === 'CURRENT_COMMISSION'
        ? 'CURRENT'
        : ''
}
export function emptyDraft(): DraftDocument {
  return {
    policyCode: '',
    policyName: '',
    policyType: '',
    sourceClass: '',
    effectiveFrom: '',
    effectiveTo: '',
    feeRegimeCode: '',
    basicDocumentVersion: '',
    regulationRefs: [],
    sourceRefs: [],
    sourceDetails: [],
    commissionRules: [],
    feeRegimeRules: [],
    parameters: [],
    clawbackRules: [],
  }
}
export function emptyCommission(): CommissionDraft {
  return {
    paymentStage: '',
    insurerId: '',
    productOfferingId: '',
    organizationId: '',
    agentRankCode: '',
    commissionItemId: '',
    feeComponentType: '',
    installmentFrom: '',
    installmentTo: '',
    calculationType: 'RATE',
    basisCode: '',
    ratePct: '',
    fixedAmount: '',
    equalMonthlyYn: false,
    paymentConditionCode: '',
    priorityNo: '',
  }
}
export function emptyRegime(type: string): FeeRegimeDraft {
  return {
    feeRegimeCode: regimeFor(type),
    salesStartDateFrom: '',
    salesStartDateTo: '',
    basicDocumentVersion: '',
    basicDocumentDateFrom: '',
    basicDocumentDateTo: '',
    maxInstallments: '',
    longTermMaintenanceFrom: '',
    longTermMaintenanceTo: '',
    reviewRequiredYn: false,
    sourceRefs: [],
  }
}
export function permittedActions(policy: Policy, detail: ManagedPolicyDetail, role?: string) {
  const stateActions: Record<string, string[]> = {
    DRAFT: ['EDIT', 'SUBMIT'],
    REVIEW: ['APPROVE', 'REJECT'],
    APPROVED: ['ACTIVATE', 'NEW_VERSION'],
    ACTIVE: ['NEW_VERSION'],
    RETIRED: ['NEW_VERSION'],
  }
  const admin = role === 'GA_ADMIN' || role === 'SYSTEM_ADMIN'
  return (detail.allowedActions ?? []).filter(
    (action) =>
      stateActions[policy.status ?? '']?.includes(action) &&
      (admin || (role === 'SETTLEMENT' && ['EDIT', 'SUBMIT', 'NEW_VERSION'].includes(action))),
  )
}
export function safeSourceUrl(url: string | null | undefined) {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return ['https:', 'http:'].includes(parsed.protocol) && !parsed.username && !parsed.password ? parsed.href : null
  } catch {
    return null
  }
}
export function ruleCount(draft?: DraftDocument) {
  return draft
    ? draft.commissionRules.length + draft.feeRegimeRules.length + draft.parameters.length + draft.clawbackRules.length
    : 0
}
