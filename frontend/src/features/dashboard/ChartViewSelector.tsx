import type { CSSProperties } from 'react'
import { guideFeatureId } from '../assistant/feature-ids'

const paths = {
  bar: 'M5 20V10h3v10m3 0V4h3v16m3 0V7h3v13',
  line: 'M3 3v18h18M5 16l5-6 4 3 6-8',
  donut: 'M12 2v10h10A10 10 0 0 0 12 2Zm-3 1a9 9 0 1 0 12 12H9V3Z',
  table: 'M4 5h16v14H4V5Zm0 5h16M9 5v14M4 15h16',
  money: 'M3 6h18v12H3V6Zm4 0v3H3m18 0h-4V6M3 15h4v3m10 0v-3h4M14 12a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z',
  people: 'M13 7a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM3 21v-3a6 6 0 0 1 12 0v3M17 4a3 3 0 0 1 0 6m1 4a5 5 0 0 1 3 4v3',
  contract: 'M6 3h8l4 4v14H6V3Zm8 0v5h4M9 12h6m-6 4h6',
  exception: 'm12 3 10 18H2L12 3Zm0 6v5m0 3h.01',
} as const

export interface ChartView {
  id: string
  label: string
  icon: keyof typeof paths
}

export function ChartViewSelector({
  views,
  selected,
  onSelect,
  label,
  controls,
}: {
  views: readonly ChartView[]
  selected: number
  onSelect: (index: number) => void
  label: string
  controls: string
}) {
  return (
    <div
      className="chart-view-selector"
      role="group"
      aria-label={label}
      style={{ '--chart-view-index': selected, '--chart-view-count': views.length } as CSSProperties}
    >
      <span className="chart-view-thumb" aria-hidden="true" />
      {views.map((view, index) => (
        <button
          key={view.id}
          data-guide-feature={guideFeatureId('dashboard.chart-view', view.id)}
          type="button"
          aria-label={view.label}
          title={view.label}
          aria-pressed={selected === index}
          aria-controls={controls}
          onClick={() => onSelect(index)}
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d={paths[view.icon]} />
          </svg>
        </button>
      ))}
    </div>
  )
}
