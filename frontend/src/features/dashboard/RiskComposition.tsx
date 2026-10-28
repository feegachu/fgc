import { useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router'
import { int } from '../../lib/format'
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
 * 각 줄은 해당 목록 화면으로 이동하는 링크다. 줄에 마우스를 올리거나 포커스하면 해당 조각이 강조된다.
 */
export function RiskComposition({ items }: { items: CompositionItem[] }) {
  const [activeKey, setActiveKey] = useState<string | null>(null)
  const drawn = useDrawn()
  const total = items.reduce((sum, item) => sum + item.value, 0)
  const segments = new Map(donutSegments(items).map((segment) => [segment.key, segment]))

  if (total === 0) {
    return <div className="empty-state">이번 달 표시할 위험 건이 없습니다.</div>
  }

  return (
    <div className="risk-composition">
      <div className="risk-donut" aria-hidden="true">
        <svg viewBox="0 0 100 100" className="risk-donut-svg">
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
        <div className="risk-donut-center">
          <span>항목별 비중</span>
        </div>
      </div>
      <ul className="risk-legend">
        {items.map((item, index) => {
          const share = total > 0 ? item.value / total : 0
          // 화면정의서 공통 규칙 3: 비율은 소수점 첫째 자리까지, 정수에도 .0을 붙인다
          const sharePct = `${(share * 100).toFixed(1)}%`
          return (
            <li key={item.key} style={{ '--i': index } as CSSProperties}>
              <Link
                to={item.to}
                className={`risk-legend-row${activeKey === item.key ? ' is-active' : ''}`}
                aria-label={`${item.label} ${int(item.value)}건, 비중 ${sharePct}`}
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
