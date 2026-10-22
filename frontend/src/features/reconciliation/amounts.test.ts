import { describe, expect, it } from 'vitest'
import { amountText, sumAmounts } from './amounts'

describe('visible reconciliation amount totals', () => {
  it.each([
    { values: [500.49, 500.49], total: '1000', display: '1,000원' },
    { values: [0.49, 0.49], total: '0', display: '0원' },
    { values: [0.5, 0.5], total: '2', display: '2원' },
    { values: [-500.49, -500.49], total: '-1000', display: '(1,000)원' },
    { values: [-0.5, -0.5], total: '-2', display: '(2)원' },
    { values: [1.5, -0.5], total: '1', display: '1원' },
  ])('rounds each signed row HALF_UP before summing $values', ({ values, total, display }) => {
    expect(sumAmounts(values)).toBe(total)
    expect(amountText(sumAmounts(values))).toBe(display)
  })
  it('preserves exact decimal-string boundaries and large integer amounts', () => {
    expect(sumAmounts(['12000.00', '1000.00'])).toBe('13000')
    expect(sumAmounts(['9007199254740993.01', '0.99'])).toBe('9007199254740994')
    expect(sumAmounts(['0.49999999999999999', '0.49999999999999999'])).toBe('0')
    expect(sumAmounts(['0.50000000000000000', '-0.50000000000000000'])).toBe('0')
    expect(amountText('-1234.50', false)).toBe('(1,235)')
    expect(amountText('-1234.50', false, 'minus')).toBe('-1,235')
    expect(amountText('-9007199254740993.50', true, 'minus')).toBe('-9,007,199,254,740,994원')
    expect(amountText('+1234.50', true, 'minus')).toBe('1,235원')
  })
  it('distinguishes an empty page from unavailable amounts', () => {
    expect(sumAmounts([])).toBe('0')
    expect(amountText(0, false)).toBe('0')
    expect(amountText(0, false, 'minus')).toBe('0')
    expect(sumAmounts([100, undefined])).toBeNull()
    expect(sumAmounts([100, 'bad'])).toBeNull()
    expect(amountText(null)).toBe('—')
    expect(amountText(null, true, 'minus')).toBe('—')
  })
})
