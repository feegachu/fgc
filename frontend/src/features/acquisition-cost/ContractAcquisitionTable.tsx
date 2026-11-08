import { useQuery } from '@tanstack/react-query'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { dateTime } from '../../lib/format'
import { PAYMENT_STAGE } from '../schedule/labels'
import { AcquisitionFailure, AcquisitionStatus } from './AcquisitionCostResult'
import { contractAcquisitionQuery } from './api'
import type { AcquisitionCheck } from './api'
import { amountText } from './labels'

const columns: TableColumn<AcquisitionCheck>[] = [
  { key: 'checkedAt', label: '검증일시', width: '10rem', render: row => dateTime(row.checkedAt) },
  { key: 'stage', label: '지급단계', width: '8rem', render: row => row.paymentStageLabel ?? (row.paymentStage ? PAYMENT_STAGE[row.paymentStage] : '-') },
  { key: 'upfrontAmount', label: '선지급 계산액', align: 'number', render: row => amountText(row.upfront?.calculatedAmount) },
  { key: 'upfrontLimit', label: '선지급 한도', align: 'number', render: row => amountText(row.upfront?.limitAmount) },
  { key: 'upfrontStatus', label: '선지급 판정', render: row => <AcquisitionStatus status={row.upfront?.status} label={row.upfront?.statusLabel} /> },
  { key: 'maintenanceAmount', label: '유지관리 계산액', align: 'number', render: row => amountText(row.maintenance?.calculatedAmount) },
  { key: 'maintenanceLimit', label: '유지관리 한도', align: 'number', render: row => amountText(row.maintenance?.limitAmount) },
  { key: 'maintenanceStatus', label: '유지관리 판정', render: row => <AcquisitionStatus status={row.maintenance?.status} label={row.maintenance?.statusLabel} /> },
  { key: 'status', label: '종합 판정', render: row => <AcquisitionStatus status={row.resultStatus} label={row.resultStatusLabel} /> },
]

/** Read the latest result of each stage without issuing a new verification. */
export function ContractAcquisitionTable({ contractId }: { contractId: string }) {
  const insurer = useQuery(contractAcquisitionQuery(contractId, 'INSURER_TO_GA'))
  const agent = useQuery(contractAcquisitionQuery(contractId, 'GA_TO_FC'))
  const queries = [insurer, agent]
  const rows = queries.flatMap((query, index) => {
    if (query.isError) return []
    const stage = index === 0 ? 'INSURER_TO_GA' : 'GA_TO_FC'
    const latest = query.data?.content?.find(row => row.paymentStage === stage)
    return latest ? [latest] : []
  })
  return <div className="contract-acquisition-table">
    {queries.map((query, index) => query.error && <AcquisitionFailure key={index} error={query.error} retry={() => void query.refetch()} />)}
    <DataTable caption="계약체결비용 한도 검증 결과" columns={columns} rows={rows}
      rowKey={row => String(row.acquisitionCostCheckId ?? row.paymentStage)}
      emptyMessage={queries.some(query => query.isLoading) ? '검증 결과를 불러오는 중입니다.' : '조회된 계약체결비용 한도 판정이 없습니다.'} />
  </div>
}
