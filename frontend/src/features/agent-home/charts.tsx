import { useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { int, won } from '../../lib/format'
import { useDrawn } from '../../lib/motion'
import type { Slice } from './model'

/** 마우스를 올리면 따라다니는 설명 상자. 차트 본체는 aria-label과 아래 범례·표가 대신 읽어 준다. */
function useTip() {
  const [tip, setTip] = useState<{ x: number; y: number; body: ReactNode } | null>(null)
  const show = (event: React.MouseEvent<HTMLElement | SVGElement>, body: ReactNode) => {
    const box = (event.currentTarget.closest('.ah-chart') as HTMLElement).getBoundingClientRect()
    setTip({ x: event.clientX - box.left, y: event.clientY - box.top, body })
  }
  return { tip, show, hide: () => setTip(null) }
}
function Tip({ tip }: { tip: { x: number; y: number; body: ReactNode } | null }) {
  return tip ? (
    <div className="ah-tip" style={{ left: tip.x + 12, top: tip.y + 12 } as CSSProperties}>
      {tip.body}
    </div>
  ) : null
}

const R_OUT = 45.5
const R_IN = 33
const arc = (from: number, to: number, outer: number, inner: number) => {
  const p = (a: number, r: number) => [50 + r * Math.cos(a), 50 + r * Math.sin(a)]
  const big = to - from > Math.PI ? 1 : 0
  const [x1, y1] = p(from, outer)
  const [x2, y2] = p(to, outer)
  const [x3, y3] = p(to, inner)
  const [x4, y4] = p(from, inner)
  return `M${x1} ${y1}A${outer} ${outer} 0 ${big} 1 ${x2} ${y2}L${x3} ${y3}A${inner} ${inner} 0 ${big} 0 ${x4} ${y4}Z`
}

export function Donut({
  slices,
  total,
  centerTotal,
  hint,
  selected,
  onSelect,
}: {
  slices: Slice[]
  total: number
  centerTotal: number
  hint: string
  selected: string | null
  onSelect: (name: string) => void
}) {
  const { tip, show, hide } = useTip()
  const drawn = useDrawn()
  const [hover, setHover] = useState<string | null>(null)
  const positive = slices.filter((s) => s.value > 0)
  const sum = positive.reduce((a, s) => a + s.value, 0)
  const GAP = positive.length > 1 ? 0.025 : 0
  const arcs = positive.map((s, index) => {
    const before = positive.slice(0, index).reduce((acc, x) => acc + x.value, 0)
    const start = -Math.PI / 2 + (before / sum) * Math.PI * 2
    const span = (s.value / sum) * Math.PI * 2
    const from = start + GAP / 2
    const to = Math.max(from + 0.001, start + span - GAP / 2)
    return { s, d: span >= Math.PI * 2 - 0.01 ? null : arc(from, to, R_OUT, R_IN) }
  })
  return (
    <>
      <div className="ah-donut-canvas ah-chart">
        <svg viewBox="0 0 100 100" role="img" aria-label="보험사별 계약 건수 도넛 차트">
          {sum === 0 && <circle cx="50" cy="50" r={(R_OUT + R_IN) / 2} fill="none" stroke="#f1f5f9" strokeWidth={R_OUT - R_IN} />}
          {arcs.map(({ s, d }) => {
            const dim = selected && selected !== s.name
            const share = total ? Math.round((s.value / total) * 1000) / 10 : 0
            const common = {
              fill: s.color,
              opacity: dim ? 0.22 : 1,
              className: `ah-slice${hover === s.name ? ' is-hover' : ''}`,
              style: { transform: drawn ? 'scale(1)' : 'scale(.6)', transformOrigin: '50% 50%' },
              onMouseMove: (e: React.MouseEvent<SVGElement>) => {
                setHover(s.name)
                show(
                  e,
                  <>
                    <b>{s.name}</b>
                    <div>
                      계약 <b>{s.value}건</b> · 비중 <b>{share}%</b>
                    </div>
                    <small>월환산 {int(s.premium)}원</small>
                  </>,
                )
              },
              onMouseLeave: () => {
                setHover(null)
                hide()
              },
              onClick: () => onSelect(s.name),
            }
            return d ? (
              <path key={s.name} d={d} {...common} />
            ) : (
              <circle key={s.name} cx="50" cy="50" r={(R_OUT + R_IN) / 2} fill="none" stroke={s.color} strokeWidth={R_OUT - R_IN} opacity={common.opacity} onClick={common.onClick} onMouseMove={common.onMouseMove} onMouseLeave={common.onMouseLeave} />
            )
          })}
        </svg>
        <div className="ah-donut-center">
          <b>{centerTotal}</b>
          <span>총 계약</span>
          <em>{hint}</em>
        </div>
        <Tip tip={tip} />
      </div>
      <ul className="ah-donut-legend">
        {slices.map((s) => {
          const cls = selected ? (selected === s.name ? 'sel' : 'dim') : ''
          return (
            <li key={s.name} className={cls}>
              <button type="button" aria-pressed={selected === s.name} onClick={() => onSelect(s.name)} onMouseEnter={() => setHover(s.name)} onMouseLeave={() => setHover(null)}>
                <span className="ah-lg-dot" style={{ background: s.color }} />
                <span className="ah-lg-name">
                  {s.name}
                  <b>{s.value}</b>
                </span>
                <em>{total ? Math.round((s.value / total) * 100) : 0}%</em>
              </button>
            </li>
          )
        })}
      </ul>
    </>
  )
}

export function Trend({ points }: { points: { month: string; count: number; premium: number }[] }) {
  const { tip, show, hide } = useTip()
  const drawn = useDrawn()
  const W = 560
  const H = 232
  const L = 30
  const T = 30
  const B = 24
  const maxCount = Math.max(1, ...points.map((p) => p.count))
  const ticks = [0, Math.ceil(maxCount / 3), Math.ceil((maxCount * 2) / 3), maxCount].filter((v, i, a) => a.indexOf(v) === i)
  const top = Math.max(...ticks, 1)
  const step = (W - L - 8) / points.length
  const y = (v: number) => T + (H - T - B) * (1 - v / top)
  const best = points.reduce((b, p, i) => (p.count > points[b].count ? i : b), 0)
  // 선의 점은 각 막대 윗면 가운데에 닿는다. 보험료는 마우스를 올리면 보인다.
  const line = points.map((p, i) => [L + step * (i + 0.5), y(p.count)])
  const path = line.map(([x, v], i) => `${i ? 'L' : 'M'}${x} ${v}`).join('')
  const every = points.length > 12 ? 2 : 1
  return (
    <div className="ah-trend ah-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="월별 신규 계약 건수 막대와 추이선 차트" preserveAspectRatio="none">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={L} x2={W - 8} y1={y(t)} y2={y(t)} stroke="#f1f5f9" />
            <text x={L - 6} y={y(t) + 3} textAnchor="end" className="ah-axis">
              {t}
            </text>
          </g>
        ))}
        <line x1={L} x2={W - 8} y1={H - B} y2={H - B} stroke="#e2e8f0" />
        <path d={`${path}L${line[line.length - 1][0]} ${H - B}L${line[0][0]} ${H - B}Z`} fill="rgba(132,103,165,.08)" />
        {points.map((p, i) => {
          const h = drawn ? (H - T - B) * (p.count / top) : 0
          const x = L + step * (i + 0.5)
          return (
            <g key={p.month}>
              <rect x={x - Math.min(9, step * 0.32)} y={H - B - h} width={Math.min(18, step * 0.64)} height={h} rx="4" fill="#7f57ac" className="ah-bar" />
              {i % every === 0 && (
                <text x={x} y={H - 6} textAnchor="middle" className="ah-axis">
                  {p.month.slice(2).replace('-', '.')}
                </text>
              )}
              <rect
                x={L + step * i}
                y={T}
                width={step}
                height={H - T - B}
                fill="transparent"
                onMouseMove={(e) =>
                  show(
                    e,
                    <>
                      <b>{p.month}</b>
                      <div>
                        신규 계약 <b>{p.count}건</b>
                      </div>
                      <small>월환산 {won(p.premium)}</small>
                    </>,
                  )
                }
                onMouseLeave={hide}
              />
            </g>
          )
        })}
        <path d={path} fill="none" stroke="#8467a5" strokeWidth="2" strokeLinejoin="round" />
        {points.length <= 12 && line.map(([x, v], i) => <circle key={points[i].month} cx={x} cy={v} r="2.6" fill="#8467a5" stroke="#fff" strokeWidth="1.5" />)}
        {points[best].count > 0 && (
          <g>
            <rect x={L + step * (best + 0.5) - 19} y={y(points[best].count) - 24} width="38" height="17" rx="4" fill="#6a4893" />
            <text x={L + step * (best + 0.5)} y={y(points[best].count) - 12} textAnchor="middle" className="ah-best">
              BEST
            </text>
          </g>
        )}
      </svg>
      <Tip tip={tip} />
    </div>
  )
}

