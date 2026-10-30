import { dateTime } from '../../lib/format'
import type { ContractCreateRequest, ContractDetail, ContractUpdateRequest } from './api'

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
/** IF-API-19 statusEvent. 일시는 datetime-local 값(YYYY-MM-DDTHH:mm, 서울 시각)이다. */
export interface StatusEventDraft {
  effectiveAt: string
  receivedAt: string
  reasonCode: string
  reason: string
}
/** 키는 서버 오류의 field 값(statusEvent.effectiveAt 등)과 같다. */
export type StatusEventField = `statusEvent.${keyof StatusEventDraft}`
export type ContractFieldErrors = Partial<Record<ContractField | StatusEventField, string>>

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

/** 지금 서울 시각(분 단위). 접수일시 기본값과 미래 접수 검사에 쓴다. */
export const seoulNow = () => dateTime(new Date()).replace(' ', 'T')

export const initialStatusEvent = (now: string): StatusEventDraft => ({
  effectiveAt: '',
  receivedAt: now,
  reasonCode: '',
  reason: '',
})

const validDateTime = (value: string) => {
  const parsed = new Date(`${value}:00Z`)
  return (
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) &&
    !Number.isNaN(parsed.getTime()) &&
    parsed.toISOString().slice(0, 16) === value
  )
}

/** 서버(운영정책서 제13조의2 3항)가 다시 검사한다. 최신 사건 효력일시 하한은 서버만 안다. */
export function validateStatusEvent(event: StatusEventDraft, now: string, codes: readonly string[]) {
  const errors: ContractFieldErrors = {}
  for (const field of ['effectiveAt', 'receivedAt'] as const)
    if (!validDateTime(event[field])) errors[`statusEvent.${field}`] = '올바른 일시를 입력해 주세요.'
  if (!errors['statusEvent.receivedAt'] && event.receivedAt > now)
    errors['statusEvent.receivedAt'] = '접수일시는 현재 시각보다 늦을 수 없습니다.'
  if (!errors['statusEvent.effectiveAt'] && !errors['statusEvent.receivedAt'] && event.effectiveAt > event.receivedAt)
    errors['statusEvent.effectiveAt'] = '효력일시는 접수일시보다 늦을 수 없습니다.'
  if (!codes.includes(event.reasonCode)) errors['statusEvent.reasonCode'] = '사유코드를 선택해 주세요.'
  const reason = event.reason.trim()
  if (!reason) errors['statusEvent.reason'] = '사유를 입력해 주세요.'
  else if (reason.length > 2000) errors['statusEvent.reason'] = '사유는 2,000자 이내로 입력해 주세요.'
  return errors
}

// 서울은 일광절약시간이 없어 +09:00으로 고정해 보낸다.
export const statusEventRequest = (event: StatusEventDraft): NonNullable<ContractUpdateRequest['statusEvent']> => ({
  effectiveAt: `${event.effectiveAt}:00+09:00`,
  receivedAt: `${event.receivedAt}:00+09:00`,
  reasonCode: event.reasonCode,
  reason: event.reason.trim(),
})

// 서버 ContractServiceImpl#hasScheduleImpactingChanges와 같은 필드다. 저장 뒤 안내는 응답의 재생성 결과로 한다.
const scheduleFields = [
  'insurerId',
  'productOfferingId',
  'contractDate',
  'agentId',
  'organizationId',
  'paymentCycleCode',
  'firstPremiumAmount',
  'monthlyEquivalentFirstPremium',
  'paymentTermMonths',
  'standardSurrenderDeductionAmount',
] as const
export const changesSchedule = (request: ContractCreateRequest, original: ContractDetail) =>
  scheduleFields.some((field) => (request[field] ?? null) !== (original[field] ?? null))
