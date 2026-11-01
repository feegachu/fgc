import { describe, expect, it } from 'vitest'
import { aggregate, buildRows, categoryCounts, deltaPercent, insurerSlices, monthStats, periodMonths, shiftMonth, statusCounts } from './model'
import type { AgentContract, Insurer, Product } from './model'

const insurers: Insurer[] = [
  { insurerId: 1, insurerName: 'A생명', insurerType: 'LIFE' },
  { insurerId: 2, insurerName: 'B손해', insurerType: 'NON_LIFE' },
]
const products: Product[] = [
  { productName: '건강 A', productGroupCode: 'HEALTH_PROTECTION' },
  { productName: '장기 B', productGroupCode: 'LONG_TERM_NONLIFE' },
]
const contract = (patch: Partial<AgentContract>): AgentContract => ({
  contractId: 1,
  contractNo: 'C-1',
  contractDate: '2026-07-10',
  contractStatus: 'ACTIVE',
  capResultStatus: 'NORMAL',
  insurerName: 'A생명',
  productName: '건강 A',
  monthlyEquivalentFirstPremium: 100000,
  ...patch,
})
const rows = buildRows(
  [
    contract({}),
    contract({ contractId: 2, contractNo: 'C-2', contractDate: '2026-07-20', monthlyEquivalentFirstPremium: 50000, capResultStatus: 'WARNING' }),
    contract({ contractId: 3, contractNo: 'C-3', contractDate: '2026-06-01', insurerName: 'B손해', productName: '장기 B', contractStatus: 'UNPAID' }),
    contract({ contractId: 4, contractNo: 'C-4', contractDate: '2025-01-01', capResultStatus: 'REVIEW_REQUIRED', contractStatus: 'TERMINATED' }),
  ],
  insurers,
  products,
)

describe('buildRows', () => {
  it('보험사 구분과 보장 유형을 기준정보로 채우고 모르면 기타로 둔다', () => {
    expect(rows.map((r) => [r.insurerKind, r.category])).toEqual([
      ['LIFE', '건강보장'],
      ['LIFE', '건강보장'],
      ['NON_LIFE', '장기손해'],
      ['LIFE', '건강보장'],
    ])
    expect(buildRows([contract({ productName: '없는 상품' })], insurers, products)[0].category).toBe('기타')
  })
})

describe('월 지표', () => {
  it('기준월 신규 계약 건수·보험료와 한도 근접·검토 필요를 센다', () => {
    expect(monthStats(rows, '2026-07')).toEqual({ count: 2, premium: 150000, near: 1, review: 0 })
    expect(monthStats(rows, '2025-01')).toEqual({ count: 1, premium: 100000, near: 0, review: 1 })
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

describe('차트 집계', () => {
  it('기간은 기준월 포함 n개월이고 전체는 첫 계약월부터다', () => {
    expect(periodMonths(rows, '2026-07', 6)).toEqual(['2026-02', '2026-03', '2026-04', '2026-05', '2026-06', '2026-07'])
    expect(periodMonths(rows, '2026-07', 'all')).toHaveLength(19)
  })
  it('보험사 구분과 보험사 선택으로 좁힌다', () => {
    expect(aggregate(rows, '2026-07', 12, 'all', null).base).toHaveLength(3)
    expect(aggregate(rows, '2026-07', 12, 'NON_LIFE', null).base.map((r) => r.id)).toEqual(['C-3'])
    expect(aggregate(rows, '2026-07', 12, 'all', 'A생명').focus).toHaveLength(2)
  })
  it('도넛 조각은 보험사 기준정보 순서이고 0건도 남긴다', () => {
    const { base } = aggregate(rows, '2026-07', 12, 'all', null)
    expect(insurerSlices(base, insurers, 'all').map((s) => [s.name, s.value])).toEqual([['A생명', 2], ['B손해', 1]])
  })
  it('보장 유형·상태 비중은 건수 대비 비율을 첫째 자리에서 절사한다(공통규칙 3)', () => {
    const { focus } = aggregate(rows, '2026-07', 12, 'all', null)
    expect(categoryCounts(focus).find((c) => c.name === '건강보장')).toMatchObject({ count: 2, pct: 66.6 })
    expect(statusCounts(focus).map((s) => [s.label, s.count])).toEqual([['정상', 2], ['미납', 1], ['실효', 0], ['해지·종료', 0]])
  })
})

describe('shiftMonth', () => {
  it('연도 경계를 넘긴다', () => {
    expect(shiftMonth('2026-02', -3)).toBe('2025-11')
    expect(shiftMonth('2026-11', 2)).toBe('2027-01')
  })
})