export function ProductBars({ items }: { items: { name: string; count: number; pct: number; color: string }[] }) {
  const drawn = useDrawn()
  const max = Math.max(1, ...items.map((i) => i.count)) * 1.18
  return (
    <ul className="ah-product" role="img" aria-label="보장 유형별 계약 비중 가로 막대 차트">
      {items.map((item) => (
        <li key={item.name}>
          <span className="ah-pd-name">{item.name}</span>
          <span className="ah-pd-track">
            <span className="ah-pd-fill" style={{ width: drawn ? `${(item.count / max) * 100}%` : 0, background: item.color }} />
          </span>
          <b>
            {item.count}건 · {item.pct}%
          </b>
        </li>
      ))}
    </ul>
  )
}

export function StatusBar({ items }: { items: { key: string; label: string; color: string; count: number; pct: number }[] }) {
  const drawn = useDrawn()
  const total = items.reduce((s, i) => s + i.count, 0) || 1
  return (
    <>
      <div className="ah-status-bar" aria-hidden="true">
        {items.map((i) => (
          <span key={i.key} style={{ width: drawn ? `${(i.count / total) * 100}%` : 0, background: i.color }} />
        ))}
      </div>
      <ul className="ah-status-legend">
        {items.map((i) => (
          <li key={i.key}>
            <span className="ah-sg-dot" style={{ background: i.color }} />
            {i.label} <b>{i.count}</b>
            <em>{i.pct}%</em>
          </li>
        ))}
      </ul>
    </>
  )
}
