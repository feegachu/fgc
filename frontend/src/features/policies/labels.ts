// 운영정책서 제15조의 공식 직급명. BRANCH_MANAGER는 지사장이다.
const agentRankLabels = {
  FC: '설계사',
  TEAM_LEADER: '팀장',
  BRANCH_MANAGER: '지사장',
  DIVISION_HEAD: '본부장',
} as const

export function policyAgentRankLabel(code?: string | null) {
  // 수수료 규칙 API에서 null은 직급을 한정하지 않는 전체 적용을 뜻한다.
  if (code === null) return '전체'
  if (code == null || code.trim() === '') return '-'
  return Object.hasOwn(agentRankLabels, code) ? agentRankLabels[code as keyof typeof agentRankLabels] : code
}
