import type {
  Agent,
  AttributionPayload,
  AttributionRequest,
  Blocker,
  CommissionItem,
  Contract,
  Payment,
  PaymentPayload,
  PaymentRequest,
  Precheck,
} from './form-api'
import { dateTimeSeconds } from '../../lib/format'

export const SOURCE_TYPE = 'GA_MANUAL_PAYMENT'
export const RECORDABLE_CODES = ['FGC-CAP-001', 'FGC-CAP-002']
export const METHODS: [AttributionRequest['attributionMethod'], string][] = [
  ['DIRECT', '직접 귀속'],
  ['SETTLEMENT_SUPPORT_MONTHLY', '정착지원금 월배부'],
  ['FIRST_CONTRACT_CARRY_FORWARD', '최초계약 이월'],
  ['APPROVED_ALLOCATION', '승인 배부'],
  ['MANUAL_REVIEW', '수기 검토'],
]
export interface FormValues {
  paymentStage: PaymentRequest['paymentStage']
  sourceBusinessKey: string
  settlementMonth: string
  agentId: string
  commissionItemId: string
  amount: string
  cashflowType: string
  evidenceRef: string
}
export interface AttributionRow {
  key: number
  contractId: string
  attributionDate: string
  amount: string
  decision: string
  attributionMethod: AttributionRequest['attributionMethod']
  allocationBasis: string
  evidenceRef: string
}
export function businessKey(now = new Date()) {
  const [date, time] = dateTimeSeconds(now.toISOString()).split(' ')
  return `GA-MANUAL-${date.replaceAll('-', '')}-${time.replaceAll(':', '')}-${String(now.getMilliseconds()).padStart(3, '0')}`
}
export function initialForm(month: string, draft?: Payment): FormValues {
  return {
    paymentStage: draft?.paymentStage ?? 'GA_TO_FC',
    sourceBusinessKey: draft?.sourceBusinessKey ?? businessKey(),
    settlementMonth: draft?.settlementMonth?.slice(0, 7) ?? month,
    agentId: draft?.agentId == null ? '' : String(draft.agentId),
    commissionItemId: draft?.commissionItemId == null ? '' : String(draft.commissionItemId),
    amount: String(draft?.amount ?? 250000),
    cashflowType: draft?.cashflowType ?? 'PAYMENT',
    evidenceRef: draft?.evidenceRef ?? '',
  }
}
export function newRow(key: number, date: string, amount = '0'): AttributionRow {
  return {
    key,
    contractId: '',
    attributionDate: date,
    amount,
    decision: 'INCLUDED',
    attributionMethod: 'DIRECT',
    allocationBasis: '직접 귀속',
    evidenceRef: '',
  }
}
export function draftRows(draft: Payment | undefined, month: string): AttributionRow[] {
  if (!draft?.attributions?.length) return [newRow(1, `${month}-01`, String(draft?.amount ?? 250000))]
  return draft.attributions.map((row, index) => ({
    key: index + 1,
    contractId: row.contractId == null ? '' : String(row.contractId),
    attributionDate: row.attributionDate ?? '',
    amount: String(row.amount ?? ''),
    decision:
      row.inclusionDecisionStatus === 'EXCLUDED'
        ? `EXCLUDED:${row.exclusionType ?? 'NEW_AGENT_SUPPORT'}`
        : (row.inclusionDecisionStatus ?? 'INCLUDED'),
    attributionMethod: row.attributionMethod ?? 'DIRECT',
    allocationBasis: row.allocationBasis ?? '',
    evidenceRef: row.evidenceRef ?? '',
  }))
}
export const isNewcomer = (form: FormValues, items: CommissionItem[]) =>
  form.paymentStage !== 'INSURER_TO_GA' &&
  items.some((item) => String(item.commissionItemId) === form.commissionItemId && item.itemCode === 'NEWCOMER_SUPPORT')
