import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { contractStatusLabels, dataOriginLabels, responseLabel } from './labels'

const contractTones: Record<string, StatusTone> = {
  APPLIED: 'info',
  ACTIVE: 'success',
  UNPAID: 'warning',
  LAPSED: 'error',
  REVIVED: 'info',
  CANCELLED: 'neutral',
  TERMINATED: 'neutral',
  MATURED: 'neutral',
}

export function ContractStatusBadge({ code, label }: { code?: string; label?: string }) {
  return (
    <StatusBadge tone={contractTones[code ?? ''] ?? 'neutral'}>
      {responseLabel(contractStatusLabels, code, label)}
    </StatusBadge>
  )
}

export function DataOriginBadge({ code, label }: { code?: string; label?: string }) {
  return <StatusBadge>{responseLabel(dataOriginLabels, code, label)}</StatusBadge>
}
