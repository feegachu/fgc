import { describe, expect, it } from 'vitest'
import { paymentEntryHref, readPaymentEntry, returnLink, safeReturnTo } from './payment-entry'

describe('SIR-009 지급 맥락 URL', () => {
  it('계약·수령인·항목·귀속일·귀속월과 이전 목록을 보존한다', () => {
    const entry = {
      contractId: '600',
      agentId: '601',
      commissionItemId: '25',
      attributionDate: '2026-07-31',
      amount: '250000',
      paymentStage: 'GA_TO_FC' as const,
    }
    const back = '/contracts?contractNo=ABC&page=3&selectedId=600'
    const url = new URL(paymentEntryHref(entry, back, '2026-07'), 'http://local')
    expect(readPaymentEntry(url.searchParams)).toEqual(entry)
    expect(url.searchParams.get('month')).toBe('2026-07')
    expect(url.searchParams.get('returnTo')).toBe(back)
  })
  it('잘못된 ID·날짜·금액과 외부 복귀 경로를 채우지 않는다', () => {
    expect(
      readPaymentEntry(
        new URLSearchParams(
          'contractId=-1&agentId=Infinity&commissionItemId=9007199254740992&attributionDate=2026-02-30&amount=NaN',
        ),
      ),
    ).toEqual({ paymentStage: 'GA_TO_FC' })
    for (const path of ['https://evil.test', '//evil.test', '/login', '/contracts-other'])
      expect(safeReturnTo(path, '/transactions')).toBe('/transactions')
  })
  it('상세·수정 왕복 시 필터·페이지와 선택 행이 유지된다', () => {
    const detail = new URL(
      returnLink('/contracts/600?tab=basic', '/contracts?agentId=601&page=3', '600'),
      'http://local',
    )
    expect(detail.searchParams.get('tab')).toBe('basic')
    expect(detail.searchParams.get('returnTo')).toBe('/contracts?agentId=601&page=3&selectedId=600')
  })
})
