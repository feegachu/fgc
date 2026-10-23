import { describe, expect, it } from 'vitest'
import { formatSort, parseSort } from './tableSort'

describe('tableSort', () => {
  it('key,방향 표기를 읽고 다시 만든다', () => {
    expect(parseSort('amount,asc')).toEqual({ key: 'amount', direction: 'asc' })
    expect(formatSort(parseSort('amount,desc'))).toBe('amount,desc')
    expect(formatSort(undefined)).toBe('')
  })
  it('형식이 틀리거나 허용 목록에 없으면 무시한다', () => {
    for (const raw of [undefined, null, '', 'amount', 'amount,up', 'amount,asc,x', ',asc']) {
      expect(parseSort(raw)).toBeUndefined()
    }
    expect(parseSort('secret,asc', ['amount'])).toBeUndefined()
    expect(parseSort('amount,asc', ['amount'])).toEqual({ key: 'amount', direction: 'asc' })
  })
})
