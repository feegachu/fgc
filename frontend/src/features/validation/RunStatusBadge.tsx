import { StatusBadge } from '../../components/StatusBadge'
import { runStatusTone } from './steps'

/** FINALIZED 는 화면정의서 4-2 표기 "확정(잠김)"으로 맞추고 자물쇠 아이콘을 붙인다. */
export function RunStatusBadge({ status, label }: { status?: string; label?: string }) {
  return (
    <StatusBadge tablePresentation="dot" optionCount={5} tone={runStatusTone(status)}>
      <span>{status === 'FINALIZED' ? '확정(잠김)' : (label ?? '-')}</span>
      {status === 'FINALIZED' && (
        <span className="material-symbols-rounded vrun-badge-lock" aria-hidden="true">
          lock
        </span>
      )}
    </StatusBadge>
  )
}
