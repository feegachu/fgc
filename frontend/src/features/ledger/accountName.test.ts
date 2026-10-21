import { describe, expect, it } from 'vitest'
import { accountDisplayName } from './accountName'

describe('accountDisplayName', () => {
  it('서버가 준 한글 계정명만 보여 주고 코드는 붙이지 않는다', () => {
    expect(accountDisplayName('EXPECTED_RECEIVABLE', '과거 미수금')).toBe('과거 미수금')
  })
  it('이름이 비었거나 공백뿐이면 알려진 코드의 한글 매핑을 쓴다', () => {
    expect(accountDisplayName('EXPECTED_RECEIVABLE', '')).toBe('예상 수수료미수금')
    expect(accountDisplayName('CONFIRMED_PAYOUT_PAYABLE', '   ')).toBe('확정 지급채무')
    expect(accountDisplayName('ACTUAL_INCOME', null)).toBe('실제 수수료수익 대응')
  })
  it('이름도 매핑도 없으면 영어 코드를 노출하지 않고 한글 안내를 쓴다', () => {
    expect(accountDisplayName('LEGACY_UNKNOWN', undefined)).toBe('계정명 확인 필요')
    expect(accountDisplayName('LEGACY_UNKNOWN', ' ')).not.toContain('LEGACY_UNKNOWN')
  })
  it('코드도 없으면 -', () => {
    expect(accountDisplayName(undefined, undefined)).toBe('-')
    expect(accountDisplayName('', '')).toBe('-')
  })
})
