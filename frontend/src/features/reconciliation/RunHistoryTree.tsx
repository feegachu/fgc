import { HierarchyTree } from '../../components/HierarchyTree'
import type { Run } from './api'
import { runHistoryGroups } from './runHistoryGroups'

export function RunHistoryTree({ runs, selectedId, onSelect }: { runs: Run[]; selectedId: string; onSelect: (key: string, members: Run[]) => void }) {
  const { nodes, groups } = runHistoryGroups(runs)
  return <HierarchyTree nodes={nodes} label="월 통합검증별 대사 실행 이력" selectedId={selectedId} onSelect={key => {
    const members = key.startsWith('run:') ? runs.filter(run => String(run.reconciliationRunId) === key.slice(4)) : groups.get(key) ?? []
    onSelect(key, members)
  }} />
}
