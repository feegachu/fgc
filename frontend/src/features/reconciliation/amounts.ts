export type Amount = number | string | null | undefined

function decimal(value: Amount) {
  if (value == null || (typeof value === 'number' && !Number.isFinite(value))) return null
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(String(value).trim())
  if (!match) return null
  const fraction = match[3] ?? ''
  return { coefficient: BigInt(`${match[1] === '-' ? '-' : ''}${match[2]}${fraction}`), scale: fraction.length }
}

/** Add the visible server page without binary floating-point arithmetic or per-row rounding. */
export function sumAmounts(values: Amount[]): string | null {
  let coefficient = 0n
  let scale = 0
  for (const value of values) {
    const part = decimal(value)
    if (!part) return null
    const nextScale = Math.max(scale, part.scale)
    coefficient =
      coefficient * 10n ** BigInt(nextScale - scale) + part.coefficient * 10n ** BigInt(nextScale - part.scale)
    scale = nextScale
  }
  const sign = coefficient < 0n ? '-' : ''
  const digits = (coefficient < 0n ? -coefficient : coefficient).toString().padStart(scale + 1, '0')
  return (
    sign + (scale ? `${digits.slice(0, -scale)}.${digits.slice(-scale)}`.replace(/0+$/, '').replace(/\.$/, '') : digits)
  )
}

/** Keep the existing whole-won display rule, rounding only at the final display boundary. */
export function amountText(value: Amount, unit = true) {
  const part = decimal(value)
  if (!part) return '—'
  const negative = part.coefficient < 0n
  const absolute = negative ? -part.coefficient : part.coefficient
  const divisor = 10n ** BigInt(part.scale)
  const rounded = (absolute + divisor / 2n) / divisor
  const digits = rounded.toLocaleString('ko-KR')
  return `${negative ? `(${digits})` : digits}${unit ? '원' : ''}`
}
