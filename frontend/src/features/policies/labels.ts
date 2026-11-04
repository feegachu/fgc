import { isDeveloperText } from '../../lib/screenText'

// 운영정책서 제15조의 공식 직급명. BRANCH_MANAGER는 지사장이다.
const agentRankLabels = {
  FC: '설계사',
  TEAM_LEADER: '팀장',
  BRANCH_MANAGER: '지사장',
  DIVISION_HEAD: '본부장',
} as const

export function policyAgentRankLabel(code?: string | null, paymentStage?: string) {
  // 원수사→GA는 개인 직급이 해당 없고, 다른 단계의 null은 직급 정보 누락이다.
  if (code === null) return paymentStage === 'INSURER_TO_GA' ? '해당 없음(GA 수령)' : '-'
  if (code == null || code.trim() === '') return '-'
  return Object.hasOwn(agentRankLabels, code) ? agentRankLabels[code as keyof typeof agentRankLabels] : '확인 필요'
}

/** 정책 파라미터는 저장 키로 찾는다. 화면에는 저장 키와 코드값을 쓰지 않는다(SRC-074). */
const parameterLabels: Record<string, string> = {
  ARBITRAGE_EFFECTIVE_CONTRACT_DATE: '차익거래 적용 시작일',
  ARBITRAGE_REFUND_ADDITION_MONTHS: '해약환급금 합산 기간(개월)',
  ARBITRAGE_REFUND_ADDITION_BOUNDARY_INCLUSIVE: '기간 경계일 포함',
  TOLERANCE_AMOUNT_KRW: '대사 허용오차(원)',
  TOLERANCE_DAYS: '대사 허용오차(일)',
  REFUND_RATE_BASIS: '환급률 적용 기준',
}
const parameterValueLabels: Record<string, string> = {
  true: '예',
  false: '아니오',
  MONTHLY_EQUIVALENT_FIRST_PREMIUM_X12: '월납환산 초회보험료 × 12',
}
export const policyParameterLabel = (key: string) => parameterLabels[key] ?? '설정 항목'
export function policyParameterValue(value: unknown) {
  const text = String(value)
  return parameterValueLabels[text] ?? (isDeveloperText(text) ? '확인 필요' : text)
}

const policyCodeLabels: Record<string, string> = {
  RATE: '요율',
  FIXED: '정액',
  MONTHLY_EQUIVALENT_FIRST_PREMIUM: '월납환산 초회보험료',
  NONE: '제외 없음',
  NEW_AGENT_SUPPORT: '신인 지원금',
  // 귀속 방법은 화면정의서 TRAN-W01 귀속 방법 표기와 같다.
  DIRECT: '직접 귀속',
  SETTLEMENT_SUPPORT_MONTHLY: '정착지원금 월귀속',
  FIRST_CONTRACT_CARRY_FORWARD: '최초계약 이월',
  APPROVED_ALLOCATION: '승인배부',
  MANUAL_REVIEW: '수동검토',
  NOT_APPLICABLE: '해당 없음',
  TOTAL_PAID: '총 지급액',
  CONFIRMED_PAID: '확정 지급액',
  PAID_COMMISSION: '지급 수수료',
  PAID_ATTRIBUTED_AMOUNT: '확정 지급 귀속액',
}
export function policyCodeLabel(code?: string | null) {
  return code ? (policyCodeLabels[code] ?? '확인 필요') : '-'
}
