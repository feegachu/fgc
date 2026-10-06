import type { ReactNode } from 'react'
import type { StatusTone } from './StatusBadge'
export function KpiCard({
  label,
  value,
  unit,
  footer,
  tone = 'neutral',
}: {
  label: string
  value: ReactNode
  unit?: string
  footer?: ReactNode
  tone?: StatusTone
}) {
  return (
    <section className={`kpi-card kpi-card-${tone}`} aria-label={label}>
      <div className="kpi-card-header">
        <h2 className="kpi-label">{label}</h2>
      </div>
      <div className="kpi-value-row">
        <strong className="kpi-value tabular-nums">{value}</strong>
        {unit && <span className="kpi-unit">{unit}</span>}
      </div>
      {footer && <div className="kpi-card-footer">{footer}</div>}
    </section>
  )
}
