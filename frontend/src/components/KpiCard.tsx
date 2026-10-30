import type { ReactNode } from 'react'
import type { StatusTone } from './StatusBadge'
export function KpiCardContent({
  label,
  value,
  unit,
  footer,
  heading = false,
  zero,
}: {
  label: string
  value: ReactNode
  unit?: string
  footer?: ReactNode
  heading?: boolean
  zero?: boolean
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
          {value}
        </strong>
        {unit && <span className="kpi-unit">{unit}</span>}
      </div>
      {footer && <div className="kpi-card-footer">{footer}</div>}
    </>
  )
}
export function KpiCard({
  label,
  value,
  unit,
  footer,
  tone = 'neutral',
  zero,
}: {
  label: string
  value: ReactNode
  unit?: string
  footer?: ReactNode
  tone?: StatusTone
  zero?: boolean
}) {
  return (
    <section className={`kpi-card kpi-card-${tone}`} aria-label={label}>
      <KpiCardContent label={label} value={value} unit={unit} footer={footer} zero={zero} heading />
    </section>
  )
}
