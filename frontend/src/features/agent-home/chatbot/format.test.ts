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
  capImpactPct: 60,
  associationGrade: null,
  evidence: [],
  ...patch,
})

describe('1위 설명은 서버가 순위를 가른 기준과 일치한다', () => {
  it('순수령액이 더 크면 차이를 설명하고 가장 높다고 한다', () => {
    const items = [item(1, { conservativeNet: 1_200_000 }), item(2)]

    expect(heroWhy(items)).toContain('2위보다 순수령액이 200,000원 많아요')
    expect(followUpAnswer('why', items, 12)).toContain('가장 높아요')
  })

  it('순수령액이 같고 환수 노출액이 더 작으면 그 기준으로 1위라고 한다', () => {
    const items = [item(1, { clawbackExposure: 50_000 }), item(2, { clawbackExposure: 80_000 })]

    expect(heroWhy(items)).toBe('2위와 순수령액은 같고, 예상 환수액이 더 적어서 1위예요.')
    const answer = followUpAnswer('why', items, 12)
    expect(answer).not.toContain('가장 높아요')
    expect(answer).not.toContain('0원 많아요')
    expect(answer).toContain('2위와 순수령액은 같고')
  })

  it('순수령액·환수 노출액이 모두 같으면 상품 등록 순서 기준이라고 밝힌다', () => {
    const items = [item(1), item(2)]

    expect(heroWhy(items)).toContain('상품 등록 순서로 1위')
    expect(followUpAnswer('why', items, 12)).not.toContain('가장 높아요')
  })

  it('순수령액이 더 커도 환수액이 같으면 더 적다고 하지 않는다', () => {
    const items = [item(1, { conservativeNet: 1_200_000 }), item(2)]

    expect(heroWhy(items)).toContain('예상 환수액은 같아요.')
    expect(heroWhy(items)).not.toContain('예상 환수액도 더 적어요.')
  })
})

describe('capBadge', () => {
  it('비율은 소수 첫째 자리 절사라 99.96% 가 100.0% 로 보이지 않는다', () => {
    expect(capBadge(99.96).text).toBe('99.9% 사용 · 근접')
    expect(capBadge(100.04).text).toBe('100.0% 사용 · 근접')
    expect(capBadge(100.04, 1).text).toBe('100.0% 사용 · 초과/이월')
    expect(capBadge(99.96, 0).text).toBe('99.9% 사용 · 근접')
    expect(capBadge(89.99).text).toBe('89.9% 사용 · 여유')
  })

  it('한도와 정확히 같아 이월이 0원이면 초과가 아니라 근접이다', () => {
    expect(capBadge(100, 0)).toEqual({ tone: 'warning', text: '100.0% 사용 · 근접' })
    expect(capBadge(110.833333, 130_000)).toEqual({ tone: 'error', text: '110.8% 사용 · 초과/이월' })
  })
})

describe('한도·환수 연관 질문', () => {
  it('환수 노출액은 환수 규칙으로 계산했다고 설명하고 해약환급률을 환수율로 쓰지 않는다', () => {
    const answer = followUpAnswer('limit', [item(1)], 12)

    expect(answer).toContain('환수 규칙의 항목별 환수율')
    expect(answer).not.toContain('해약환급률')
  })

  it('해지 시점은 고정 문구 대신 서버가 준 계산 가정을 사용한다', () => {
    for (const kind of ['why', 'limit'] as const) {
      expect(followUpAnswer(kind, [item(1)], 18)).toContain('18개월 차에 해지한다고 가정')
      expect(followUpAnswer(kind, [item(1)], 18)).not.toContain('12개월')
    }
  })
})
