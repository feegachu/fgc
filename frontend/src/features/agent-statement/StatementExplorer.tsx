import { useState, type ReactNode } from 'react'
import { HierarchyTree, ViewModeSwitch } from '../../components/HierarchyTree'
import { organizationTree } from './organizationTree'
import { StatementResults } from './StatementResults'
import { int, won } from '../../lib/format'
import type { StatementAgentOverview } from './api'

export function StatementExplorer({
  rows,
  selectedId,
  selectedBranchId,
  onSelect,
  onSelectBranch,
  viewControl,
  onSelectGroup,
}: {
  onSelectGroup?: (key: string, members: StatementAgentOverview[], label: string) => void
  viewControl: ReactNode
  rows: StatementAgentOverview[]
  selectedId: string | null
  selectedBranchId: number | null
  onSelect: (id: string) => void
  onSelectBranch: (id: number) => void
}) {
  const [groupId, setGroupId] = useState<string | null>(null)
  const [groupTitle, setGroupTitle] = useState('')
  const tree = organizationTree(rows)
  const branches = [...new Map(rows.map((row) => [row.branchId, row.branchName])).entries()]
  const selected = rows.find((row) => String(row.agentId) === selectedId)
  const activeBranch = selectedBranchId ?? selected?.branchId ?? branches[0]?.[0]
  const visible = (groupId && tree.groups.get(groupId)) || rows.filter(row => row.branchId === activeBranch)
  return (
    <StatementResults className="ast-explorer" label="지점 및 설계사 명세 탐색">
          <div className="surface-header ast-explorer-heading">
            <h3>
              {groupId ? groupTitle : branches.find(([id]) => id === activeBranch)?.[1]}{' '}
              <span className="ast-muted">설계사 {visible.length}명</span>
            </h3>
            {viewControl}
          </div>
      <div className="ast-explorer-columns">
        <div className="ast-tree-pane"><HierarchyTree nodes={tree.nodes} selectedId={selectedBranchId !== null ? groupId ?? 'branch:' + selectedBranchId : selectedId ? 'agent:' + selectedId : groupId}
          label="지점과 설계사 목록" onSelect={id => {
            if (id.startsWith('agent:')) { setGroupId(null); onSelect(id.slice(6)); return }
            const members = tree.groups.get(id) ?? []
            setGroupId(id)
            const findLabel = (nodes: typeof tree.nodes): string => { for (const node of nodes) { if (node.id === id) return node.label; const nested = node.children && findLabel(node.children); if (nested) return nested } return '' }
            setGroupTitle(findLabel(tree.nodes))
            if (onSelectGroup) onSelectGroup(id, members, findLabel(tree.nodes))
            else if (members[0]) onSelectBranch(members[0].branchId)
          }} /></div>
        <div className="ast-agent-area">
          <div className="ast-agent-cards">
            {visible.map((row) => (
              <button data-guide-feature="features.statement-explorer.button-1"
                className={`ast-agent-card ${String(row.agentId) === selectedId ? 'is-active' : ''}`}
                key={row.agentId}
                aria-pressed={String(row.agentId) === selectedId}
                onClick={() => onSelect(String(row.agentId))}
              >
                <span className="ast-agent-card-head">
                  <span className="material-symbols-rounded" aria-hidden="true">
                    account_circle
                  </span>
                  <strong>{row.agentName}</strong>
                  <span className="material-symbols-rounded" aria-hidden="true">
                    arrow_forward
                  </span>
                </span>
                <span className="ast-muted">
                  {row.agentCode}  {row.organizationName}
                </span>
                <span className="ast-agent-card-metric">
                  <span>명세 연결 계약</span>
                  <strong>{int(row.contractCount)}건</strong>
                </span>
                <span className="ast-agent-card-metric">
                  <span>지급 확정</span>
                  <strong>{won(row.paymentAmount)}</strong>
                </span>
                <span className="ast-agent-card-extra">
                  보류 {won(row.heldAmount)}  조정 {won(row.adjustmentAmount)}
                  <br />
                  환수 계산 {won(row.clawbackAmount)}
                  {row.missingAmountCount > 0 && ` 금액 미확인 ${row.missingAmountCount}건`}
                </span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </StatementResults>
  )
}

export function StatementViewSwitch({
  value,
  onChange,
}: {
  value: 'tree' | 'table'
  onChange: (value: 'tree' | 'table') => void
}) {
  return <ViewModeSwitch value={value} onChange={onChange} label="명세 보기 방식" />
}
