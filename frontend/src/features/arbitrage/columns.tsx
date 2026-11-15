import type { TableColumn } from '../../components/DataTable'
import { ArbitrageBadge, ArbitrageAmount } from './badges'
import { present } from './utils'
import { StatusBadge } from '../../components/StatusBadge'
import { date, int, isNegative, won } from '../../lib/format'
import { EvidenceLink } from '../../components/EvidenceLink'
import type { ArbitrageRow } from './api'
import { plannedBasisPresentation } from './planned-basis'
export const columns: TableColumn<ArbitrageRow>[] = [
  {
    key: 'contract',
    label: '계약',
    sortKey: 'contractNo',
    sortable: true,
    width: '12rem',
    cellClassName: 'tabular-nums',
    render: (r) => <span title={present(r.contractNo)}>{present(r.contractNo)}</span>,
  },
  { key: 'stage', label: '지급단계', width: '7rem', render: (r) => present(r.paymentStageLabel) },
  {
    key: 'date',
    label: '기준일',
    sortKey: 'asOfDate',
    sortable: true,
    width: '6.5rem',
    cellClassName: 'tabular-nums',
    render: (r) => date(r.asOfDate),
  },
  {
    key: 'month',
    label: '계약차월',
    sortKey: 'contractMonthNo',
    sortable: true,
    width: '5.5rem',
    align: 'left',
    render: (r) => int(r.contractMonthNo),
  },
  {
    key: 'premium',
    label: '누적 납입보험료',
    sortKey: 'cumulativePaidPremium',
    sortable: true,
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.cumulativePaidPremium} />,
  },
  {
    key: 'paid',
    label: '확정 수수료 순액',
    sortKey: 'paidCommissionAmount',
    sortable: true,
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.paidCommissionAmount} />,
  },
  {
    key: 'planned',
    label: '지급예정액 및 계산 기준',
    sortKey: 'plannedCommissionAmount',
    sortable: true,
    width: '12rem',
    align: 'number',
    render: (r) => {
      const basis = plannedBasisPresentation(r.plannedCommissionBasis)
      return (
        <>
          <ArbitrageAmount value={r.plannedCommissionAmount} />
          <span className="arb-planned-basis">{basis.label}</span>
        </>
      )
    },
  },
  {
    key: 'refund',
    label: '합산 해약환급금',
    sortKey: 'includedSurrenderValueAmount',
    sortable: true,
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.includedSurrenderValueAmount} />,
  },
  { key: 'source', label: '환급금 출처', width: '7.5rem', render: (r) => present(r.surrenderValueSourceTypeLabel) },
  {
    key: 'excess',
    label: '초과액',
    sortKey: 'netDifferenceAmount',
    sortable: true,
    width: '8rem',
    align: 'number',
    render: (r) => <ArbitrageAmount value={r.netDifferenceAmount} excess />,
  },
  {
    key: 'addition',
    label: '환급금 가산',
    width: '7.5rem',
    align: 'center',
    render: (r) => (
      <StatusBadge tone={r.refundAdditionAppliedYn ? 'info' : 'neutral'}>
        {r.refundAdditionAppliedYn ? '가산 함' : '가산 안 함'}
      </StatusBadge>
    ),
  },
  { key: 'status', label: '판정', width: '7rem', align: 'center', render: (r) => <ArbitrageBadge row={r} /> },
]

export const compactColumns = columns.map((column): TableColumn<ArbitrageRow> => column.key === 'planned' ? {
  ...column,
  label: '지급예정액',
  width: '8rem',
  render: (row) => {
    const basis = plannedBasisPresentation(row.plannedCommissionBasis)
    return (
      <span className={`arb-planned-amount${isNegative(row.plannedCommissionAmount) ? ' is-negative-amount' : ''}`}>
        <EvidenceLink label={won(row.plannedCommissionAmount)}>
          <strong>{basis.label}</strong>
          <p>{basis.description}</p>
        </EvidenceLink>
      </span>
    )
  },
} : column)
