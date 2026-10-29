import { validMonth } from '../../stores/workspace'
export interface PaymentEntry {
  contractId?: string
  agentId?: string
  commissionItemId?: string
  attributionDate?: string
  amount?: string
  paymentStage?: 'GA_TO_FC' | 'INSURER_TO_GA'
  cashflowType?: 'PAYMENT' | 'DEDUCTION'
}
const positive = (value: string | null) =>
  value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? value : undefined
export function safeReturnTo(value: string | null, fallback: string) {
  return value && value.length < 5000 && /^\/(contracts|transactions|schedules|intake)(?:[/?]|$)/.test(value)
    ? value
    : fallback
}
export function readPaymentEntry(params: URLSearchParams): PaymentEntry {
  const rawDate = params.get('attributionDate')
  const date =
    rawDate &&
    /^\d{4}-\d{2}-\d{2}$/.test(rawDate) &&
    !Number.isNaN(Date.parse(rawDate)) &&
    new Date(rawDate).toISOString().slice(0, 10) === rawDate
      ? rawDate
      : undefined
  const amount = params.get('amount')
  return {
    contractId: positive(params.get('contractId')),
    agentId: positive(params.get('agentId')),
    commissionItemId: positive(params.get('commissionItemId')),
    attributionDate: date,
    cashflowType:
      params.get('cashflowType') === 'DEDUCTION'
        ? 'DEDUCTION'
        : params.get('cashflowType') === 'PAYMENT'
          ? 'PAYMENT'
          : undefined,
    amount: amount && /^\d+(?:\.\d{1,2})?$/.test(amount) && Number.isFinite(Number(amount)) ? amount : undefined,
    paymentStage: params.get('paymentStage') === 'INSURER_TO_GA' ? 'INSURER_TO_GA' : 'GA_TO_FC',
  }
}
/** INTK-W02 등 후속 입력 화면도 이 URL 계약으로 기존 지급 폼을 연다. ID는 서버에서 다시 검증한다. */
export function paymentEntryHref(entry: PaymentEntry, returnTo: string, month?: string) {
  const params = new URLSearchParams()
  Object.entries(entry).forEach(([key, value]) => {
    if (value != null && value !== '') params.set(key, value)
  })
  if (validMonth(month)) params.set('month', month)
  params.set('returnTo', safeReturnTo(returnTo, '/transactions'))
  return `/transactions/new?${params}`
}
export function returnLink(path: string, returnTo: string, selectedId?: string) {
  const back = new URL(returnTo, 'http://workspace.local')
  if (selectedId) back.searchParams.set('selectedId', selectedId)
  const url = new URL(path, 'http://workspace.local')
  url.searchParams.set('returnTo', back.pathname + back.search)
  return url.pathname + url.search
}
