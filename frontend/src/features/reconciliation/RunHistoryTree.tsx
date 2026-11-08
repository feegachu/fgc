import { HierarchyTree, type HierarchyNode } from '../../components/HierarchyTree'
import type { Run } from './api'
import { PAYMENT_STAGE } from './labels'

export function runHistoryGroups(runs: Run[]) {
  const groups = new Map<string, Run[]>()
  const group = (id: string, label: string, members: Run[], children: HierarchyNode[]): HierarchyNode => {
    groups.set(id, members)
    return { id, label, meta: `${members.length}건`, children }
  }
  const nodes = [...new Set(runs.map(run => run.validationRunId != null ? `validation:${run.validationRunId}` : `standalone:${run.reconciliationRunId}`))].map(key => {
    const members = runs.filter(run => (run.validationRunId != null ? `validation:${run.validationRunId}` : `standalone:${run.reconciliationRunId}`) === key)
    const first = members[0]
    const label = first.validationRunId != null ? `월 통합검증 #${first.validationRunNo ?? first.validationRunId}` : `개별 대사 #${first.reconciliationRunId}`
    return group(key, label, members, [...new Set(members.map(run => run.paymentStage ?? ''))].map(stage => {
      const direction = members.filter(run => (run.paymentStage ?? '') === stage)
      return group(`${key}:${stage}`, PAYMENT_STAGE[stage as keyof typeof PAYMENT_STAGE] ?? '지급 방향 확인 필요', direction,
        [...new Set(direction.map(run => run.insurerId ?? 0))].map(insurerId => {
          const company = direction.filter(run => (run.insurerId ?? 0) === insurerId)
          const companyKey = `${key}:${stage}:insurer:${insurerId}`
          return group(companyKey, company[0].insurerName ?? '전체 보험회사', company,
            company.map(run => ({ id: `run:${run.reconciliationRunId}`, label: `대사 #${run.reconciliationRunId}`, meta: `${run.targetCount ?? 0}건`, icon: 'description' })))
        }))
    }))
  })
  return { nodes, groups }
}
export function RunHistoryTree({ runs, selectedId, onSelect }: { runs: Run[]; selectedId: string; onSelect: (key: string, members: Run[]) => void }) {
  const { nodes, groups } = runHistoryGroups(runs)
  return <HierarchyTree nodes={nodes} label="월 통합검증별 대사 실행 이력" selectedId={selectedId} onSelect={key => {
    const members = key.startsWith('run:') ? runs.filter(run => String(run.reconciliationRunId) === key.slice(4)) : groups.get(key) ?? []
    onSelect(key, members)
  }} />
}
