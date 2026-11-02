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

const policyCodeLabels: Record<string, string> = {
  RATE: '요율',
  FIXED: '정액',
  MONTHLY_EQUIVALENT_FIRST_PREMIUM: '월납 환산 초회 보험료',
  NONE: '제외 없음',
  NEW_AGENT_SUPPORT: '신인 지원금',
  DIRECT: '직접 귀속',
  SETTLEMENT_SUPPORT_MONTHLY: '정착지원금 월별 귀속',
  FIRST_CONTRACT_CARRY_FORWARD: '초회 계약 이월',
  APPROVED_ALLOCATION: '승인된 배분',
  MANUAL_REVIEW: '수동 검토',
  TOTAL_PAID: '총 지급액',
  CONFIRMED_PAID: '확정 지급액',
  PAID_COMMISSION: '지급 수수료',
  PAID_ATTRIBUTED_AMOUNT: '확정 지급 귀속액',
}
export function policyCodeLabel(code?: string | null) {
  return code ? (policyCodeLabels[code] ?? '확인 필요') : '-'
}
