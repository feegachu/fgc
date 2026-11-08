import { queryOptions } from '@tanstack/react-query'
import { get, type JournalCriteria } from './api'

export interface ExplorerPage<T> { content: T[]; page: number; totalElements: number; totalPages: number }
export interface LedgerContract { contractId: number; contractNo: string; journalCount: number; debitTotal: number; creditTotal: number; agentName?: string; insurerName?: string }
export interface LedgerEntry {
  journalHeaderId: number; journalNo: string; journalDate: string; journalType: string; status: string
  validationRunId: number | null; description: string; debitTotal: number; creditTotal: number
  lines: { lineNo: number; accountCode: string; accountName: string; debitAmount: number; creditAmount: number; memo: string }[]
}
export interface LedgerExplorerData { contracts: ExplorerPage<LedgerContract>; journals: ExplorerPage<LedgerEntry>; selectedContractId: number | null; totals: {journalCount: number; lineCount: number; debitTotal: number; creditTotal: number} }
export function explorerQuery(criteria: JournalCriteria, selection: { contractId: string; all: boolean; contractPage: number; page: number; journalId?: string }, enabled: boolean) {
  return queryOptions({
    queryKey: ['journals', 'explorer', criteria, selection], enabled,
    queryFn: ({ signal }) => {
      const query = new URLSearchParams({ page: String(selection.page), contractPage: String(selection.contractPage), size: '20', all: String(selection.all) })
      for (const key of ['from', 'to', 'type', 'account', 'contractNo', 'status'] as const) if (criteria[key]) query.set(key, criteria[key])
      if (selection.contractId !== '') query.set('contractId', selection.contractId)
      if (selection.journalId) query.set('journalId', selection.journalId)
      return get<LedgerExplorerData>(`/api/v1/journals/explorer?${query}`, signal)
    }, meta: { errorToast: false },
  })
}
