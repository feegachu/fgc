import { useQuery } from '@tanstack/react-query'
import { DataTable } from '../../components/DataTable'
import { QueryState } from '../reference/ReferenceUI'
import { date, int, won } from '../../lib/format'
import { arbitrageTimelineQuery } from './api'
import type { ArbitrageRow } from './api'
import { present } from './utils'
import { ArbitrageAmount } from './badges'
function Timeline({ rows }: { rows: ArbitrageRow[] }) {
  const values = rows.map((row) => Number(row.netDifferenceAmount) || 0)
  const max = Math.max(...values, 0),
    min = Math.min(...values, 0),
    range = max - min || 1
  const points = values.map((amount, index) => ({
    x: 24 + (values.length === 1 ? 136 : (index * 272) / (values.length - 1)),
    y: 116 - ((amount - min) * 92) / range,
  }))
  return (
    <>
      <h3 className="arb-timeline-title">기준일별 초과액 추이</h3>
      <svg className="arb-timeline-chart" viewBox="0 0 320 140" role="img" aria-label="기준일별 초과액 추이">
        <line className="arb-timeline-axis" x1="24" y1="116" x2="296" y2="116" />
        <polyline
          className="arb-timeline-line"
          points={points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')}
        />
        {points.map((point, index) => (
          <circle
            className="arb-timeline-point"
            key={rows[index].arbitrageCheckId}
            cx={point.x.toFixed(1)}
            cy={point.y.toFixed(1)}
            r="4"
          >
            <title>
              {date(rows[index].asOfDate)} · {won(rows[index].netDifferenceAmount)}
            </title>
          </circle>
        ))}
      </svg>
      <DataTable
        viewportClassName="arb-timeline-table"
        className="arb-timeline-data-table"
        caption="기준일별 차익거래 이력"
        rows={rows}
        rowKey={(r) => String(r.arbitrageCheckId)}
        columns={[
          { key: 'date', label: '기준일', render: (r) => date(r.asOfDate) },
          { key: 'month', label: '차월', align: 'number', render: (r) => int(r.contractMonthNo) },
          {
            key: 'premium',
            label: '누적 보험료',
            align: 'number',
            render: (r) => <ArbitrageAmount value={r.cumulativePaidPremium} />,
          },
          {
            key: 'paid',
            label: '지급수수료',
            align: 'number',
            render: (r) => <ArbitrageAmount value={r.paidCommissionAmount} />,
          },
          {
            key: 'excess',
            label: '초과액',
            align: 'number',
            render: (r) => <ArbitrageAmount value={r.netDifferenceAmount} excess />,
          },
        ]}
      />
    </>
  )
}
export function ArbitrageDetail({ row }: { row: ArbitrageRow }) {
  const timeline = useQuery(arbitrageTimelineQuery(row))
  return (
    <>
      <dl className="arb-detail-list">
        <div>
          <dt>계약 · 지급단계</dt>
          <dd>
            {row.contractNo} · {present(row.paymentStageLabel)}
          </dd>
        </div>
        <div>
          <dt>기준일 · 계약차월</dt>
          <dd>
            {date(row.asOfDate)} · {int(row.contractMonthNo)}개월
          </dd>
        </div>
        <div>
          <dt>환급금 가산 여부</dt>
          <dd>{row.refundAdditionAppliedYn ? '가산 함' : '가산 안 함'}</dd>
        </div>
        <div>
          <dt>환급금 출처</dt>
          <dd>{present(row.surrenderValueSourceTypeLabel)}</dd>
        </div>
        <div>
          <dt>판정 근거</dt>
          <dd>{present(row.decisionReason)}</dd>
        </div>
      </dl>
      <pre className="arb-calculation tabular-nums">
        확정 수수료: <ArbitrageAmount value={row.paidCommissionAmount} />
        {'\n'}지급예정액: <ArbitrageAmount value={row.plannedCommissionAmount} />
        {'\n'}해약환급금: <ArbitrageAmount value={row.includedSurrenderValueAmount} />
        {'\n'}누적 납입보험료: <ArbitrageAmount value={row.cumulativePaidPremium} />
        {'\n'}지급분 기준 초과액: <ArbitrageAmount value={row.netDifferenceAmount} />
        {'\n'}지급예정 포함 초과액 (모니터링): <ArbitrageAmount value={row.projectedExcessAmount} />
      </pre>
      <section className="arb-timeline-panel" aria-label="기준일별 초과액 추이">
        {timeline.isLoading || timeline.error ? (
          <div className="empty-state arb-timeline-state">
            <QueryState loading={timeline.isLoading} error={timeline.error} />
          </div>
        ) : timeline.data?.length ? (
          <Timeline rows={timeline.data} />
        ) : (
          <p className="empty-state arb-timeline-state">표시할 기준일별 이력이 없습니다.</p>
        )}
      </section>
    </>
  )
}
