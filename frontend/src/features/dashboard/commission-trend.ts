import { rate } from '../../lib/format'

/** 선택 월 직전의 달을 연도 경계까지 포함해 반환한다. */
export function previousMonth(month: string) {
  const [year, number] = month.split('-').map(Number)
  return `${number === 1 ? year - 1 : year}-${String(number === 1 ? 12 : number - 1).padStart(2, '0')}`
}

/** 확정 금액의 전월 증감 표시. 비교 불가와 0원 기준을 구분하고 백분율은 소수 첫째 자리까지 절사한다. */
export function commissionChange(current: number | undefined, previous: number | undefined) {
  if (current == null || previous == null) return { direction: 'flat', text: '전월 비교 불가' }
  if (current === previous) return { direction: 'flat', text: '전월과 동일' }
  if (previous === 0) return { direction: 'flat', text: '전월 실적 없음' }
  // API 금액은 소수 둘째 자리까지이므로 최소 금액 단위로 빼서 0.1% 경계의 오차를 피한다.
  const previousCents = Math.round(previous * 100)
  const differenceCents = Math.round(current * 100) - previousCents
  const percent = (Math.abs(differenceCents) * 100) / Math.abs(previousCents)
  const amount = percent < 0.1 ? '0.1% 미만' : `${rate(percent)}%`
  return {
    direction: current > previous ? 'up' : 'down',
    text: `전월 대비 ${amount} ${current > previous ? '증가' : '감소'}`,
  }
}
