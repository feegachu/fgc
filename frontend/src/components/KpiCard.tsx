import type { ReactNode } from 'react'
import { AnimatedNumber } from './AnimatedNumber'
import type { StatusTone } from './StatusBadge'
export function KpiCardContent({
  label,
  value,
  unit,
  footer,
  heading = false,
  zero,
  format,
}: {
  label: string
  value: ReactNode
  unit?: string
  footer?: ReactNode
  heading?: boolean
  zero?: boolean
  format?: (value: number) => string
}) {
  return (
    <>
      <div className="kpi-card-header">
        {heading ? <h2 className="kpi-label">{label}</h2> : <span className="kpi-label">{label}</span>}
      </div>
      <div className="kpi-value-row">
        <strong
          className="kpi-value tabular-nums"
          data-zero={zero ?? (value === 0 || (typeof value === 'string' && /^0(?:\.0+)?$/.test(value)))}
        >
          {typeof value === 'number' ? <AnimatedNumber value={value} format={format} /> : value}
        </strong>
        {unit && <span className="kpi-unit">{unit}</span>}
      </div>
      {footer && <div className="kpi-card-footer">{footer}</div>}
    </>
  )
}
export function KpiCard({
  guideId,
  label,
  value,
  unit,
  footer,
  tone = 'neutral',
  zero,
  format,
}: {
  guideId: string | undefined
  label: string
  value: ReactNode
  unit?: string
  footer?: ReactNode
  tone?: StatusTone
  zero?: boolean
  format?: (value: number) => string
}) {
  return (
    <section data-guide-feature={guideId} className={`kpi-card kpi-card-${tone}`} aria-label={label}>
      <KpiCardContent label={label} value={value} unit={unit} footer={footer} zero={zero} format={format} heading />
    </section>
  )
}
