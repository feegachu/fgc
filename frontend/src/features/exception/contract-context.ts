import { useQuery } from '@tanstack/react-query'
import { contractDetailQuery } from '../contract/api'
import { contractStatusLabels } from '../contract/labels'

export function useExceptionContract(contractId?: number) {
  return useQuery({ ...contractDetailQuery(contractId ? String(contractId) : ''), staleTime: 30_000 })
}

export function contractStateLabel(status?: string, label?: string) {
  return label || contractStatusLabels[status as keyof typeof contractStatusLabels] || '확인 필요'
}
