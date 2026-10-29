import { describe, expect, it } from 'vitest'
import type { CompareItem } from './api'
import { capBadge, followUpAnswer, heroWhy } from './format'

const item = (rank: number, patch: Partial<CompareItem> = {}): CompareItem => ({
  rank,
  offeringId: rank,
  insurer: `${rank}번보험`,
  product: `상품${rank}`,
  expectedReceipt: 1_100_000,
  cappedReceipt: 1_050_000,
  carryover: 0,
  clawbackExposure: 50_000,
  conservativeNet: 1_000_000,
  clawbackRatePct: 4.8,
  capImpactPct: 60,
  associationGrade: null,
  evidence: [],
  ...patch,
})

describe('1위 설명은 서버가 순위를 가른 기준과 일치한다', () => {
  it('순수령액이 더 크면 차이를 설명하고 가장 높다고 한다', () => {
    const items = [item(1, { conservativeNet: 1_200_000 }), item(2)]

    expect(heroWhy(items)).toContain('2위보다 순수령액이 200,000원 많아요')
    expect(followUpAnswer('why', items)).toContain('가장 높아요')
  })

  it('순수령액이 같고 환수 노출액이 더 작으면 그 기준으로 1위라고 한다', () => {
    const items = [item(1, { clawbackExposure: 50_000 }), item(2, { clawbackExposure: 80_000 })]

    expect(heroWhy(items)).toBe('2위와 순수령액은 같고, 환수 노출액이 더 작아서 1위예요.')
    const answer = followUpAnswer('why', items)
    expect(answer).not.toContain('가장 높아요')
    expect(answer).not.toContain('0원 많아요')
    expect(answer).toContain('2위와 순수령액은 같고')
  })

  it('순수령액·환수 노출액이 모두 같으면 표시 순서 기준이라고 밝힌다', () => {
    const items = [item(1), item(2)]

    expect(heroWhy(items)).toContain('표시 순서(상품 ID) 기준')
    expect(followUpAnswer('why', items)).not.toContain('가장 높아요')
  })
})

describe('capBadge', () => {
  it('비율은 소수 첫째 자리 절사라 99.96% 가 100.0% 로 보이지 않는다', () => {
    expect(capBadge(99.96).text).toBe('99.9% 근접')
    expect(capBadge(100.04).text).toBe('100.0% 초과·이월')
    expect(capBadge(89.99).text).toBe('89.9% 여유')
  })
})
