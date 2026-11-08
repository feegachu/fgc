import { expect, it } from 'vitest'
import { organizationTree } from './organizationTree'
import type { StatementAgentOverview } from './api'

function member(agentId: number, teamId: number, rankCode = 'FC'): StatementAgentOverview {
  return { agentId, agentCode: `FC-${agentId}`, agentName: `설계사${agentId}`, branchId: 3, branchName: '강동지사',
    organizationId: teamId, organizationName: `팀${teamId}`, rankCode, contractCount: 1,
    paymentAmount: 100, heldAmount: 20, adjustmentAmount: -10, clawbackAmount: 5, entryCount: 1, missingAmountCount: 0,
    organizationPath: [
      { organizationId: 1, parentId: null, organizationName: '우리GA', organizationType: 'GA' },
      { organizationId: 2, parentId: 1, organizationName: '동부본부', organizationType: 'DIVISION' },
      { organizationId: 3, parentId: 2, organizationName: '강동지사', organizationType: 'BRANCH' },
      { organizationId: teamId, parentId: 3, organizationName: `팀${teamId}`, organizationType: 'TEAM' },
    ] }
}
it('starts at headquarters and keeps branch, team, rank and name with aggregate selection', () => {
  const rows = [member(10, 4, 'TEAM_LEADER'), member(11, 4), member(12, 5)]
  const { nodes, groups } = organizationTree(rows)
  expect(nodes).toHaveLength(1)
  expect(nodes[0]).toMatchObject({ label: '동부본부', meta: '3명' })
  expect(JSON.stringify(nodes)).not.toContain('우리GA')
  const headquarters = nodes[0]
  expect(headquarters.label).toBe('동부본부')
  const branch = headquarters.children![0]
  expect(branch.children!.map(node => node.label)).toEqual(['팀4', '팀5'])
  expect(branch.children![0].children!.map(node => node.label)).toEqual(['팀장', '설계사'])
  expect(branch.children![0].children![0].children![0]).toMatchObject({ id: 'agent:10', label: '설계사10' })
  expect(groups.has('organization:1')).toBe(false)
  expect(groups.get('organization:2')).toEqual(rows)
  expect(groups.get('organization:4')?.map(row => row.agentId)).toEqual([10, 11])
  expect(groups.get('organization:4:rank:FC')?.map(row => row.agentId)).toEqual([11])
})
it('keeps separate headquarters under a hidden GA and never invents absent ancestors', () => {
  const first = member(1, 4)
  const second = { ...member(2, 6), branchId: 8, branchName: '서부지사', organizationPath: [
    first.organizationPath![0], { organizationId: 8, parentId: 1, organizationName: '서부지사', organizationType: 'BRANCH' },
  ] }
  const tree = organizationTree([first, second])
  expect(tree.groups.has('organization:1')).toBe(false)
  expect(tree.nodes.map(node => node.label)).toEqual(['동부본부', '서부지사'])
  expect(tree.groups.get('organization:3')).toEqual([first])
  const legacy = organizationTree([{ ...first, organizationPath: undefined }])
  expect(legacy.nodes[0].label).toBe('강동지사')
  expect(legacy.nodes.some(node => node.label === '우리GA')).toBe(false)
})
