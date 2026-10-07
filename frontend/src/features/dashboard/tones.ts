import type { StatusTone } from '../../components/StatusBadge'

// 색 분류는 templates/dashboard/index.html 의 th:classappend 규칙을 그대로 옮긴 것이다.
export function severityTone(severity?: string): StatusTone {
  if (severity === 'WARNING') return 'warning'
  if (severity === 'HIGH' || severity === 'CRITICAL') return 'error'
  return 'neutral'
}

const warningTypes = ['CAP_WARNING', 'ARBITRAGE_CANDIDATE', 'ALLOCATION_EVIDENCE_MISSING']
const errorTypes = [
  'CAP_VIOLATION',
  'RECONCILIATION_MISMATCH',
  'JOURNAL_IMBALANCE',
  'POLICY_MISSING',
  'POLICY_DUPLICATE',
]
const reviewTypes = ['CAP_REVIEW_REQUIRED', 'REFUND_TABLE_MISSING', 'PRODUCT_CODE_MISMATCH', 'DATA_QUALITY']

export function exceptionTypeTone(type = ''): StatusTone {
  if (warningTypes.includes(type)) return 'warning'
  if (errorTypes.includes(type)) return 'error'
  if (reviewTypes.includes(type)) return 'review'
  return 'neutral'
}

export function exceptionStatusTone(status?: string): StatusTone {
  if (status === 'NEW') return 'error'
  if (status === 'IN_REVIEW') return 'info'
  if (status === 'RESOLVED') return 'success'
  return 'review'
}

export function runStatusTone(status?: string): StatusTone {
  if (status === 'FAILED') return 'error'
  if (status === 'FINALIZED' || status === 'COMPLETED') return 'success'
  if (status === 'RUNNING') return 'info'
  return 'neutral'
}
