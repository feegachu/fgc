import { useId, useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router'
import { int, rate } from '../../lib/format'
import { useDrawn } from '../../lib/motion'
import { CIRCUMFERENCE, RADIUS, donutSegments } from './donut'

export type CompositionItem = {
  key: string
  label: string
  value: number
  /** 도넛 조각·범례 색. 색만으로 구분하지 않도록 범례에 이름과 건수가 함께 있다. */
  color: 'error' | 'warning' | 'review' | 'info' | 'success'
  to: string
}

/**
 * 검증 항목별 건수 비중. 도넛은 눈으로 훑는 보조 수단이고, 범례 줄이 이름·건수·비중을 갖는 접근 가능한 본체이며
 * 각 줄은 해당 목록 화면으로 이동하는 링크다. 조각이나 범례에 올리면 중앙에 항목·건수·비중을 보여 준다.
 */
export function RiskComposition({ items }: { items: CompositionItem[] }) {
  const [activeKey, setActiveKey] = useState<string | null>(null)
  const tooltipId = useId()
  const drawn = useDrawn()
  const total = items.reduce((sum, item) => sum + item.value, 0)
  const segments = new Map(donutSegments(items).map((segment) => [segment.key, segment]))
  const active = items.find((item) => item.key === activeKey)

  if (total === 0) {
    return <div className="empty-state">이번 달 표시할 위험 건이 없습니다.</div>
  }

  return (
    <div className="risk-composition" onKeyDown={(event) => {
      if (event.key === 'Escape') setActiveKey(null)
    }}>
      <div className="risk-donut">
        <svg viewBox="0 0 100 100" className="risk-donut-svg" aria-hidden="true">
          <circle className="risk-donut-track" cx="50" cy="50" r={RADIUS} />
          {items.map((item) => {
            const segment = segments.get(item.key)
            if (!segment) return null
            const state = activeKey === null ? '' : activeKey === item.key ? ' is-active' : ' is-dimmed'
            const dash = drawn ? segment.length : 0
            return (
              <circle
                key={item.key}
                className={`risk-donut-segment risk-color-${item.color}${state}`}
                cx="50"
                cy="50"
                r={RADIUS}
                strokeDasharray={`${dash} ${CIRCUMFERENCE - dash}`}
                strokeDashoffset={-segment.offset}
                onMouseEnter={() => setActiveKey(item.key)}
                onMouseLeave={() => setActiveKey(null)}
              />
            )
          })}
        </svg>
        {active ? (
          <div id={tooltipId} role="tooltip" className="risk-donut-center risk-donut-tooltip">
            <span className="risk-donut-detail-label">{active.label}</span>
            <strong className="risk-donut-detail-value tabular-nums">{int(active.value)}건</strong>
            <span>비중 {rate(String((active.value * 100) / total))}%</span>
          </div>
        ) : (
          <div className="risk-donut-center" aria-hidden="true"><span>항목별 비중</span></div>
        )}
      </div>
      <ul className="risk-legend">
        {items.map((item, index) => {
          const share = total > 0 ? item.value / total : 0
          // 화면정의서 공통 규칙 3: 소수점 첫째 자리까지 절사. (value*100)/total이라야 0.29*100 같은 곱셈 오차가 없다
          const sharePct = `${rate(total > 0 ? String((item.value * 100) / total) : '0')}%`
          return (
            <li key={item.key} style={{ '--i': index } as CSSProperties}>
              <Link data-guide-feature="features.dashboard.risk-composition.link"
                to={item.to}
                className={`risk-legend-row${activeKey === item.key ? ' is-active' : ''}`}
                aria-label={`${item.label} ${int(item.value)}건, 비중 ${sharePct}`}
                aria-describedby={activeKey === item.key ? tooltipId : undefined}
                onMouseEnter={() => setActiveKey(item.key)}
                onMouseLeave={() => setActiveKey(null)}
                onFocus={() => setActiveKey(item.key)}
                onBlur={() => setActiveKey(null)}
              >
                <span className={`risk-swatch risk-color-${item.color}`} aria-hidden="true" />
                <span className="risk-legend-label">{item.label}</span>
                <span className="risk-legend-value tabular-nums">{int(item.value)}건</span>
                <span className="risk-legend-share tabular-nums">{sharePct}</span>
                <span className="risk-legend-bar" aria-hidden="true">
                  <span
                    className={`risk-legend-bar-fill risk-color-${item.color}`}
                    style={{ transform: `scaleX(${drawn ? share : 0})` }}
                  />
                </span>
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
