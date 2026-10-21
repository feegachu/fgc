import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useOutletContext, useSearchParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { StatusBadge } from '../../components/StatusBadge'
import { int, isNegative, month as formatMonth, won } from '../../lib/format'
import { withMonth } from '../../stores/workspace'
import { useInsurers } from '../base/api'
import type { Insurer } from '../base/api'
import { filterParams, listError, readFilters, sourceOptions, statusOptions, transactionListQuery } from './list-api'
import type { PaymentFilters, PaymentRow, TextFilter } from './list-api'
import './transaction.css'

type KnownOptions = { agents: Record<string, string>; items: Record<string, string> }
const disclosure = (value: string | undefined) => <CellDisclosure singleLine preview={value || '-'} />

export default function TransactionListPage() {
  const { month, user } = useOutletContext<ShellContext>()
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const search = useQuery(transactionListQuery(filters))
  // TRAN-W01 offers the first 100 insurers and learns agent/item options from visited result pages.
  const insurers = useInsurers({ page: 1, size: 100 })
  const [known, setKnown] = useState<KnownOptions>({ agents: {}, items: {} })
  const [previousRows, setPreviousRows] = useState<PaymentRow[]>()
  if (search.data?.content && search.data.content !== previousRows) {
    setPreviousRows(search.data.content)
    const next = { agents: { ...known.agents }, items: { ...known.items } }
    for (const row of search.data.content) {
      if (row.recipientAgentId != null && row.recipientAgentName)
        next.agents[row.recipientAgentId] = row.recipientAgentName
      if (row.commissionItemId != null && row.commissionItemName)
        next.items[row.commissionItemId] = row.commissionItemName
    }
    setKnown(next)
  }
  const rows = search.isError ? [] : (search.data?.content ?? [])
  const sum = rows.reduce((total, row) => total + Number(row.amount ?? 0), 0)
  const total = search.isError ? 0 : (search.data?.totalElements ?? 0)
  const totalPages = Math.max(1, search.data?.totalPages ?? 1)
  const page = search.data?.page ?? filters.page
  const path = (id: number | undefined) =>
    month ? withMonth(`/transactions/new?id=${id}`, month) : `/transactions/new?id=${id}`
  function apply(next: PaymentFilters) {
    const query = filterParams(next)
    const unchanged = query.toString() === filterParams(filters).toString()
    // An explicitly cleared month must remain blank after submitting/restoring the URL.
    if (!next.settlementMonth) query.set('settlementMonth', '')
    if (month) query.set('month', month)
    setParams(query, { replace: true })
    if (unchanged) void search.refetch()
  }
  const columns: TableColumn<PaymentRow>[] = [
    { key: 'stage', label: '지급단계', width: '6.5rem', render: (row) => row.paymentStageLabel || row.paymentStage },
    {
      key: 'source',
      label: '원천유형',
      width: '7rem',
      render: (row) => disclosure(row.sourceTypeLabel || row.sourceType),
    },
    {
      key: 'key',
      label: '업무키',
      width: '12rem',
      cellClassName: 'transaction-mono',
      render: (row) => <span title={row.sourceBusinessKey}>{row.sourceBusinessKey || '-'}</span>,
    },
    { key: 'month', label: '정산월', width: '5.5rem', render: (row) => formatMonth(row.settlementMonth) },
    { key: 'agent', label: '수령 설계사', width: '9rem', render: (row) => disclosure(row.recipientAgentName) },
    {
      key: 'item',
      label: '수수료 항목',
      width: '8.5rem',
      render: (row) => disclosure(row.commissionItemName || row.commissionItemCode),
    },
    {
      key: 'amount',
      label: '금액',
      width: '7rem',
      align: 'number',
      render: (row) => (
        <span className={isNegative(row.amount) ? 'is-negative-amount' : undefined}>{won(row.amount)}</span>
      ),
    },
    {
      key: 'flow',
      label: '흐름',
      width: '5rem',
      align: 'center',
      render: (row) => (
        <StatusBadge
          tone={row.status === 'CANCELLED' ? 'neutral' : row.cashflowType === 'DEDUCTION' ? 'warning' : 'info'}
        >
          {row.status === 'CANCELLED' ? '취소' : row.cashflowTypeLabel || row.cashflowType}
        </StatusBadge>
      ),
    },
    {
      key: 'status',
      label: '상태',
      width: '5.5rem',
      align: 'center',
      render: (row) => (
        <StatusBadge tone={row.status === 'CONFIRMED' ? 'success' : row.status === 'CANCELLED' ? 'error' : 'neutral'}>
          {row.statusLabel || row.status}
        </StatusBadge>
      ),
    },
    {
      key: 'attributions',
      label: '귀속',
      width: '6.5rem',
      align: 'center',
      render: (row) => {
        const count = row.attributionCount ?? 0
        const imbalance = count === 0 || Number(row.differenceAmount ?? 0) !== 0
        return (
          <StatusBadge tone={imbalance ? 'error' : 'success'}>
            {count}건 · {imbalance ? '확인필요' : '일치'}
          </StatusBadge>
        )
      },
    },
    {
      key: 'action',
      label: <span className="visually-hidden">행 작업</span>,
      width: '5rem',
      render: (row) =>
        row.status !== 'DRAFT' ? (
          '-'
        ) : user.canProcess ? (
          <Link
            className="button button-ghost"
            to={path(row.commissionTransactionId)}
            aria-label={`${row.sourceBusinessKey || '선택한 지급 건'} 수정`}
          >
            수정
          </Link>
        ) : (
          <Button
            variant="ghost"
            disabled
            aria-label={`${row.sourceBusinessKey || '선택한 지급 건'} 수정 (조회 전용 권한)`}
          >
            수정
          </Button>
        ),
    },
  ]
  return (
    <div className="transaction-page transaction-list-page">
      <header className="page-header transaction-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">수수료 지급 건 목록</h1>
        </div>
        <div className="page-header-actions">
          {user.canProcess && (
            <Link
              className="button button-primary"
              to={month ? withMonth('/transactions/new', month) : '/transactions/new'}
            >
              <span className="material-symbols-rounded" aria-hidden="true">
                add
              </span>
              신규 등록
            </Link>
          )}
        </div>
      </header>
      <TransactionFilters
        key={`${params.toString()}|${month}`}
        initial={{ ...filters, settlementMonth: params.has('settlementMonth') ? filters.settlementMonth : month || '' }}
        known={known}
        insurers={insurers.data?.content ?? []}
        insurersLoading={insurers.isPending}
        onSearch={(next) => apply({ ...next, contractNo: next.contractNo.trim(), page: 1 })}
        onReset={() => apply({ ...readFilters(new URLSearchParams()), settlementMonth: month || '' })}
      />
      {insurers.isError && (
        <p className="transaction-filter-status field-error" role="alert">
          {listError(insurers.error)}
        </p>
      )}
      <section
        className="surface transaction-list-panel"
        aria-labelledby="transaction-list-title"
        aria-busy={search.isFetching}
      >
        <header className="surface-header transaction-surface-header">
          <h2 className="surface-title" id="transaction-list-title">
            지급 건
          </h2>
          <span className="transaction-result-count">
            <span className="tabular-nums" id="row-count">
              {int(total)}
            </span>
            건 · 한 화면 20행
          </span>
        </header>
        <DataTable
          className="transaction-list-table"
          viewportClassName="transaction-table-viewport"
          bodyId="list-body"
          caption="검색 조건에 해당하는 수수료 지급 건"
          columns={columns}
          rows={rows}
          rowKey={(row) => String(row.commissionTransactionId)}
          emptyMessage={
            <div
              className={`transaction-state transaction-state-${search.isError ? 'error' : search.isPending ? 'loading' : 'empty'}`}
              role={search.isError ? 'alert' : 'status'}
            >
              <strong className="transaction-state-title">
                {search.isError
                  ? '목록을 불러오지 못했습니다'
                  : search.isPending
                    ? '불러오는 중'
                    : '조회 결과가 없습니다'}
              </strong>
              <span className="transaction-state-copy">
                {search.isError
                  ? listError(search.error)
                  : search.isPending
                    ? '수수료 지급 건을 불러오는 중입니다.'
                    : '검색 조건을 바꾸어 다시 조회하세요.'}
              </span>
              {search.isError && (
                <Button variant="secondary" onClick={() => void search.refetch()}>
                  다시 시도
                </Button>
              )}
            </div>
          }
          footer={
            <tr>
              <td colSpan={6}>금액 절대값 합계</td>
              <td className="is-number">
                <span id="sum-amount" className={`tabular-nums ${isNegative(sum) ? 'is-negative-amount' : ''}`}>
                  {won(sum)}
                </span>
              </td>
              <td colSpan={4} />
            </tr>
          }
        />
        {total > 0 && (
          <ListPagination
            className="transaction-pagination"
            label="수수료 지급 건 목록 페이지 선택"
            page={page}
            totalPages={totalPages}
            onPageChange={(next) => apply({ ...filters, page: next })}
          />
        )}
      </section>
    </div>
  )
}

