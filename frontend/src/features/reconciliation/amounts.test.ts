import { describe, expect, it } from 'vitest'
import { amountText, sumAmounts } from './amounts'

describe('visible reconciliation amount totals', () => {
  it('adds decimal and signed amounts exactly before display rounding', () => {
    expect(sumAmounts([0.1, 0.2, -0.05])).toBe('0.25')
    expect(sumAmounts(['12000.00', '1000.00'])).toBe('13000')
    expect(sumAmounts(['9007199254740993.01', '0.99'])).toBe('9007199254740994')
    expect(amountText(sumAmounts([0.49, 0.49]))).toBe('1원')
    expect(amountText('-1234.50', false)).toBe('(1,235)')
  })
  it('distinguishes an empty page from unavailable amounts', () => {
    expect(sumAmounts([])).toBe('0')
    expect(amountText(0, false)).toBe('0')
    expect(sumAmounts([100, undefined])).toBeNull()
    expect(sumAmounts([100, 'bad'])).toBeNull()
    expect(amountText(null)).toBe('—')
  })
})
