import { useQuery } from '@tanstack/react-query'
import { QueryState } from '../reference/ReferenceUI'
import { date, int } from '../../lib/format'
import { arbitrageTimelineQuery } from './api'
import type { ArbitrageRow } from './api'
import { present } from './utils'
import { ArbitrageAmountSummary } from './ArbitrageAmountSummary'
import { ArbitrageTimeline } from './ArbitrageTimeline'
export function ArbitrageDetail({ row }: { row: ArbitrageRow }) {
  const timeline = useQuery(arbitrageTimelineQuery(row))
  return (
    <>
      <dl className="arb-detail-list">
        <div>
          <dt>계약, 지급단계</dt>
          <dd>
            {row.contractNo}, {present(row.paymentStageLabel)}
          </dd>
        </div>
        <div>
          <dt>기준일, 계약차월</dt>
          <dd>
            {date(row.asOfDate)}, {int(row.contractMonthNo)}개월
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
      <ArbitrageAmountSummary row={row} />
      <section className="arb-timeline-panel" aria-label="기준일별 초과액 추이">
        {timeline.isLoading || timeline.error ? (
          <div className="empty-state arb-timeline-state">
            <QueryState loading={timeline.isLoading} error={timeline.error} />
          </div>
        ) : timeline.data?.length ? (
          <ArbitrageTimeline rows={timeline.data} />
        ) : (
          <p className="empty-state arb-timeline-state">표시할 기준일별 이력이 없습니다.</p>
        )}
      </section>
    </>
  )
}
