export type Amount = number | string | null | undefined

function decimal(value: Amount) {
  if (value == null || (typeof value === 'number' && !Number.isFinite(value))) return null
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(String(value).trim())
  if (!match) return null
  const fraction = match[3] ?? ''
  return { coefficient: BigInt(`${match[1] === '-' ? '-' : ''}${match[2]}${fraction}`), scale: fraction.length }
}

/** Operating policy Article 17-2: HALF_UP each detail amount to whole won before aggregation. */
function wholeWon(part: NonNullable<ReturnType<typeof decimal>>) {
  const absolute = part.coefficient < 0n ? -part.coefficient : part.coefficient
  const divisor = 10n ** BigInt(part.scale)
  const rounded = (absolute + divisor / 2n) / divisor
  return part.coefficient < 0n ? -rounded : rounded
}

/** Sum the displayed whole-won rows exactly, including negative correction amounts. */
export function sumAmounts(values: Amount[]): string | null {
  let total = 0n
  for (const value of values) {
    const part = decimal(value)
    if (!part) return null
    total += wholeWon(part)
  }
  return total.toString()
}

/** Use the same per-detail HALF_UP rule for table cells and page totals. */
export function amountText(value: Amount, unit = true, negativeStyle: 'parentheses' | 'minus' = 'parentheses') {
  const part = decimal(value)
  if (!part) return '—'
  const negative = part.coefficient < 0n
  const rounded = wholeWon(part)
  const digits = (rounded < 0n ? -rounded : rounded).toLocaleString('ko-KR')
  const signed = negative ? (negativeStyle === 'minus' ? `-${digits}` : `(${digits})`) : digits
  return `${signed}${unit ? '원' : ''}`
}
