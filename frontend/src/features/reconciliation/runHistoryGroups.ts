import { createElement } from 'react'
import type { HierarchyNode } from '../../components/HierarchyTree'
import type { Run } from './api'
import { PAYMENT_STAGE } from './labels'
import { RunHistoryCard } from './RunHistoryCard'

export function runHistoryGroups(runs: Run[]) {
  const groups = new Map<string, Run[]>()
  const group = (id: string, label: string, members: Run[], children: HierarchyNode[]): HierarchyNode => {
    // Keep old runs selectable as history, but never add them to current totals.
    groups.set(id, members.filter(run => !run.superseded))
    return { id, label, meta: `${members.length}건`, children }
  }
  const nodes = [...new Set(runs.map(run => run.validationRunId != null ? `validation:${run.validationRunId}` : `standalone:${run.reconciliationRunId}`))].map(key => {
    const members = runs.filter(run => (run.validationRunId != null ? `validation:${run.validationRunId}` : `standalone:${run.reconciliationRunId}`) === key)
    const first = members[0]
    const label = first.validationRunId != null ? `월 통합검증 #${first.validationRunNo ?? first.validationRunId}` : `개별 대사 #${first.reconciliationRunId}`
    const root = group(key, label, members, [...new Set(members.map(run => run.paymentStage ?? ''))].map(stage => {
      const direction = members.filter(run => (run.paymentStage ?? '') === stage)
      const branch = group(`${key}:${stage}`, PAYMENT_STAGE[stage as keyof typeof PAYMENT_STAGE] ?? '지급 방향 확인 필요', direction,
        direction.map(run => ({ id: `run:${run.reconciliationRunId}`, label: run.insurerName ?? '전체 보험회사', icon: null, className: 'reco-history-leaf',
          content: createElement(RunHistoryCard, { run }) })))
      return { ...branch, icon: null, meta: undefined }
    }))
    return { ...root, meta: first.settlementMonth?.slice(0, 7) }
  })
  return { nodes, groups }
}
