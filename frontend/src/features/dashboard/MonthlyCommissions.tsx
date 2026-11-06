import { useState } from 'react'
import type { CSSProperties } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AnimatedNumber } from '../../components/AnimatedNumber'
import { won } from '../../lib/format'
import { monthText } from '../../lib/screenText'
import { monthlyCommissionsQueryOptions } from './api'
import type { MonthlyCommission } from './api'
import { RegionError } from './RegionStatus'
import { commissionChange, previousMonth } from './commission-trend'

const compactAmount = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 })

/** 선택 월 합계·전월 증감과 최근 6개월 확정 계약 귀속 수수료를 표시한다. */
export function MonthlyCommissions({ month, replay = 0 }: { month: string; replay?: number }) {
  const query = useQuery(monthlyCommissionsQueryOptions(month))
  const current = query.data?.months?.find((row) => row.month?.slice(0, 7) === month)
  const previous = query.data?.months?.find((row) => row.month?.slice(0, 7) === previousMonth(month))
  return (
    <section
      data-guide-feature="features.dashboard.monthly-commissions.section"
      className="surface dashboard-commissions"
      aria-labelledby="monthly-commissions-title"
    >
      <header className="surface-header">
        <h2 id="monthly-commissions-title" className="surface-title">
          월별 수수료
        </h2>
      </header>
      <p className="dashboard-commissions-basis">{monthText(month)} · 확정 지급 기준</p>
      <div key={replay}>
        {query.isError ? (
          <RegionError
            error={query.error}
            fallback="월별 수수료를 불러오지 못했습니다."
            updatedAt={query.dataUpdatedAt}
            busy={query.isFetching}
            onRetry={() => void query.refetch()}
          />
        ) : query.data ? (
          <div className="commission-overview">
            <div className="commission-totals">
              <div data-guide-target="dashboard.commissions.ga"><CommissionTotal label="GA 수령액" value={current?.gaAmount} previous={previous?.gaAmount} /></div>
              <div data-guide-target="dashboard.commissions.agent"><CommissionTotal label="설계사 지급액" value={current?.agentAmount} previous={previous?.agentAmount} /></div>
            </div>
            <CommissionChart months={query.data.months ?? []} />
          </div>
        ) : (
          <p className="empty-state" role="status">
            월별 수수료를 불러오는 중입니다.
          </p>
        )}
      </div>
    </section>
  )
}

/** 금액은 기존 KPI 모션으로 표시하고 비교할 수 없는 전월 값은 임의 비율로 만들지 않는다. */
function CommissionTotal({ label, value, previous }: { label: string; value?: number; previous?: number }) {
  const change = commissionChange(value, previous)
  return (
    <div className="commission-total">
      <span>{label}</span>
      <strong className="tabular-nums">
        {value == null ? won(value) : <AnimatedNumber value={value} format={won} />}
      </strong>
      <small data-direction={change.direction}>
        <span aria-hidden="true">{change.direction === 'up' ? '↗ ' : change.direction === 'down' ? '↘ ' : ''}</span>
        {change.text}
      </small>
    </div>
  )
}

/** 수령·지급 금액을 공통 축에 표시하고 hover·focus한 월의 실제 금액을 제공한다. */
function CommissionChart({ months }: { months: MonthlyCommission[] }) {
  const [hovered, setHovered] = useState<number | null>(null)
  const [focused, setFocused] = useState<number | null>(null)
  const active = hovered ?? focused
  const current = active == null ? undefined : months[active]
  const max = Math.max(0, ...months.flatMap((row) => [row.gaAmount ?? 0, row.agentAmount ?? 0])) || 10000

  return (
    <div className="commission-chart" data-guide-target="dashboard.commissions.chart">
      {!months.length ? <p className="empty-state">표시할 월별 수수료가 없습니다.</p> : <>
      <div className="commission-chart-summary" aria-live="polite" aria-atomic="true">
        {current && <span className="commission-active-month">{monthText(current.month?.slice(0, 7) ?? '')}</span>}
        <span className="commission-series-value">
          <i className="commission-ga-swatch" aria-hidden="true" />
          GA 수령액 {current && <strong>{won(current.gaAmount)}</strong>}
        </span>
        <span className="commission-series-value">
          <i className="commission-agent-swatch" aria-hidden="true" />
          설계사 지급액 {current && <strong>{won(current.agentAmount)}</strong>}
        </span>
      </div>
      <div className="commission-chart-scroll">
        <div className="commission-chart-canvas">
          <div className="commission-chart-axis" aria-hidden="true">
            <span>{compactAmount.format(max)}원</span>
            <span>{compactAmount.format(max / 2)}원</span>
            <span>0원</span>
          </div>
          <div className="commission-plot">
            <div className="commission-month-groups">
              {months.map((row, index) => (
                <button
                  data-guide-feature="features.dashboard.monthly-commissions.month"
                  key={row.month}
                  type="button"
                  className="commission-month"
                  style={{ '--bar-delay': `${index * 55}ms` } as CSSProperties}
                  data-active={index === active}
                  aria-label={`${monthText(row.month?.slice(0, 7) ?? '')}, GA 수령액 ${won(row.gaAmount)}, 설계사 지급액 ${won(row.agentAmount)}`}
                  onMouseEnter={() => setHovered(index)}
                  onMouseLeave={() => setHovered(null)}
                  onFocus={() => setFocused(index)}
                  onBlur={() => setFocused(null)}
                >
                  <span className="commission-bars" aria-hidden="true">
                    <span className="commission-ga-bar" style={{ height: `${((row.gaAmount ?? 0) / max) * 100}%` }} />
                    <span
                      className="commission-agent-bar"
                      style={{ height: `${((row.agentAmount ?? 0) / max) * 100}%` }}
                    />
                  </span>
                  <span className="commission-month-label" aria-hidden="true">
                    {row.month?.slice(2, 7).replace('-', '.')}
                  </span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
      </>}
    </div>
  )
}