export const availableItems = (form: FormValues, items: CommissionItem[]) =>
  items.filter(
    (item) =>
      item.cashflowType === form.cashflowType &&
      (form.paymentStage !== 'INSURER_TO_GA' || item.itemCode !== 'NEWCOMER_SUPPORT'),
  )
export function inclusionOptions(insurer: boolean, newcomer: boolean): [string, string][] {
  if (newcomer)
    return [
      ['EXCLUDED:NEW_AGENT_SUPPORT', '제외 · 신인 지원'],
      ['REVIEW_REQUIRED', '검토필요'],
    ]
  return [
    ['INCLUDED', '산입'],
    ['REVIEW_REQUIRED', '검토필요'],
    ...(insurer
      ? [['EXCLUDED:COMPLIANCE_3PCT', '제외 · 준법경영비']]
      : [['EXCLUDED:NEW_AGENT_SUPPORT', '제외 · 신인 지원']]),
  ] as [string, string][]
}
export function normalizeRow(row: AttributionRow, insurer: boolean, newcomer: boolean): AttributionRow {
  if (newcomer)
    return {
      ...row,
      contractId: '',
      attributionMethod: 'NEWCOMER_NON_CONTRACT',
      decision: row.decision === 'REVIEW_REQUIRED' ? row.decision : 'EXCLUDED:NEW_AGENT_SUPPORT',
    }
  const forbidden =
    row.attributionMethod === 'NEWCOMER_NON_CONTRACT' ||
    (insurer && ['SETTLEMENT_SUPPORT_MONTHLY', 'FIRST_CONTRACT_CARRY_FORWARD'].includes(row.attributionMethod))
  return {
    ...row,
    attributionMethod: forbidden ? 'DIRECT' : row.attributionMethod,
    decision: inclusionOptions(insurer, false).some(([value]) => value === row.decision) ? row.decision : 'INCLUDED',
  }
}
export function filterAgentId(agentId: string, agents: Agent[]) {
  const agent = agents.find((candidate) => String(candidate.agentId) === agentId)
  return agent?.rankCode && agent.rankCode !== 'FC' ? '' : agentId
}
export function selectContract(row: AttributionRow, contractId: string, contracts: Contract[]) {
  const date = contracts.find((contract) => String(contract.contractId) === contractId)?.contractDate?.slice(0, 10)
  return {
    ...row,
    contractId,
    attributionDate: date && (!row.attributionDate || row.attributionDate < date) ? date : row.attributionDate,
  }
}
export class FormValidationError extends Error {
  field: string
  constructor(message: string, field: string) {
    super(message)
    this.field = field
  }
}
const blank = (value: string) => value.trim() || null
function requireValue(value: string, label: string, field: string, numeric = false) {
  if (!value.trim() || (numeric && (!Number.isFinite(Number(value)) || Number(value) < 0)))
    throw new FormValidationError(`필수값을 확인하세요: ${label}`, field)
}
export function buildPayload(form: FormValues, rows: AttributionRow[], newcomer: boolean): PaymentPayload {
  requireValue(form.sourceBusinessKey, '업무키', 'bizKey')
  requireValue(form.settlementMonth, '정산월', 'settlementMonth')
  const insurer = form.paymentStage === 'INSURER_TO_GA'
  if (!insurer) requireValue(form.agentId, '수령 설계사', 'recipient')
  requireValue(form.commissionItemId, '수수료 항목', 'item')
  requireValue(form.amount, '금액', 'amount', true)
  if (!rows.length) throw new FormValidationError('귀속행을 하나 이상 추가하세요.', 'attributions')
  const attributions: AttributionPayload[] = rows.map((raw) => {
    const row = normalizeRow(raw, insurer, newcomer)
    if (!newcomer) requireValue(row.contractId, '귀속 계약', `attr-${row.key}-contractId`)
    requireValue(row.attributionDate, '귀속일', `attr-${row.key}-attributionDate`)
    requireValue(row.amount, '귀속금액', `attr-${row.key}-amount`, true)
    const [status, exclusion = 'NONE'] = row.decision.split(':') as [
      AttributionRequest['inclusionDecisionStatus'],
      AttributionRequest['exclusionType'],
    ]
    if ((status === 'EXCLUDED' || newcomer) && !blank(row.evidenceRef))
      throw new FormValidationError('제외 귀속행은 제외유형과 증빙을 입력해야 합니다.', `attr-${row.key}-evidenceRef`)
    if (row.attributionMethod === 'APPROVED_ALLOCATION' && !blank(row.allocationBasis))
      throw new FormValidationError('승인 배부에는 배부정책을 입력해야 합니다.', `attr-${row.key}-allocationBasis`)
    const exclusions = new Map([
      ['NEW_AGENT_SUPPORT', '신인활동지원비'],
      ['COMPLIANCE_3PCT', '준법경영비'],
      ['NONE', ''],
    ])
    return {
      contractId: newcomer ? null : Number(row.contractId),
      attributionDate: row.attributionDate,
      amount: Number(row.amount),
      inclusionDecisionStatus: status,
      exclusionType: exclusion,
      inclusionDecisionReason:
        status === 'EXCLUDED'
          ? `한도 제외: ${exclusions.get(exclusion ?? 'NONE')}`
          : status === 'INCLUDED'
            ? '화면에서 산입으로 지정'
            : '화면에서 검토필요로 지정',
      allocationBasis: blank(row.allocationBasis),
      evidenceRef: blank(row.evidenceRef),
      attributionMethod: row.attributionMethod,
    }
  })
  return {
    sourceType: SOURCE_TYPE,
    sourceBusinessKey: form.sourceBusinessKey.trim(),
    contractId: attributions[0].contractId,
    agentId: insurer ? null : Number(form.agentId),
    commissionItemId: Number(form.commissionItemId),
    amount: Number(form.amount),
    settlementMonth: `${form.settlementMonth}-01`,
    cashflowType: form.cashflowType,
    scheduledPaymentDate: `${form.settlementMonth}-01`,
    paymentStage: form.paymentStage,
    allocationPolicyVersion: null,
    attributions,
    evidenceRef: blank(form.evidenceRef),
    note: null,
  }
}
const GATES = [
  { label: '작성중(DRAFT) 저장', codes: ['FGC-TRAN-005'] },
  { label: '귀속행 입력', codes: ['FGC-TRAN-002'] },
  { label: '귀속합계 = 지급액', codes: ['FGC-TRAN-003'] },
  { label: '검토필요 귀속 해소', codes: ['FGC-CAP-002'] },
  { label: '1,200% 사전검증', codes: ['FGC-CAP-001', 'FGC-CAP-003', 'FGC-CAP-004'] },
  { label: '전부 통과 → 확정', codes: [] },
]
export function gateStates(result: Precheck | null) {
  const matched: Blocker[][] = GATES.map(() => [])
  for (const blocker of result?.blockers ?? []) {
    const index = GATES.findIndex((gate) => gate.codes.includes(blocker.code ?? ''))
    matched[index < 0 ? 5 : index].push(blocker)
  }
  let blockedBefore = false
  return GATES.map((gate, index) => {
    const failures = matched[index]
    const state =
      result == null
        ? 'todo'
        : result.confirmable === true
          ? 'pass'
          : failures.length
            ? 'fail'
            : blockedBefore
              ? 'todo'
              : 'pass'
    if (failures.length) blockedBefore = true
    return { ...gate, state, failures }
  })
}
// confirm failFirst records only the first blocker, never a later CAP code.
export const hasRecordableBlocker = (result: Precheck) => RECORDABLE_CODES.includes(result.blockers?.[0]?.code ?? '')
export function blockedContractNo(result: Precheck, code: string, contracts: Contract[]) {
  const blockers = (result.blockers ?? []).filter(
    (blocker) => blocker.contractId != null && RECORDABLE_CODES.includes(blocker.code ?? ''),
  )
  const target = blockers.find((blocker) => blocker.code === code) ?? blockers[0]
  if (!target) return undefined
  return contracts.find((contract) => contract.contractId === target?.contractId)?.contractNo
}
