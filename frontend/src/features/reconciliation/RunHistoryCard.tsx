import type { Run } from './api'
import { RUN_STATUS } from './labels'
import { StatusBadge } from '../../components/StatusBadge'
import { int, won } from '../../lib/format'

export function RunHistoryCard({ run }: { run: Run }) {
  return <span className="reco-history-run">
            <span className="reco-history-run-title"><strong>{run.insurerName ?? '전체 보험회사'}</strong><small>#{run.reconciliationRunId}</small></span>
            <span className="reco-history-run-status"><StatusBadge tone={run.status === 'COMPLETED' || run.status === 'FINALIZED' ? 'success' : run.status === 'FAILED' ? 'error' : 'neutral'}>{RUN_STATUS[run.status as keyof typeof RUN_STATUS] ?? '상태 확인 필요'}</StatusBadge><span>대상 {run.targetCount == null ? '—' : int(run.targetCount)} · 불일치 {run.exceptionCount == null ? '—' : int(run.exceptionCount)}</span></span>
            <span className="reco-history-run-difference">차액 <strong className={run.differenceTotal ? 'reco-react-difference' : undefined}>{run.differenceTotal == null ? '—' : won(run.differenceTotal)}</strong></span>
          </span>
}
