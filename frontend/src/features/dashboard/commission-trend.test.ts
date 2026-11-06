import { describe, expect, it } from 'vitest'
import { commissionChange, previousMonth } from './commission-trend'

describe('monthly commission comparisons', () => {
  it('compares with the previous year in January', () => {
    expect(previousMonth('2026-01')).toBe('2025-12')
  })
  it('calculates increases and decreases against the previous amount', () => {
    expect(commissionChange(125, 100)).toEqual({ direction: 'up', text: '전월 대비 25% 증가' })
    expect(commissionChange(50, 100)).toEqual({ direction: 'down', text: '전월 대비 50% 감소' })
    expect(commissionChange(0, 100)).toEqual({ direction: 'down', text: '전월 대비 100% 감소' })
  })
  it('does not invent a percentage for zero or missing prior values', () => {
    expect(commissionChange(100, 0).text).toBe('전월 실적 없음')
    expect(commissionChange(0, 0).text).toBe('전월과 동일')
    expect(commissionChange(100, undefined).text).toBe('전월 비교 불가')
  })
})
