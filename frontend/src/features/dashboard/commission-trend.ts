export function previousMonth(month: string) {
  const [year, number] = month.split('-').map(Number)
  return `${number === 1 ? year - 1 : year}-${String(number === 1 ? 12 : number - 1).padStart(2, '0')}`
}

export function commissionChange(current: number | undefined, previous: number | undefined) {
  if (current == null || previous == null) return { direction: 'flat', text: '전월 비교 불가' }
  if (current === previous) return { direction: 'flat', text: '전월과 동일' }
  if (previous === 0) return { direction: 'flat', text: '전월 실적 없음' }
  const percent = Math.abs(((current - previous) / previous) * 100)
  const amount =
    percent < 0.1 ? '0.1% 미만' : `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 }).format(percent)}%`
  return {
    direction: current > previous ? 'up' : 'down',
    text: `전월 대비 ${amount} ${current > previous ? '증가' : '감소'}`,
  }
}
