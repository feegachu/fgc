import type { StatusTone } from '../../components/StatusBadge'

/*
 * SCHE-W01·W02 라벨 · 상태 톤 — 1차 static/js/features/schedule/schedule-labels.js 를 옮겼다.
 * 인터페이스정의서 §2-4 "한글 라벨은 서버가 만든다"에 따라 응답에 *Label 필드가 있으면 그것을 쓴다.
 * 이 표는 (1) 필터 select 선택지, (2) 서버 라벨이 없는 회차 상태(lineStatus)·계산방식(calculationType),
 * (3) 서버 라벨이 비었을 때의 대체값에만 쓴다. 코드값은 서버 enum 과 1:1 이다
 * (PaymentStage · ScheduleRegime · SchedulePurpose · ScheduleHeaderStatus · ScheduleLineStatus · CalculationType).
 */
export const EMPTY = '-'

export const PAYMENT_STAGE = { INSURER_TO_GA: '원수사→GA', GA_TO_FC: 'GA→설계사' } as const

export const SCHEDULE_REGIME = {
  CURRENT: '현행',
  FOUR_YEAR_2027: '4년 분급(2027)',
  SEVEN_YEAR_2029: '7년 분급(2029)',
  TM_SPECIAL: 'TM 특례',
} as const

export const SCHEDULE_PURPOSE = { OPERATIONAL: '운영', COMPARISON: '비교', SIMULATION: '시뮬레이션' } as const

export const SCHEDULE_STATUS = {
  PLANNED: '예정',
  CONFIRMED: '확정',
  MATCHED: '대사일치',
  ADJUSTED: '조정',
  HOLD: '보류',
  CANCELLED: '취소',
  RESTARTED: '재개',
} as const

/** 회차 상태는 헤더 상태와 같은 코드계다(ScheduleLineStatus). 응답에 라벨 필드가 없어 이 표로 표시한다. */
export const LINE_STATUS: Record<string, string> = SCHEDULE_STATUS

/** 계산방식도 응답에 라벨 필드가 없다(ScheduleLineResponse.calculationType). */
export const CALCULATION_TYPE: Record<string, string> = { RATE: '요율', FIXED: '정액' }

/*
 * 상태 배지 톤 — 화면정의서 4장 규칙 4. 근거와 선택 이유는 1차 schedule-labels.js 주석 그대로다.
 * CONFIRMED 는 VRUN-W01 의 확정과 같은 review + 자물쇠(규칙 8 확정 후 잠금)로 표시한다.
 */
const STATUS_TONES: Record<string, StatusTone> = {
  PLANNED: 'info',
  CONFIRMED: 'review',
  MATCHED: 'success',
  ADJUSTED: 'warning',
  HOLD: 'review',
  CANCELLED: 'neutral',
  RESTARTED: 'info',
}

/** 적용 체계·용도는 상태가 아니라 분류값이라 규칙 4 의 상태 5색을 쓰지 않는다. */
export const CLASSIFICATION_TONE: StatusTone = 'neutral'

export const statusTone = (code?: string | null): StatusTone => (code && STATUS_TONES[code]) || CLASSIFICATION_TONE
export const isLocked = (code?: string | null) => code === 'CONFIRMED'

export const REGIME_EVIDENCE = {
  title: 'REG-19 · 적용 체계 판정',
  body: '적용 체계는 계약 체결연도만으로 정하지 않습니다. 상품 판매개시일 · 기초서류 버전 · 판매채널을 함께 보고 정합니다.',
}

/** 서버 라벨 → 코드표 → 코드값 → '-' 순서. 1차 responseLabel 과 같은 결과다. */
export function responseLabel(
  serverLabel: string | null | undefined,
  labels: Record<string, string>,
  code?: string | null,
) {
  if (typeof serverLabel === 'string' && serverLabel.trim()) return serverLabel
  return (code && labels[code]) || code || EMPTY
}
