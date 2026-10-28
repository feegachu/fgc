import { useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { int, won } from '../../lib/format'
import { useDrawn, useTween } from '../../lib/motion'
import type { Slice } from './model'

/** 마우스를 올리면 따라다니는 설명 상자. 차트 값은 범례·목록·숨은 표가 스크린 리더에 대신 읽어 준다. */
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
  // 필터를 바꾸면 조각이 이전 비율에서 새 비율로 부드럽게 옮겨 간다.
  const shownValues = useTween(slices.map((s) => s.value))
  const shown = slices.map((s, i) => ({ ...s, value: shownValues[i] ?? s.value }))
  const positive = shown.filter((s) => s.value > 0.0005)
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
          {sum === 0 && (
            <circle cx="50" cy="50" r={(R_OUT + R_IN) / 2} fill="none" stroke="#f1f5f9" strokeWidth={R_OUT - R_IN} />
          )}
          {arcs.map(({ s, d }) => {
            const dim = selected && selected !== s.name
            const real = slices.find((x) => x.name === s.name) ?? s
            const share = total ? Math.round((real.value / total) * 1000) / 10 : 0
            const common = {
              fill: s.color,
              opacity: dim ? 0.22 : 1,
              className: `ah-slice${hover === s.name ? ' is-hover' : ''}`,
              style: drawn ? undefined : { transform: 'scale(.6)' },
              onMouseMove: (e: React.MouseEvent<SVGElement>) => {
                setHover(s.name)
                show(
                  e,
                  <>
                    <b>{s.name}</b>
                    <div>
                      계약 <b>{real.value}건</b> · 비중 <b>{share}%</b>
                    </div>
                    <small>월환산 {int(real.premium)}원</small>
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
              <circle
                key={s.name}
                cx="50"
                cy="50"
                r={(R_OUT + R_IN) / 2}
                fill="none"
                stroke={s.color}
                strokeWidth={R_OUT - R_IN}
                opacity={common.opacity}
                onClick={common.onClick}
                onMouseMove={common.onMouseMove}
                onMouseLeave={common.onMouseLeave}
              />
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
              <button
                type="button"
                aria-pressed={selected === s.name}
                onClick={() => onSelect(s.name)}
                onMouseEnter={() => setHover(s.name)}
                onMouseLeave={() => setHover(null)}
              >
                <span className="ah-lg-dot" style={{ background: s.color }} />
                <span className="ah-lg-name">
                  {s.name}
                  <b>{s.value}</b>
                </span>
                <em>{total ? ((s.value / total) * 100).toFixed(1) : '0.0'}%</em>
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
  const W = 560
  const H = 232
  const L = 30
  const T = 30
  const B = 24
  const maxCount = Math.max(1, ...points.map((p) => p.count))
  const ticks = [0, Math.ceil(maxCount / 3), Math.ceil((maxCount * 2) / 3), maxCount].filter(
    (v, i, a) => a.indexOf(v) === i,
  )
  const top = Math.max(...ticks, 1)
  const step = (W - L - 8) / points.length
  const y = (v: number) => T + (H - T - B) * (1 - v / top)
  const best = points.reduce((b, p, i) => (p.count > points[b].count ? i : b), 0)
  // 막대와 선은 같은 값으로 함께 움직인다(처음 그릴 때는 바닥에서 올라오고, 필터를 바꾸면 새 값으로 옮겨 간다).
  const shownCounts = useTween(
    points.map((p) => p.count),
    500,
    true,
  )
  const shown = points.map((_, i) => shownCounts[i] ?? 0)
  const [hoverIdx, setHoverIdx] = useState<number | null>(null)
  // 선의 점은 각 막대 윗면 가운데에 닿는다. 보험료는 마우스를 올리면 보인다.
  const line = shown.map((v, i) => [L + step * (i + 0.5), y(v)])
  const path = line.map(([x, v], i) => `${i ? 'L' : 'M'}${x} ${v}`).join('')
  const every = points.length > 12 ? 2 : 1
  return (
    <div className="ah-trend ah-chart">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label="월별 신규 계약 건수 막대와 추이선 차트"
        preserveAspectRatio="none"
      >
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
          const h = (H - T - B) * (shown[i] / top)
          const x = L + step * (i + 0.5)
          return (
            <g key={p.month}>
              <rect
                x={x - Math.min(9, step * 0.32)}
                y={H - B - h}
                width={Math.min(18, step * 0.64)}
                height={h}
                rx="4"
                fill="#7f57ac"
                className={`ah-bar${hoverIdx === i ? ' is-hover' : ''}`}
              />
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
                fill={hoverIdx === i ? 'rgba(127,87,172,.06)' : 'transparent'}
                onMouseMove={(e) => {
                  setHoverIdx(i)
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
                }}
                onMouseLeave={() => {
                  setHoverIdx(null)
                  hide()
                }}
              />
            </g>
          )
        })}
        <path d={path} fill="none" stroke="#8467a5" strokeWidth="2" strokeLinejoin="round" />
        {points.length <= 12 &&
          line.map(([x, v], i) => (
            <circle key={points[i].month} cx={x} cy={v} r="2.6" fill="#8467a5" stroke="#fff" strokeWidth="1.5" />
          ))}
        {points[best].count > 0 && (
          <g>
            <rect
              x={L + step * (best + 0.5) - 19}
              y={y(shown[best]) - 24}
              width="38"
              height="17"
              rx="4"
              fill="#6a4893"
            />
            <text x={L + step * (best + 0.5)} y={y(shown[best]) - 12} textAnchor="middle" className="ah-best">
              BEST
            </text>
          </g>
        )}
      </svg>
      <table className="visually-hidden">
        <caption>월별 신규 계약 건수와 월 환산 보험료</caption>
        <thead>
          <tr>
            <th scope="col">월</th>
            <th scope="col">신규 계약</th>
            <th scope="col">월 환산 보험료</th>
          </tr>
        </thead>
        <tbody>
          {points.map((p) => (
            <tr key={p.month}>
              <th scope="row">{p.month}</th>
              <td>{p.count}건</td>
              <td>{won(p.premium)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Tip tip={tip} />
    </div>
  )
}

export function ProductBars({ items }: { items: { name: string; count: number; pct: number; color: string }[] }) {
  const { tip, show, hide } = useTip()
  const drawn = useDrawn()
  const max = Math.max(1, ...items.map((i) => i.count)) * 1.18
  return (
    <div className="ah-chart">
      <ul className="ah-product" aria-label="보장 유형별 계약 비중 가로 막대 차트">
        {items.map((item) => (
          <li
            key={item.name}
            onMouseMove={(e) =>
              show(
                e,
                <>
                  <b>{item.name}</b>
                  <div>
                    {item.count}건 · {item.pct.toFixed(1)}%
                  </div>
                </>,
              )
            }
            onMouseLeave={hide}
          >
            <span className="ah-pd-name">{item.name}</span>
            <span className="ah-pd-track">
              <span
                className="ah-pd-fill"
                style={{ width: drawn ? `${(item.count / max) * 100}%` : 0, background: item.color }}
              />
            </span>
            <b>
              {item.count}건 · {item.pct.toFixed(1)}%
            </b>
          </li>
        ))}
      </ul>
      <Tip tip={tip} />
    </div>
  )
}

export function StatusBar({
  items,
}: {
  items: { key: string; label: string; color: string; count: number; pct: number }[]
}) {
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
            <em>{i.pct.toFixed(1)}%</em>
          </li>
        ))}
      </ul>
    </>
  )
}
