import type { TableColumn } from '../../components/DataTable'
import { date } from '../../lib/format'
import { present } from '../arbitrage/utils'
import type { CandidateRow } from './api'
import { Amount, CalculatedAmount, CalculationBadge } from './badges'
import { triggerLabels } from './labels'
export const columns: TableColumn<CandidateRow>[] = [
  {
    key: 'candidateId',
    label: '후보 ID',
    sortable: true,
    width: '6rem',
    cellClassName: 'tabular-nums',
    render: (r) => present(r.candidateId),
  },
  {
    key: 'contractNo',
    label: '계약번호',
    sortable: true,
    width: '10rem',
    cellClassName: 'tabular-nums',
    render: (r) => <span title={present(r.contractNo)}>{present(r.contractNo)}</span>,
  },
  {
    key: 'product',
    label: '보험회사 · 상품',
    width: '13rem',
    render: (r) => (
      <span title={`${present(r.insurerName)} · ${present(r.productName)}`}>
        {present(r.insurerName)} · {present(r.productName)}
      </span>
    ),
  },
  { key: 'agent', label: '설계사', width: '7rem', render: (r) => present(r.agentName) },
  {
    key: 'event',
    label: '발생 사유 · 기준일',
    sortKey: 'eventDate',
    sortable: true,
    width: '11rem',
    render: (r) => (
      <>
        {triggerLabels[r.triggerType ?? ''] ?? present(r.triggerType)}
        <div className="claw-cell-sub tabular-nums">
          {present(r.dateBasisLabel)} {date(r.eventDate)}
        </div>
      </>
    ),
  },
  {
    key: 'contractDate',
    label: '계약일',
    sortable: true,
    width: '6.5rem',
    cellClassName: 'tabular-nums',
    render: (r) => date(r.contractDate),
  },
  {
    key: 'paidAmount',
    label: '기지급액',
    sortable: true,
    width: '8rem',
    align: 'number',
    render: (r) => <Amount value={r.paidAmount} />,
  },
  {
    key: 'unpaidAmount',
    label: '미지급액',
    sortable: true,
    width: '8rem',
    align: 'number',
    render: (r) => <Amount value={r.unpaidAmount} />,
  },
  { key: 'status', label: '계산 여부', width: '6.5rem', align: 'center', render: (r) => <CalculationBadge row={r} /> },
  {
    key: 'calculatedAmount',
    label: '환수 계산액',
    width: '8rem',
    align: 'number',
    render: (r) => <CalculatedAmount row={r} />,
  },
]
