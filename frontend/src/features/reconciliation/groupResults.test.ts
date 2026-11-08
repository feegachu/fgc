import { expect, it } from 'vitest'
import { aggregateSummaries, paginateGroupResults } from './groupResults'
import { runHistoryGroups } from './RunHistoryTree'
import type { Result, Run } from './api'

it('combines exact won amounts and keeps unavailable totals unavailable', () => {
  expect(aggregateSummaries([
    { resultCount: 2, expectedTotal: 100001, actualTotal: 90000, differenceTotal: 10001 },
    { resultCount: 3, expectedTotal: 200002, actualTotal: 180000, differenceTotal: 20002 },
  ])).toMatchObject({ resultCount: 5, expectedTotal: 300003, actualTotal: 270000, differenceTotal: 30003 })
  expect(aggregateSummaries([{ expectedTotal: 100 }, {}]).expectedTotal).toBeUndefined()
  expect(() => aggregateSummaries([{ expectedTotal: Number.MAX_SAFE_INTEGER }, { expectedTotal: 1 }])).toThrow('표시 범위')
})
it('filters and sorts all combined runs before paging, with duplicate protection', () => {
  const rows: Result[] = Array.from({ length: 45 }, (_, index) => ({ reconciliationResultId: index + 1,
    resultType: index % 3 === 0 ? 'MATCHED' : 'MISMATCH', differenceAmount: index }))
  const second = paginateGroupResults([...rows, rows[3]], 2, true, 'differenceAmount,desc')
  expect(second.totalElements).toBe(30)
  expect(second.content.map(row => row.reconciliationResultId)).toEqual([15, 14, 12, 11, 9, 8, 6, 5, 3, 2])
  expect(() => paginateGroupResults([{}], 1, false, '')).toThrow('식별자')
})
it('groups by real validation run, payment direction, insurer and reconciliation run', () => {
  const runs: Run[] = [
    { reconciliationRunId: 10, validationRunId: 999, validationRunNo: 1, paymentStage: 'GA_TO_FC', insurerId: 1, insurerName: '보험A' },
    { reconciliationRunId: 11, validationRunId: 999, validationRunNo: 1, paymentStage: 'INSURER_TO_GA', insurerId: 2, insurerName: '보험B' },
    { reconciliationRunId: 12 }, { reconciliationRunId: 13 },
  ]
  const { nodes, groups } = runHistoryGroups(runs)
  expect(nodes.map(node => node.label)).toEqual(['월 통합검증 #1', '개별 대사 #12', '개별 대사 #13'])
  expect(groups.get('validation:999')).toEqual(runs.slice(0, 2))
  expect(groups.get('validation:999:GA_TO_FC:insurer:1')).toEqual([runs[0]])
  expect(nodes[0].children![0].children![0].children![0].id).toBe('run:10')
})
