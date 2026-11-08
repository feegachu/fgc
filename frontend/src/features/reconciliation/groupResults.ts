import type { Result, Summary } from './api'
import { sumAmounts } from './amounts'

export function aggregateSummaries(summaries: Summary[]): Summary {
  const money = (key: 'expectedTotal' | 'actualTotal' | 'differenceTotal') => {
    const exact = sumAmounts(summaries.map(summary => summary[key]))
    if (exact == null) return undefined
    const value = Number(exact)
    if (!Number.isSafeInteger(value)) throw new Error('집계 금액이 표시 범위를 초과했습니다. 개별 실행을 선택하세요.')
    return value
  }
  return { resultCount: summaries.reduce((sum, row) => sum + (row.resultCount ?? 0), 0),
    matchedCount: summaries.reduce((sum, row) => sum + (row.matchedCount ?? 0), 0),
    exceptionCount: summaries.reduce((sum, row) => sum + (row.exceptionCount ?? 0), 0),
    expectedTotal: money('expectedTotal'), actualTotal: money('actualTotal'), differenceTotal: money('differenceTotal') }
}
export function paginateGroupResults(rows: Result[], page: number, onlyMismatch: boolean, sort: string) {
  if (rows.some(row => !Number.isSafeInteger(row.reconciliationResultId) || (row.reconciliationResultId ?? 0) <= 0))
    throw new Error('대사 결과 식별자가 없습니다. 개별 실행을 다시 조회하세요.')
  const [key, direction] = (sort || 'createdAt,desc').split(',')
  const unique = [...new Map(rows.map(row => [row.reconciliationResultId, row])).values()].filter(row => !onlyMismatch || row.resultType !== 'MATCHED')
  unique.sort((a, b) => {
    const left = a[key as keyof Result], right = b[key as keyof Result]
    const compared = typeof left === 'number' && typeof right === 'number' ? left - right
      : String(left ?? '').localeCompare(String(right ?? ''), 'ko', { numeric: true })
    return (direction === 'asc' ? 1 : -1) * (compared || (a.reconciliationResultId ?? 0) - (b.reconciliationResultId ?? 0))
  })
  return { content: unique.slice((page - 1) * 20, page * 20), page, size: 20, totalElements: unique.length, totalPages: Math.ceil(unique.length / 20) }
}
