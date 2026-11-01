import { describe, expect, it } from 'vitest'
import { effectiveFilters, filterParams } from './list-api'

const apiQuery = (search: string, globalMonth: string | null) =>
  filterParams(effectiveFilters(new URLSearchParams(search), globalMonth)).toString()

describe('#752 지급 목록 적용 필터', () => {
  it('정산월 키가 없으면 기준 정산월로 조회한다', () => {
    expect(apiQuery('month=2026-10', '2026-10')).toBe('settlementMonth=2026-10&page=1&size=20')
  })
  it('명시한 정산월은 기준 정산월과 달라도 유지한다', () => {
    expect(apiQuery('month=2026-10&settlementMonth=2026-07', '2026-10')).toBe(
      'settlementMonth=2026-07&page=1&size=20',
    )
  })
  it('비운 정산월은 전체월 조회로 보존한다', () => {
    expect(apiQuery('month=2026-10&settlementMonth=', '2026-10')).toBe('page=1&size=20')
  })
})
