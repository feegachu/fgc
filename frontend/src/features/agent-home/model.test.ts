import { describe, expect, it } from 'vitest'
import type { AgentHome } from './api'
import { asOfForMonth, categoryCounts, deltaPercent, insurerSlices, kindParam, monthStats, periodParam, shiftMonth, statusCounts, trendSeries } from './model'

const home: AgentHome = {
  portfolio: {
    contractCount: 4,
    insurers: [
      { insurerId: 1, name: 'A생명', contractCount: 3, monthlyEquivalentFirstPremiumSum: 250000 },
      { insurerId: 2, name: 'B손해', contractCount: 1, monthlyEquivalentFirstPremiumSum: 100000 },
    ],
    monthly: [
      { month: '2026-06', newContractCount: 1, monthlyEquivalentFirstPremiumSum: 100000 },
      { month: '2026-07', newContractCount: 3, monthlyEquivalentFirstPremiumSum: 250000 },
    ],
    categories: [
      { code: 'HEALTH_PROTECTION', label: '건강보장', count: 2 },
      { code: 'UNKNOWN', label: '분류 미확인', count: 2 },
    ],
    statuses: [
      { code: 'ACTIVE', label: '정상', count: 1 },
      { code: 'REVIVED', label: '부활', count: 1 },
      { code: 'UNPAID', label: '미납', count: 1 },
      { code: 'UNKNOWN', label: '상태 미확인', count: 1 },
    ],
  },
}

describe('월 지표', () => {
  it('서버 월별 집계에서 기준월 값을 읽고 없는 달은 0이다', () => {
    expect(monthStats(home, '2026-07')).toEqual({ count: 3, premium: 250000 })
    expect(monthStats(home, '2020-01')).toEqual({ count: 0, premium: 0 })
    expect(monthStats(undefined, '2026-07')).toEqual({ count: 0, premium: 0 })
  })
  it('전월 값이 0이면 증감률은 0이다', () => {
    expect(deltaPercent(150000, 100000)).toBe(50)
    expect(deltaPercent(100, 0)).toBe(0)
  })
  it('증감률은 반올림하지 않고 0 쪽으로 절사하며 나누어떨어지는 값은 그대로 둔다', () => {
    expect(deltaPercent(129, 100)).toBe(29) // (29/100)*100은 28.999…라 단순 절사하면 28.9가 된다
    expect(deltaPercent(100, 300)).toBe(-66.6)
    expect(deltaPercent(200, 300)).toBe(-33.3)
  })
})

describe('차트 변환', () => {
  it('도넛 조각은 서버 보험사 순서이고 보험료를 함께 가진다', () => {
    expect(insurerSlices(home).map((s) => [s.name, s.value, s.premium, s.insurerId])).toEqual([
      ['A생명', 3, 250000, 1],
      ['B손해', 1, 100000, 2],
    ])
    expect(insurerSlices(undefined)).toEqual([])
  })
  it('월별 추이는 서버 월 순서를 그대로 쓴다', () => {
    expect(trendSeries(home).map((p) => p.month)).toEqual(['2026-06', '2026-07'])
  })
  it('보장 유형 비중은 건수 대비 비율을 첫째 자리에서 절사한다(공통규칙 3)', () => {
    expect(categoryCounts(home).map((c) => [c.name, c.count, c.pct])).toEqual([['건강보장', 2, 50], ['분류 미확인', 2, 50]])
  })
  it('상태는 4종으로 묶고 이력이 없는 계약은 상태 미확인으로 분모에 남긴다', () => {
    expect(statusCounts(home).map((s) => [s.label, s.count])).toEqual([
      ['정상', 2],
      ['미납', 1],
      ['실효', 0],
      ['해지/종료', 0],
      ['상태 미확인', 1],
    ])
    expect(statusCounts({ portfolio: { contractCount: 1, statuses: [{ code: 'ACTIVE', count: 1 }] } }).some((s) => s.key === 'UNKNOWN')).toBe(false)
  })
})

describe('기준일·요청 변환', () => {
  it('끝난 달은 월말, 진행 중인 달은 오늘이 기준일이다', () => {
    expect(asOfForMonth('2026-07', '2026-11-02')).toBe('2026-07-31')
    expect(asOfForMonth('2026-11', '2026-11-02')).toBe('2026-11-02')
    expect(asOfForMonth('2028-02', '2030-01-01')).toBe('2028-02-29')
  })
  it('화면 선택값을 API 값으로 바꾼다', () => {
    expect(periodParam(6)).toBe('6')
    expect(periodParam(12)).toBe('12')
    expect(periodParam('all')).toBe('ALL')
    expect(kindParam('all')).toBe('ALL')
    expect(kindParam('LIFE')).toBe('LIFE')
  })
})

describe('shiftMonth', () => {
  it('연도 경계를 넘긴다', () => {
    expect(shiftMonth('2026-02', -3)).toBe('2025-11')
    expect(shiftMonth('2026-11', 2)).toBe('2027-01')
  })
})
