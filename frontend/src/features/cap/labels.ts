import type { CapStage, CapAgent } from './api'

export const STAGES = { INSURER_TO_GA: '원수사 → GA', GA_TO_FC: 'GA → 설계사' }
export const STATUSES = { NORMAL: '정상', WARNING: '주의', VIOLATION: '위반', REVIEW_REQUIRED: '검토필요' }
/** 목록 필터·배지용 — KPI 카드에는 적용대상 아님을 집계하지 않는다(#477, #482). */
export const STATUS_LABELS: Record<string, string> = { ...STATUSES, NOT_APPLICABLE: '적용대상 아님' }

/** 1차 stageStatus: 집계 모니터링 우선순위. 계약별 저장 판정을 재계산하지 않는다. */
export function monitoringStatus(row: CapStage | CapAgent) {
  if (!row.contractCount) return { code: '', label: '대상 없음' }
  if (row.violationCount) return { code: 'VIOLATION', label: `위반 ${row.violationCount.toLocaleString('ko-KR')}건` }
  if (row.warningCount) return { code: 'WARNING', label: `주의 ${row.warningCount.toLocaleString('ko-KR')}건` }
  if (row.reviewRequiredCount)
    return { code: 'REVIEW_REQUIRED', label: `검토필요 ${row.reviewRequiredCount.toLocaleString('ko-KR')}건` }
  return { code: 'NORMAL', label: '정상' }
}
