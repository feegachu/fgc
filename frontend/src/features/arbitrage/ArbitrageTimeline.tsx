import { useEffect, useId, useRef, useState } from 'react'
import { DataTable } from '../../components/DataTable'
import { date, int, won } from '../../lib/format'
import type { ArbitrageRow } from './api'
import { ArbitrageAmount } from './badges'

const LEFT = 76
const TOP = 38
const BOTTOM = 186
const tickNumber = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 })

function day(value?: string) {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const time = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? time : null
}

function axis(values: number[]) {
  const min = Math.min(0, ...values)
  const max = Math.max(0, ...values)
  if (min === max) return { min: 0, max: 1, ticks: [0] }
  const rough = (max - min) / 5
  const magnitude = 10 ** Math.floor(Math.log10(rough))
  const factor = [1, 2, 5, 10].find((value) => value * magnitude >= rough) ?? 10
  const step = Math.max(1, factor * magnitude)
  const lower = Math.floor(min / step) * step
  const upper = Math.ceil(max / step) * step
  return { min: lower, max: upper, ticks: Array.from({ length: Math.round((upper - lower) / step) + 1 }, (_, index) => lower + index * step) }
}

export function ArbitrageTimeline({ rows }: { rows: ArbitrageRow[] }) {
  const descriptionId = useId()
  const chartRef = useRef<SVGSVGElement>(null)
  const [width, setWidth] = useState(480)
  const samples = rows.map((row) => ({
    row,
    time: day(row.asOfDate),
    amount: typeof row.netDifferenceAmount === 'number' && Number.isSafeInteger(row.netDifferenceAmount) ? row.netDifferenceAmount : null,
  }))
  const dates = [...new Set(samples.flatMap(({ time }) => time === null ? [] : [time]))].sort((a, b) => a - b)
  const values = samples.flatMap(({ time, amount }) => time !== null && amount !== null ? [amount] : [])
  const hasValues = values.length > 0
  useEffect(() => {
    if (!chartRef.current || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      if (entry?.contentRect.width) setWidth(Math.max(280, Math.round(entry.contentRect.width)))
    })
    observer.observe(chartRef.current)
    return () => observer.disconnect()
  }, [hasValues])
  const right = width - 18
  const yAxis = axis(values)
  const start = dates[0] ?? 0
  const end = dates.at(-1) ?? start
  const x = (time: number) => end === start ? (LEFT + right) / 2 : LEFT + ((time - start) / (end - start)) * (right - LEFT)
  const y = (amount: number) => BOTTOM - ((amount - yAxis.min) / (yAxis.max - yAxis.min)) * (BOTTOM - TOP)
  const middle = dates[Math.floor(dates.length / 2)]
  const xTicks = dates.length < 3 ? dates : [start, ...(x(middle) - LEFT > 100 && right - x(middle) > 100 ? [middle] : []), end]
  const segments: { x: number; y: number; row: ArbitrageRow }[][] = [[]]
  for (const sample of samples) {
    if (sample.time === null || sample.amount === null) {
      if (segments.at(-1)!.length) segments.push([])
    } else segments.at(-1)!.push({ x: x(sample.time), y: y(sample.amount), row: sample.row })
  }
  return (
    <>
      <h3 className="arb-timeline-title">기준일별 초과액 추이</h3>
      <p className="arb-timeline-description" id={descriptionId}>
        선택한 계약과 지급단계의 기준일별 지급분 초과액을 비교합니다.
      </p>
      {values.length ? (
        <div className="arb-timeline-chart-viewport">
          <svg ref={chartRef} className="arb-timeline-chart" viewBox={`0 0 ${width} 238`} role="img" aria-label="기준일별 초과액 추이" aria-describedby={descriptionId}>
            <text className="arb-timeline-axis-label" x={LEFT} y="17">지급분 기준 초과액 (원)</text>
            {yAxis.ticks.map((amount) => (
              <g key={amount}>
                <line className={amount === 0 ? 'arb-timeline-axis' : 'arb-timeline-grid'} x1={LEFT} y1={y(amount)} x2={right} y2={y(amount)} />
                <text className="arb-timeline-tick" x={LEFT - 9} y={y(amount) + 4} textAnchor="end">{tickNumber.format(amount)}</text>
              </g>
            ))}
            <line className="arb-timeline-axis" x1={LEFT} y1={TOP} x2={LEFT} y2={BOTTOM} />
            <line className="arb-timeline-axis" x1={LEFT} y1={BOTTOM} x2={right} y2={BOTTOM} />
            {xTicks.map((time, index) => (
              <g key={time}>
                <line className="arb-timeline-axis" x1={x(time)} y1={BOTTOM} x2={x(time)} y2={BOTTOM + 4} />
                <text className="arb-timeline-tick" x={x(time)} y={BOTTOM + 20} textAnchor={xTicks.length === 1 ? 'middle' : index === 0 ? 'start' : index === xTicks.length - 1 ? 'end' : 'middle'}>
                  {new Date(time).toISOString().slice(0, 10)}
                </text>
              </g>
            ))}
            <text className="arb-timeline-axis-label" x={(LEFT + right) / 2} y="230" textAnchor="middle">기준일</text>
            {segments.filter((segment) => segment.length).map((segment, index) => (
              <g key={index}>
                <polyline className="arb-timeline-line" points={segment.map((point) => `${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ')} />
                {segment.map((point) => (
                  <circle className="arb-timeline-point" key={point.row.arbitrageCheckId} cx={point.x.toFixed(1)} cy={point.y.toFixed(1)} r="4">
                    <title>{date(point.row.asOfDate)}, 지급분 기준 초과액 {won(point.row.netDifferenceAmount)}</title>
                  </circle>
                ))}
              </g>
            ))}
          </svg>
        </div>
      ) : <p className="empty-state arb-timeline-state">그래프로 표시할 기준일과 초과액이 없습니다.</p>}
      <DataTable
        viewportClassName="arb-timeline-table"
        className="arb-timeline-data-table"
        caption="기준일별 차익거래 이력"
        rows={rows}
        rowKey={(row) => String(row.arbitrageCheckId)}
        columns={[
          { key: 'date', label: '기준일', render: (row) => date(row.asOfDate) },
          { key: 'month', label: '차월', align: 'left', render: (row) => int(row.contractMonthNo) },
          { key: 'premium', label: '누적 보험료', align: 'number', render: (row) => <ArbitrageAmount value={row.cumulativePaidPremium} /> },
          { key: 'paid', label: '지급수수료', align: 'number', render: (row) => <ArbitrageAmount value={row.paidCommissionAmount} /> },
          { key: 'excess', label: '초과액', align: 'number', render: (row) => <ArbitrageAmount value={row.netDifferenceAmount} excess /> },
        ]}
      />
    </>
  )
}
