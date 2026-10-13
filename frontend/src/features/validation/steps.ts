import type { StatusTone } from '../../components/StatusBadge'

/**
 * 운영정책서 제43조 10단계 이름 — DB 가 아니라 화면 상수다(화면정의서 :1400~1409).
 * 9·10단계는 배치가 아니라 사람이 수행한다.
 */
export const STEP_NAMES = [
  '실행 생성',
  '대상 선별',
  '스케줄 생성·재검증',
  '1,200% 검증',
  '차익거래 검증',
  '원장 기표·균형',
  '양방향 대사',
  '예외 생성',
  '담당자 검토',
  '확정',
] as const
export const MANUAL_STEP_FROM = 9

export type StepState = 'failed' | 'done' | 'running' | 'pending'

/**
 * 스텝 칸 상태 — 1차 ValidationRunViewController.stepClass()·vrun-detail.js 와 같은 규칙이다.
 * current_step 은 "마지막으로 끝난 단계"라서 RUNNING 중이면 그 다음 칸이 진행 중이다.
 * FINALIZED 는 DB 제약이 current_step=10 을 강제하므로 10칸 전부 완료다.
 */
export function stepState(stepNo: number, status: string | undefined, currentStep: number): StepState {
  if (status === 'FAILED' && stepNo === currentStep) return 'failed'
  if (status === 'FINALIZED') return 'done'
  if ((status === 'RUNNING' || status === 'COMPLETED' || status === 'FAILED') && stepNo <= currentStep) return 'done'
  if (status === 'RUNNING' && stepNo === currentStep + 1 && stepNo <= 8) return 'running'
  return 'pending'
}

/** 색만으로 상태를 전달하지 않도록 칸마다 읽어 줄 텍스트. */
export const STEP_STATE_TEXT: Record<StepState, string> = {
  failed: '실패',
  done: '완료',
  running: '진행 중',
  pending: '대기',
}

/**
 * 상태 배지 톤 — 1차 vrun/status-badge.html 의 단일 매핑이다.
 * CREATED 와 RUNNING 을 같은 색으로 두면 "아직 시작 안 함"과 "돌고 있음"을 구분할 수 없어 나눈다.
 */
export function runStatusTone(status?: string): StatusTone {
  switch (status) {
    case 'FAILED':
      return 'error'
    case 'FINALIZED':
      return 'review'
    case 'COMPLETED':
      return 'success'
    case 'RUNNING':
      return 'info'
    default:
      return 'neutral'
  }
}

/** 진행률 문구 — 화면정의서 :1442 current_step/10 × 100. */
export const progressText = (currentStep?: number | null) => {
  const step = currentStep ?? 0
  return `${step}/10 단계 (${step * 10}%)`
}
