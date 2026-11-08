import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { DataTable, type TableColumn } from '../../components/DataTable'
import { CategoryBadge } from '../../components/StatusBadge'
import { date, int, isNegative, rate, won } from '../../lib/format'
import type { TableSort } from '../../lib/tableSort'
import type { ScheduleLine } from './api'
import { ScheduleStatusBadge } from './badges'
import { CALCULATION_TYPE, EMPTY, LINE_STATUS, responseLabel } from './labels'

export function ScheduleLinesTable({ lines, total, emptyMessage, paymentHref }: {
  lines: ScheduleLine[]
  total?: number
  emptyMessage: ReactNode
  paymentHref: (line: ScheduleLine) => string | undefined
}) {
  const [sort, setSort] = useState<TableSort>({ key: 'contractMonthNo', direction: 'desc' })
  // Read the premium from this version's snapshot, never from the contract's latest edited value.
  const monthlyPremium = lines.find(line => line.basisCode === 'MONTHLY_EQUIVALENT_FIRST_PREMIUM' && line.basisAmount != null)?.basisAmount
  const amount = (value?: number) => value == null ? '자료 없음' : <span className={isNegative(value) ? 'is-negative-amount' : undefined}>{won(value)}</span>
  const columns: TableColumn<ScheduleLine>[] = [
    { key: 'lineNo', label: '줄', width: '4rem', sortable: true, render: row => int(row.lineNo) },
    { key: 'installmentNo', label: '회차', width: '5rem', sortable: true, render: row => int(row.installmentNo) },
    { key: 'expectedAmount', label: '예상금액', width: '9rem', sortable: true, align: 'number', render: row => amount(row.expectedAmount) },
    { key: 'contractMonthNo', label: '계약차월', width: '7rem', sortable: true, render: row => int(row.contractMonthNo) },
    { key: 'dueDate', label: '지급예정일', width: '8rem', sortable: true, render: row => date(row.dueDate) },
    { key: 'commissionItemName', label: '수수료 항목', width: '10rem', sortable: true, render: row => row.commissionItemName ?? EMPTY },
    { key: 'recipientName', label: '수령자', width: '7rem', sortable: true, render: row => row.recipientName ?? EMPTY },
    { key: 'monthlyPremium', label: '월납보험료', width: '9rem', sortable: true, align: 'number', render: () => amount(monthlyPremium) },
    { key: 'calculationType', label: '계산방식', width: '7rem', sortable: true, render: row => <CategoryBadge value={row.calculationType} optionCount={2}>
      {responseLabel(null, CALCULATION_TYPE, row.calculationType)}
    </CategoryBadge> },
    { key: 'rate', label: '요율', width: '8rem', sortable: true, align: 'number', render: row => row.calculationType === 'FIXED'
      ? <span title="정액 계산 금액">정액 {amount(row.fixedAmount)}</span> : row.ratePct == null ? EMPTY : `${rate(row.ratePct)}%` },
    { key: 'lineStatus', label: '상태', width: '7rem', sortable: true, align: 'center', render: row => <>
      <ScheduleStatusBadge code={row.lineStatus} label={responseLabel(null, LINE_STATUS, row.lineStatus)} />
      {row.sourceStatusEventId != null && <small className="schedule-source-event">상태 변경 있음</small>}
    </> },
    { key: 'ruleRef', label: '적용 규칙 번호', width: '9rem', sortable: true, render: row => row.ruleRef == null ? EMPTY : int(row.ruleRef) },
    { key: 'action', label: '지급 등록', width: '8rem', render: row => {
      const href = paymentHref(row)
      return href && <Link data-guide-feature="features.schedule.schedule-detail-page.link-2" className="button button-ghost" to={href} aria-label={`${row.lineNo}번 회차 지급 등록`}>지급 등록</Link>
    } },
  ]
  const sortValue = (row: ScheduleLine): string | number | undefined => {
    if (sort.key === 'monthlyPremium') return monthlyPremium
    if (sort.key === 'rate') return row.calculationType === 'FIXED' ? row.fixedAmount : row.ratePct
    if (sort.key === 'calculationType') return responseLabel(null, CALCULATION_TYPE, row.calculationType)
    if (sort.key === 'lineStatus') return responseLabel(null, LINE_STATUS, row.lineStatus)
    const result = row[sort.key as keyof ScheduleLine]
    return result == null ? undefined : result
  }
  const sorted = [...lines].sort((a, b) => {
    const left = sortValue(a), right = sortValue(b)
    if (left == null || right == null) return left == null ? right == null ? 0 : 1 : -1
    const compared = typeof left === 'number' && typeof right === 'number' ? left - right : String(left).localeCompare(String(right), 'ko', { numeric: true })
    return compared ? compared * (sort.direction === 'asc' ? 1 : -1) : (a.lineNo ?? 0) - (b.lineNo ?? 0)
  })
  return <div data-guide-feature="features.schedule.schedule-detail-page.table">
    <DataTable caption="회차별 예상 지급 금액" resizeKey="schedule-detail-lines" className="schedule-line-table"
      viewportClassName="schedule-line-viewport" columns={columns} rows={sorted}
      rowKey={row => String(row.scheduleLineId ?? row.lineNo)} sort={sort}
      onSort={next => setSort({ ...next, direction: next.key === sort.key ? next.direction : 'desc' })}
      emptyMessage={emptyMessage}
      footer={<tr><td colSpan={2}>합계 ({int(lines.length)}줄)</td><td className="is-number tabular-nums">{amount(total)}</td><td colSpan={10} /></tr>}
    />
  </div>
}
