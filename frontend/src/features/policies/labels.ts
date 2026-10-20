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
  return Object.hasOwn(agentRankLabels, code) ? agentRankLabels[code as keyof typeof agentRankLabels] : code
}
