import { useState } from 'react'
import type { ArbitrageRow } from './api'
import { ArbitrageAmount } from './badges'

const STORAGE_KEY = 'fgc:arbitrage:amount-view:v2'
const views = [
  { key: 'list', label: '목록형' },
  { key: 'compare', label: '비교형' },
  { key: 'cards', label: '카드형' },
] as const
type View = (typeof views)[number]['key']

function initialView(): View {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return views.find((view) => view.key === saved)?.key ?? 'list'
  } catch {
    return 'list'
  }
}

export function ArbitrageAmountSummary({ row }: { row: ArbitrageRow }) {
  const [view, setView] = useState(initialView)
  const metrics = [
    { key: 'paid', label: '확정 수수료 순액', value: row.paidCommissionAmount },
    { key: 'refund', label: '합산 해약환급금', value: row.includedSurrenderValueAmount },
    { key: 'premium', label: '누적 납입보험료', value: row.cumulativePaidPremium },
  ]
  const values = metrics.flatMap(({ value }) => (typeof value === 'number' && Number.isFinite(value) ? [value] : []))
  const min = Math.min(0, ...values)
  const max = Math.max(0, ...values)
  const range = max - min || 1
  const zero = (-min / range) * 100

  function selectView(next: View) {
    setView(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // View switching also works when browser storage is unavailable.
    }
  }

  return (
    <section data-guide-feature="features.arbitrage.arbitrage-amount-summary.section" className={`arb-calculation arb-amount-summary is-${view}`} aria-label="차익거래 금액 요약">
      <header className="arb-amount-header">
        <h3>금액 요약</h3>
        <div className="arb-amount-views" role="group" aria-label="금액 표시 방식">
          {views.map((option) => (
            <button
              data-guide-feature="features.arbitrage.arbitrage-amount-summary.button"
              key={option.key}
              type="button"
              className="arb-amount-view-button"
              aria-label={option.label}
              title={option.label}
              aria-pressed={view === option.key}
              onClick={() => selectView(option.key)}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                {option.key === 'compare' ? (
                  <path d="M4 4h3v16H4zM10.5 9h3v11h-3zM17 13h3v7h-3z" />
                ) : option.key === 'cards' ? (
                  <path d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z" />
                ) : (
                  <path d="M9 6h12M9 12h12M9 18h12M3 5h2v2H3zM3 11h2v2H3zM3 17h2v2H3z" />
                )}
              </svg>
            </button>
          ))}
        </div>
      </header>
      <div key={view} className="arb-amount-content">
        {view === 'list' ? (
          <dl className="arb-amount-list">
            {metrics.map((metric) => (
              <div key={metric.key}>
                <dt>{metric.label}</dt>
                <dd>
                  <ArbitrageAmount value={metric.value} />
                </dd>
              </div>
            ))}
            <div className="arb-amount-list-excess">
              <dt>지급분 기준 초과액</dt>
              <dd>
                <ArbitrageAmount value={row.netDifferenceAmount} excess />
              </dd>
            </div>
          </dl>
        ) : (
          <>
            {view === 'compare' && (
              <div className="arb-amount-bars">
                {metrics.map((metric) => {
                  const amount = typeof metric.value === 'number' && Number.isFinite(metric.value) ? metric.value : null
                  return (
                    <div key={metric.key}>
                      <dl className="arb-amount-bar-heading">
                        <dt>{metric.label}</dt>
                        <dd>
                          <ArbitrageAmount value={metric.value} />
                        </dd>
                      </dl>
                      <div className={`arb-amount-track is-${metric.key}`} aria-hidden="true">
                        {amount !== null && (
                          <span
                            className={`arb-amount-fill${amount < 0 ? ' is-negative' : ''}`}
                            style={{
                              left: `${((Math.min(0, amount) - min) / range) * 100}%`,
                              width: `${(Math.abs(amount) / range) * 100}%`,
                            }}
                          />
                        )}
                        {min < 0 && <span className="arb-amount-zero" style={{ left: `${zero}%` }} />}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
            <div className="arb-amount-excess">
              <span>지급분 기준 초과액</span>
              <strong className="tabular-nums">
                <ArbitrageAmount value={row.netDifferenceAmount} excess />
              </strong>
            </div>
            {view === 'cards' && (
              <dl className="arb-amount-cards">
                {metrics.map((metric) => (
                  <div key={metric.key}>
                    <dt>{metric.label}</dt>
                    <dd>
                      <ArbitrageAmount value={metric.value} />
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </>
        )}
        <dl className="arb-amount-reference">
          <div>
            <dt>
              <span className="arb-amount-reference-label">참고</span>지급예정액
            </dt>
            <dd>
              <ArbitrageAmount value={row.plannedCommissionAmount} />
            </dd>
          </div>
          <div>
            <dt>
              <span className="arb-amount-reference-label">참고</span>지급예정 포함 초과액 (모니터링, 판정에 쓰지 않음)
            </dt>
            <dd>
              <ArbitrageAmount value={row.projectedExcessAmount} />
            </dd>
          </div>
        </dl>
      </div>
    </section>
  )
}