function TransactionFilters({
  initial,
  known,
  insurers,
  insurersLoading,
  onSearch,
  onReset,
}: {
  initial: PaymentFilters
  known: KnownOptions
  insurers: Insurer[]
  insurersLoading: boolean
  onSearch: (filters: PaymentFilters) => void
  onReset: () => void
}) {
  const [draft, setDraft] = useState(initial)
  const set = (key: TextFilter, value: string) => setDraft((previous) => ({ ...previous, [key]: value }))
  const options = (values: readonly (readonly [string, string])[], selected: string) => (
    <>
      <option value="">전체</option>
      {selected && !values.some(([id]) => id === selected) && <option value={selected}>{selected}</option>}
      {values.map(([id, label]) => (
        <option key={id} value={id}>
          {label}
        </option>
      ))}
    </>
  )
  const remembered = (values: Record<string, string>) =>
    Object.entries(values).sort((a, b) => a[1].localeCompare(b[1], 'ko'))
  const select = (
    key: TextFilter,
    id: string,
    label: string,
    className: string,
    entries: readonly (readonly [string, string])[],
  ) => (
    <Field label={label} className={`filter-field ${className}`}>
      <select
        id={id}
        className="select-control"
        value={draft[key]}
        onChange={(event) => set(key, event.target.value)}
        disabled={key === 'insurerId' && insurersLoading}
      >
        {options(entries, draft[key])}
      </select>
    </Field>
  )
  return (
    <FilterBar
      className="transaction-filter-bar"
      wrapFields={false}
      icons
      resetFirst
      onSubmit={() => onSearch(draft)}
      onReset={onReset}
    >
      <div className="filter-fields transaction-filter-fields">
        <Field label="정산월" className="filter-field transaction-filter-month">
          <input
            id="f-month"
            type="month"
            value={draft.settlementMonth}
            onChange={(event) => set('settlementMonth', event.target.value)}
          />
        </Field>
        {select('paymentStage', 'f-stage', '지급단계', 'transaction-filter-stage', [
          ['GA_TO_FC', 'GA→설계사'],
          ['INSURER_TO_GA', '원수사→GA'],
        ])}
        {select(
          'insurerId',
          'f-insurer',
          '보험회사',
          'transaction-filter-insurer',
          insurers.map((row) => [String(row.insurerId), `${row.insurerCode} · ${row.insurerName}`]),
        )}
        <Field label="계약번호" className="filter-field transaction-filter-contract">
          <input
            id="f-contract-no"
            type="search"
            placeholder="계약번호 입력"
            autoComplete="off"
            value={draft.contractNo}
            onChange={(event) => set('contractNo', event.target.value)}
          />
        </Field>
        {select('sourceType', 'f-source', '원천유형', 'transaction-filter-source', sourceOptions)}
        {select('agentId', 'f-agent', '수령 설계사', 'transaction-filter-agent', remembered(known.agents))}
        {select('commissionItemId', 'f-item', '수수료 항목', 'transaction-filter-item', remembered(known.items))}
        {select('status', 'f-status', '상태', 'transaction-filter-status-field', statusOptions)}
        <label className="transaction-filter-checkbox">
          <input
            id="f-noattr"
            type="checkbox"
            checked={draft.noAttributionOnly}
            onChange={(event) => setDraft((previous) => ({ ...previous, noAttributionOnly: event.target.checked }))}
          />
          <span>귀속 0건만</span>
        </label>
      </div>
    </FilterBar>
  )
}
