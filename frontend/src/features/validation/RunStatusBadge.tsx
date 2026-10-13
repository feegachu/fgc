import { StatusBadge } from '../../components/StatusBadge'
import { runStatusTone } from './steps'

/** FINALIZED 만 라벨을 "확정"으로 줄이고 자물쇠 아이콘을 붙인다(1차 status-badge.html 과 같다). */
export function RunStatusBadge({ status, label }: { status?: string; label?: string }) {
  return (
    <StatusBadge tone={runStatusTone(status)}>
      <span>{status === 'FINALIZED' ? '확정' : (label ?? '-')}</span>
      {status === 'FINALIZED' && (
        <span className="material-symbols-rounded vrun-badge-lock" aria-hidden="true">
          lock
        </span>
      )}
    </StatusBadge>
  )
}
