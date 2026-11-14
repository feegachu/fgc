import { screenMessage, withoutMiddleDots } from '../../lib/screenText'
import type { ExceptionSearchResult } from './api'

type ExceptionType = NonNullable<NonNullable<ExceptionSearchResult['summary']>[number]['type']>

const defaultSummaryTypes = [
  ['CAP_VIOLATION', '1,200% 위반'],
  ['RECONCILIATION_MISMATCH', '대사 불일치'],
  ['ARBITRAGE_CANDIDATE', '차익거래 검토대상'],
  ['CAP_WARNING', '1,200% 주의'],
  ['DATA_QUALITY', '데이터 품질'],
  ['POLICY_MISSING', '정책 없음'],
  ['REFUND_TABLE_MISSING', '환급률표 없음'],
] satisfies [ExceptionType, string][]

export function exceptionSummaryCards(summary: ExceptionSearchResult['summary']) {
  const rows = summary ?? []
  const defaults = defaultSummaryTypes.map(([type, typeLabel]) =>
    rows.find((row) => row.type === type) ?? { type, typeLabel, count: 0 })
  return [...defaults, ...rows.filter((row) => !defaultSummaryTypes.some(([type]) => type === row.type))]
}

const summaryDescriptions = {
  CAP_WARNING: '사용률 경고 기준 이상',
  CAP_VIOLATION: '확정 위반 판정',
  CAP_REVIEW_REQUIRED: '한도 판정 근거 확인 필요',
  RECONCILIATION_MISMATCH: '대사 금액 차이 확인 필요',
  JOURNAL_IMBALANCE: '차변/대변 합계 확인 필요',
  JOURNAL_CORRECTION_REQUIRED: '역분개/재기표 검토 필요',
  ARBITRAGE_CANDIDATE: '위반 확정 아님',
  REFUND_TABLE_MISSING: '적용할 환급률표 확인 필요',
  PRODUCT_CODE_MISMATCH: '상품코드 연결 확인 필요',
  POLICY_MISSING: '적용 가능한 정책 확인 필요',
  POLICY_DUPLICATE: '중복 적용 정책 확인 필요',
  ALLOCATION_EVIDENCE_MISSING: '배부 근거 보완 필요',
  DATA_QUALITY: '입력 데이터 확인 필요',
  OTHER: '상세 내용 확인 필요',
} satisfies Record<ExceptionType, string>

const sourceLabels: Record<string, string> = {
  COMMISSION_TRANSACTION: '지급 건',
  INSURANCE_CONTRACT: '계약',
  SCHEDULE_HEADER: '스케줄',
  JOURNAL_HEADER: '분개',
  RECONCILIATION_RESULT: '대사 결과',
  ARBITRAGE_CHECK: '차익거래 검증',
  CAP_CHECK: '1,200% 판정',
}

/** 원천 참조를 화면 이름으로 보인다. 계약은 내부 번호 대신 계약번호를 쓰고, 모르는 유형만 'TYPE:ID' 원문으로 남긴다. */
export function sourceReference(type?: string, contractNo?: string) {
  if (!type || !Object.hasOwn(sourceLabels, type)) return '원본 보기'
  return type === 'INSURANCE_CONTRACT' && contractNo ? `${sourceLabels[type]} ${contractNo}` : sourceLabels[type]
}

export function exceptionSummaryDescription(type?: string) {
  return type && Object.hasOwn(summaryDescriptions, type)
    ? summaryDescriptions[type as ExceptionType]
    : summaryDescriptions.OTHER
}

// 자동 정정 이력에 저장된 연결 키는 감사·추적용으로 보존하고 화면에서는 제외한다.
const correctionKey = String.raw`(?:JCG-\d+-[a-zA-Z0-9]+|EXCP-(?:SCHEDULE-|RECOVERY-)?\d+)`
const internalEvidence = new RegExp(`^${correctionKey}$`)
const correctionReference = new RegExp(`^((?:원장 정정|스케줄(?:·| 및 )계약 정정) 후 재대사 일치 확인) \\(${correctionKey}, 대사 \\d+\\)`)

const recoveryConfirmation = /^원지급 \d+번의 환수 차감 ([\d,]+(?:\.\d+)?)원 확정 확인(?: |$)/

export function exceptionHistoryActionLabel(action: { reason?: string | null; actionTypeLabel?: string }) {
  return recoveryConfirmation.test(action.reason ?? '') ? '환수 차감 확정' : action.actionTypeLabel
}

export function exceptionHistoryReason(reason?: string | null) {
  const recovery = recoveryConfirmation.exec(reason ?? '')
  if (recovery) {
    const amount = Number(recovery[1].replaceAll(',', '')).toLocaleString('ko-KR', { maximumFractionDigits: 20 })
    return `환수액 ${amount}원을 수수료에서 차감하고 확정했습니다.`
  }
  return reason == null ? reason : withoutMiddleDots(reason.replace(correctionReference, '$1'))
}

export function exceptionHistoryEvidence(evidence?: string | null) {
  const normalized = evidence && withoutMiddleDots(evidence)
  const calculation = /^환수 계산 v(\d+) 원지급 \d+ 차감 (?:CLAWBACK-RECOVERY|EXCP-RECOVERY):[\d:]+$/.exec(normalized ?? '')
  if (calculation) return `환수 계산 결과 (${calculation[1]}차)`
  const notice = /^환수 통지서 (.+) 차감 (?:CLAWBACK-RECOVERY|EXCP-RECOVERY):[\d:]+$/.exec(normalized ?? '')
  if (notice) return `환수 통지서 ${notice[1]}`
  return evidence && !internalEvidence.test(evidence.trim()) ? evidence : null
}


export function exceptionListTitle(item: { type?: string; title?: string; reasonLabel?: string }) {
  const title = screenMessage(item.title)
  return withoutMiddleDots(item.type === 'RECONCILIATION_MISMATCH' && item.reasonLabel
    ? title.replace(/^대사 불일치(?=,|$)/, () => item.reasonLabel!)
    : title)
}
