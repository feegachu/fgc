import { createContext } from 'react'
import type { Policy, PolicyDetail } from './api'
import { policySeedText } from './labels'

// Editable UI values are converted to the API contract in writeApi.ts.
export const policyTypes = {
  CURRENT_COMMISSION: '현행 수수료',
  FOUR_YEAR_COMMISSION: '4년 분급',
  SEVEN_YEAR_COMMISSION: '7년 분급',
  SCHEDULE_ELIGIBILITY: '상태별 지급조건',
  CLAWBACK: '환수',
} as const
// 출처분류와 상태는 서버 라벨(PolicySourceClass, PolicyStatus)과 화면정의서 POL-W01 표기를 따른다.
export const sources = {
  REGULATORY: '규제',
  INSURER_RULE: '보험사 규칙',
  GA_POLICY: '회사 정책',
  PROJECT_ASSUMPTION: '자체 기준',
}
export const sourceStatusTags = ['현행규정', '개정고시', '시행세칙', 'FAQ', '회사정책'] as const
export const sourceReferenceNotice = 'FGC의 근거 정보는 참조용이며 상품위원회 등 법정 심의/의결을 대체하지 않습니다.'
export const statusLabels: Record<string, string> = {
  DRAFT: '작성중',
  REVIEW: '검토중',
  APPROVED: '승인',
  ACTIVE: '적용중',
  RETIRED: '종료',
}
// V71 policy_reference.title. 화면에는 규정 코드 대신 이 이름을 쓴다.
export const regulationTitles: Record<string, string> = {
  'REG-01': '판매수수료 구조',
  'REG-02': '선지급수수료',
  'REG-03': '유지관리수수료',
  'REG-04': '4년 한시 특례',
  'REG-05': '장기유지수수료',
  'REG-06': '공통비, 간접지원경비',
  'REG-06A': '초년도 1,200%룰의 공통비',
  'REG-06B': '유지관리수수료의 간접지원경비',
  'REG-06C': '일반 공통비 안분정책',
  'REG-07': '유지관리 수령인',
  'REG-08': '초년도 1,200%룰',
  'REG-09': 'GA 확대, 수수료등 범위',
  'REG-10': '준법경영비 3% 공제',
  'REG-11': '한도 계산 제외항목',
  'REG-12': '차익거래 방지',
  'REG-13': '유지관리수수료의 미지급',
  'REG-14': '회사별 환수율',
  'REG-15': '구 분할지급제 삭제',
  'REG-16': '정착지원금 환수',
  'REG-17': '판매수수료 비교공시',
  'REG-18': '대형 GA 비교설명',
  'REG-19': '분급 적용례, 규칙 판정',
  'REG-20': '경력설계사 정착지원금 귀속',
  'REG-21': '신인모집종사자, 신인활동지원비',
  'REG-22': '지급기준 버전, 우회지급',
  'REG-23': '예상 해약환급률표',
  'REG-24': '상품위원회',
}
/**
 * 근거 코드는 그대로 저장하고 화면에서만 읽기 쉬운 이름으로 보인다.
 * 'docs/05_인터페이스정의서_v2_0.md#9-5'처럼 시드에 남은 문서 경로와 승계 기록 'policy:45'가 대상이다.
 * 'policy:45'의 45는 내부 번호라 판 번호(v2)처럼 보이지 않게 숫자를 빼고, 판은 versions에서 찾을 때만 붙인다.
 */
export function refLabel(ref: string, versions?: Pick<Policy, 'policyVersionId' | 'versionNo'>[]) {
  const successor = /^policy:(\d+)$/.exec(ref)
  if (successor) {
    const next = versions?.find((version) => String(version.policyVersionId) === successor[1])
    return next ? `다음 정책 버전 v${next.versionNo}` : '다음 정책 버전'
  }
  const regulation = /^REG-\d{2}[A-Z]?/.exec(ref)
  if (regulation) return regulationTitles[regulation[0]] ?? '관련 규정'
  // 출처 코드와 팀 결정 번호는 보여 줄 이름이 없다. 빈 문자열이면 호출부가 표시하지 않는다.
  if (/^(?:SRC-|D-\d)/.test(ref)) return ''
  const doc = /^docs\/(?:\d+_|FGC_)?(.+?)(?:_v\d+(?:_\d+)*)?\.md(?:#(.+))?$/.exec(ref)
  if (doc) return doc[1].includes('운영정책서') ? ['회사 운영정책', doc[2]].filter(Boolean).join(' ') : ''
  return policySeedText(ref)
}
/** 표시할 이름이 있는 근거만 이름으로 바꾼다. */
export const refLabels = (refs: string[] = []) => refs.map((ref) => refLabel(ref)).filter(Boolean)
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
  clawbackRuleId?: number
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
export const ACQUISITION_COST_BASIS = 'ACQUISITION_COST_BASIS'
export const ACQUISITION_COST_RATE = 'ACQUISITION_COST_RATE_PCT'
/** 기준율의 description을 편집하는 입력의 오류·포커스 경로. 저장 키가 아니다. */
export const ACQUISITION_COST_SOURCE = 'ACQUISITION_COST_SOURCE'
export const MONTHLY_EQUIVALENT_FIRST_PREMIUM = 'MONTHLY_EQUIVALENT_FIRST_PREMIUM'
export const acquisitionCostLabels: Record<string, string> = {
  [ACQUISITION_COST_BASIS]: '계약체결비용 기준금액',
  [ACQUISITION_COST_RATE]: '계약체결비용 기준율(%)',
}
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
