import { StatusBadge } from '../../components/StatusBadge'
import { EvidenceLink } from '../../components/EvidenceLink'
import { CLASSIFICATION_TONE, isLocked, REGIME_EVIDENCE, statusTone } from './labels'

/** 상태 배지. 확정은 색에 더해 자물쇠로도 알린다(규칙 8, VRUN-W01 과 같은 표현). */
export function ScheduleStatusBadge({ code, label }: { code?: string | null; label: string }) {
  return (
    <StatusBadge tone={statusTone(code)}>
      {label}
      {isLocked(code) && (
        <span className="material-symbols-rounded schedule-badge-lock" aria-hidden="true">
          lock
        </span>
      )}
    </StatusBadge>
  )
}

export const ClassificationBadge = ({ label }: { label: string }) => (
  <StatusBadge tone={CLASSIFICATION_TONE}>{label}</StatusBadge>
)

export const ActiveBadge = ({ active }: { active?: boolean }) => (
  <StatusBadge tone={active === true ? 'success' : 'neutral'}>{active === true ? '사용중' : '미사용'}</StatusBadge>
)

/** 적용 체계 + 근거 아이콘(REG-19, 화면정의서 4장 규칙 5). 1차 표 머리글·헤더 정의목록과 같은 마크업이다. */
export const RegimeEvidence = () => (
  <>
    적용 체계{' '}
    <EvidenceLink icon label="적용 체계 판정 근거">
      <strong>{REGIME_EVIDENCE.title}</strong> <span>{REGIME_EVIDENCE.body}</span>
    </EvidenceLink>
  </>
)
