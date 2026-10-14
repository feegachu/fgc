import type { ContractCreateRequest, ContractDetail } from './api'

export interface ContractDraft {
  insurerId: string
  productOfferingId: string
  contractNo: string
  contractDate: string
  contractStatus: ContractCreateRequest['contractStatus']
  agentId: string
  organizationId: string
  organizationName: string
  paymentCycleCode: ContractCreateRequest['paymentCycleCode']
  premiumPerCycleAmount: string
  firstPremiumAmount: string
  monthlyEquivalentFirstPremium: string
  paymentTermMonths: string
  standardSurrenderDeductionAmount: string
  premiumEntered: boolean
}
export type ContractField = Exclude<keyof ContractDraft, 'premiumEntered' | 'organizationName'>
export type ContractFieldErrors = Partial<Record<ContractField, string>>

const text = (value: unknown) => (value == null ? '' : String(value))
const cycleMonths: Partial<Record<ContractDraft['paymentCycleCode'], number>> = {
  MONTHLY: 1,
  QUARTERLY: 3,
  SEMI_ANNUAL: 6,
  ANNUAL: 12,
}

export function initialDraft(today: string, contract?: ContractDetail): ContractDraft {
  return {
    insurerId: text(contract?.insurerId),
    productOfferingId: text(contract?.productOfferingId),
    contractNo: contract?.contractNo ?? '',
    contractDate: contract?.contractDate ?? today,
    contractStatus: contract?.contractStatus ?? 'ACTIVE',
    agentId: text(contract?.agentId),
    organizationId: text(contract?.organizationId),
    organizationName: contract?.organizationName ?? '',
    paymentCycleCode: contract?.paymentCycleCode ?? 'MONTHLY',
    premiumPerCycleAmount: text(contract?.premiumPerCycleAmount),
    firstPremiumAmount: text(contract?.firstPremiumAmount),
    monthlyEquivalentFirstPremium: text(contract?.monthlyEquivalentFirstPremium),
    paymentTermMonths: text(contract?.paymentTermMonths),
    standardSurrenderDeductionAmount: text(contract?.standardSurrenderDeductionAmount),
    premiumEntered: contract?.premiumPerCycleAmount != null,
  }
}

// A saved or explicitly entered cycle premium is independent of the monthly equivalent.
export function fillCyclePremium(draft: ContractDraft): ContractDraft {
  const months = cycleMonths[draft.paymentCycleCode]
  const monthly = Number(draft.monthlyEquivalentFirstPremium)
  if (draft.premiumEntered) return draft
  return {
    ...draft,
    premiumPerCycleAmount:
      months &&
      draft.monthlyEquivalentFirstPremium !== '' &&
      Number.isSafeInteger(monthly) &&
      monthly >= 0 &&
      Number.isSafeInteger(monthly * months)
        ? String(monthly * months)
        : '',
  }
}

export function validateDraft(draft: ContractDraft, today: string): ContractFieldErrors {
  const errors: ContractFieldErrors = {}
  for (const field of ['insurerId', 'productOfferingId', 'agentId', 'organizationId'] as const) {
    if (!draft[field] || !Number.isSafeInteger(Number(draft[field])) || Number(draft[field]) <= 0) {
      errors[field] = '필수 항목을 선택해 주세요.'
    }
  }
  if (!draft.contractNo.trim()) errors.contractNo = '계약번호를 입력해 주세요.'
  else if (draft.contractNo.trim().length > 80) errors.contractNo = '계약번호는 80자 이내로 입력해 주세요.'
  const parsedDate = new Date(`${draft.contractDate}T00:00:00Z`)
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(draft.contractDate) ||
    Number.isNaN(parsedDate.getTime()) ||
    parsedDate.toISOString().slice(0, 10) !== draft.contractDate
  )
    errors.contractDate = '올바른 계약일을 입력해 주세요.'
  else if (draft.contractDate > today) errors.contractDate = '계약일은 미래 날짜를 선택할 수 없습니다.'
  for (const field of [
    'premiumPerCycleAmount',
    'firstPremiumAmount',
    'monthlyEquivalentFirstPremium',
    'paymentTermMonths',
    'standardSurrenderDeductionAmount',
  ] as const) {
    const value = draft[field]
    if (field === 'standardSurrenderDeductionAmount' && value === '') continue
    const min = field === 'paymentTermMonths' ? 1 : 0
    const max = field === 'paymentTermMonths' ? 2147483647 : Number.MAX_SAFE_INTEGER
    if (value === '' || !Number.isSafeInteger(Number(value)) || Number(value) < min || Number(value) > max) {
      errors[field] = `${min} 이상의 정확한 정수를 입력해 주세요.`
    }
  }
  return errors
}

export function createRequest(draft: ContractDraft): ContractCreateRequest {
  return {
    insurerId: Number(draft.insurerId),
    productOfferingId: Number(draft.productOfferingId),
    contractNo: draft.contractNo.trim(),
    contractDate: draft.contractDate,
    contractStatus: draft.contractStatus,
    agentId: Number(draft.agentId),
    organizationId: Number(draft.organizationId),
    paymentCycleCode: draft.paymentCycleCode,
    premiumPerCycleAmount: Number(draft.premiumPerCycleAmount),
    firstPremiumAmount: Number(draft.firstPremiumAmount),
    monthlyEquivalentFirstPremium: Number(draft.monthlyEquivalentFirstPremium),
    paymentTermMonths: Number(draft.paymentTermMonths),
    standardSurrenderDeductionAmount:
      draft.standardSurrenderDeductionAmount === '' ? undefined : Number(draft.standardSurrenderDeductionAmount),
  }
}
