import type { TableColumn } from '../../components/DataTable'
import { ArbitrageBadge, ArbitrageAmount } from './badges'
import { present } from './utils'
import { StatusBadge } from '../../components/StatusBadge'
import { date, int } from '../../lib/format'
import type { ArbitrageRow } from './api'
export const columns: TableColumn<ArbitrageRow>[] = [
  {
    key: 'contract',
    label: '계약',
    width: '12rem',
    cellClassName: 'tabular-nums',
    render: (r) => <span title={present(r.contractNo)}>{present(r.contractNo)}</span>,
  },
  { key: 'stage', label: '지급단계', width: '7rem', render: (r) => present(r.paymentStageLabel) },
  { key: 'date', label: '기준일', width: '6.5rem', cellClassName: 'tabular-nums', render: (r) => date(r.asOfDate) },
  { key: 'month', label: '계약차월', width: '5.5rem', align: 'number', render: (r) => int(r.contractMonthNo) },
  {
    key: 'premium',
    label: '누적 납입보험료',
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.cumulativePaidPremium} />,
  },
  {
    key: 'paid',
    label: '확정 수수료',
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.paidCommissionAmount} />,
  },
  {
    key: 'planned',
    label: '지급예정액',
    width: '8rem',
    align: 'number',
    render: (r) => (
      <>
        <ArbitrageAmount value={r.plannedCommissionAmount} />
        {Number(r.projectedExcessAmount) > 0 && (
          <div>지급예정 포함 시 초과 (모니터링): <ArbitrageAmount value={r.projectedExcessAmount} /></div>
        )}
      </>
    ),
  },
  {
    key: 'refund',
    label: '해약환급금',
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.includedSurrenderValueAmount} />,
  },
  { key: 'source', label: '환급금 출처', width: '7.5rem', render: (r) => present(r.surrenderValueSourceTypeLabel) },
  {
    key: 'excess',
    label: '초과액',
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.netDifferenceAmount} excess />,
  },
  {
    key: 'addition',
    label: '환급금 가산',
    width: '7.5rem',
    render: (r) => (
      <StatusBadge tone={r.refundAdditionAppliedYn ? 'info' : 'neutral'}>
        {r.refundAdditionAppliedYn ? '가산 함' : '가산 안 함'}
      </StatusBadge>
    ),
  },
  { key: 'status', label: '판정', width: '7rem', render: (r) => <ArbitrageBadge row={r} /> },
]
