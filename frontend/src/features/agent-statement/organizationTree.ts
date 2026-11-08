import type { HierarchyNode } from '../../components/HierarchyTree'
import type { StatementAgentOverview } from './api'

const ranks: Record<string, string> = { DIVISION_HEAD: '본부장', BRANCH_MANAGER: '지사장', TEAM_LEADER: '팀장', FC: '설계사' }
export function organizationTree(rows: StatementAgentOverview[]) {
  const groups = new Map<string, StatementAgentOverview[]>()
  type Branch = { id: number; label: string; members: StatementAgentOverview[]; direct: StatementAgentOverview[]; children: Map<number, Branch> }
  const roots = new Map<number, Branch>()
  for (const row of rows) {
    const fallback = [{ organizationId: row.branchId, organizationName: row.branchName }]
    if (row.organizationId != null && row.organizationId !== row.branchId) fallback.push({ organizationId: row.organizationId, organizationName: row.organizationName })
    // 법인(GA)은 본사와 조회 범위가 겹치므로 조직 탐색은 본사부터 표시한다.
    const visiblePath = row.organizationPath?.filter(item => item.organizationType !== 'GA')
    const path = visiblePath?.length ? visiblePath : fallback
    let siblings = roots
    for (const [index, item] of path.entries()) {
      let branch = siblings.get(item.organizationId)
      if (!branch) { branch = { id: item.organizationId, label: item.organizationName, members: [], direct: [], children: new Map() }; siblings.set(branch.id, branch) }
      branch.members.push(row)
      if (index === path.length - 1) branch.direct.push(row)
      siblings = branch.children
    }
  }
  const build = (branches: Map<number, Branch>): HierarchyNode[] => [...branches.values()]
    .sort((a, b) => a.label.localeCompare(b.label, 'ko', { numeric: true })).map(branch => {
      const id = `organization:${branch.id}`
      groups.set(id, branch.members)
      const rankNodes = [...new Set(branch.direct.map(row => row.rankCode ?? ''))]
        .sort((a, b) => ['DIVISION_HEAD', 'BRANCH_MANAGER', 'TEAM_LEADER', 'FC', ''].indexOf(a) - ['DIVISION_HEAD', 'BRANCH_MANAGER', 'TEAM_LEADER', 'FC', ''].indexOf(b))
        .map(rank => {
          const members = branch.direct.filter(row => (row.rankCode ?? '') === rank)
          const key = `${id}:rank:${rank}`
          groups.set(key, members)
          return { id: key, label: ranks[rank] ?? (rank || '직급 미등록'), meta: `${members.length}명`,
            children: members.map(row => ({ id: `agent:${row.agentId}`, label: row.agentName, icon: 'person' })) }
        })
      return { id, label: branch.label, meta: `${branch.members.length}명`, children: [...rankNodes, ...build(branch.children)] }
    })
  const nodes = build(roots)
  return { nodes, groups }
}
