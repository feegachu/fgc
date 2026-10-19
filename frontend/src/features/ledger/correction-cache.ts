import type { QueryClient } from '@tanstack/react-query'

/** 원장 정정은 실제 지급·대사·예외 상태도 함께 바꾼다. */
export async function invalidateJournalCorrectionQueries(client: QueryClient) {
  await Promise.all(
    ['journals', 'transactions', 'reconciliations', 'exceptions', 'validation-runs', 'dashboard', 'cap-checks', 'audit-logs']
      .map((key) => client.invalidateQueries({ queryKey: [key] })),
  )
}
