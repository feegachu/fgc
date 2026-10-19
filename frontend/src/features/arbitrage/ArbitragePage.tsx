import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useOutletContext, useSearchParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { int } from '../../lib/format'
import { useInsurerOptions } from '../base/api'
import { QueryState } from '../reference/ReferenceUI'
import { arbitrageListQuery, criteriaFromParams } from './api'
import type { Criteria, ArbitrageRow } from './api'
import { columns } from './columns'
import { ArbitrageBadge } from './badges'
import { ArbitrageDetail } from './ArbitrageDetail'
import { RecheckModal } from './RecheckModal'
const summaryCards = [
  {
    status: 'CLEAR',
    label: '이상없음',
    tone: 'kpi-card-success',
    count: 'clearCount',
    description: '초과액이 없습니다',
  },
  {
    status: 'CANDIDATE',
    label: '검토대상',
    tone: 'kpi-card-warning',
    count: 'candidateCount',
    description: '사유 확인이 필요합니다',
  },
  {
    status: 'REVIEW_REQUIRED',
    label: '자료부족',
    tone: 'arb-summary-review',
    count: 'reviewRequiredCount',
    description: '자료 보완 후 재확인합니다',
  },
] as const
export default function ArbitragePage() {
  const { month, user } = useOutletContext<ShellContext>()
  const [params, setParams] = useSearchParams()
  const criteria = criteriaFromParams(params, month ?? '')
  const query = useQuery({ ...arbitrageListQuery(criteria), enabled: Boolean(criteria.month) })
  const insurers = useInsurerOptions()
  const [selection, setSelection] = useState<{ source: string; id: number } | null>(null)
  const source = JSON.stringify(criteria)
  const rows = query.data?.items?.content ?? []
  const selected = rows.find((row) => selection?.source === source && row.arbitrageCheckId === selection.id) ?? rows[0]
  const [modalRow, setModalRow] = useState<ArbitrageRow | null>(null)
  // Keep an unsubmitted contract-number draft only for the current URL value.
  const [draft, setDraft] = useState<{ source: string; value: string } | null>(null)
  const contractNo = draft?.source === criteria.contractNo ? draft.value : criteria.contractNo
  function patch(values: Partial<Criteria>, resetPage = true) {
    setSelection(null)
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (resetPage) next.delete('page')
      Object.entries({ contractNo: contractNo.trim(), ...values }).forEach(([key, value]) => {
        if (value === '' && key !== 'stage') next.delete(key)
        else next.set(key, String(value))
      })
      return next
    })
  }
  function select(row: ArbitrageRow) {
    if (row.arbitrageCheckId != null) setSelection({ source, id: row.arbitrageCheckId })
  }
  function reset() {
    setDraft(null)
    patch({ month: month ?? '', status: '', stage: 'GA_TO_FC', insurerId: '', contractNo: '' })
  }
  const busy = query.isLoading || query.isFetching
  const canRecheck = user.canProcess && selected?.paymentStage === 'GA_TO_FC'
  return (
    <div className="arb-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">차익거래 검증</h1>
        </div>
      </header>
      <FilterBar
        className="arb-filter-bar"
        fieldsClassName="arb-filter-fields"
        actionsClassName="arb-filter-actions"
        showSubmit={false}
        icons
        onSubmit={() => patch({ contractNo })}
        onReset={reset}
      >
        <Field variant="filter" className="arb-filter-month" label="정산월">
          <input type="month" value={criteria.month} onChange={(e) => patch({ month: e.target.value })} />
        </Field>
        <Field variant="filter" className="arb-filter-status" label="판정">
          <select
            className="select-control"
            value={criteria.status}
            onChange={(e) => patch({ status: e.target.value })}
          >
            <option value="">전체</option>
            {summaryCards.map((card) => (
              <option key={card.status} value={card.status}>
                {card.label}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" className="arb-filter-stage" label="지급단계">
          <select className="select-control" value={criteria.stage} onChange={(e) => patch({ stage: e.target.value })}>
            <option value="GA_TO_FC">GA→설계사</option>
            <option value="">전체</option>
            <option value="INSURER_TO_GA">원수사→GA</option>
          </select>
        </Field>
        <Field variant="filter" className="arb-filter-insurer" label="보험회사">
          <select
            className="select-control"
            value={criteria.insurerId}
            disabled={insurers.isLoading || Boolean(insurers.error)}
            onChange={(e) => patch({ insurerId: e.target.value })}
          >
            <option value="">{insurers.error ? '보험회사 조회 실패' : '전체'}</option>
            {insurers.data?.map((row) => (
              <option key={row.insurerId} value={row.insurerId} disabled={row.activeYn === false}>
                {row.insurerCode} · {row.insurerName}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" className="arb-filter-contract" label="계약번호">
          <input
            type="search"
            placeholder="계약번호 입력"
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                patch({ contractNo })
              }
            }}
            value={contractNo}
            onChange={(e) => setDraft({ source: criteria.contractNo, value: e.target.value })}
          />
        </Field>
      </FilterBar>
      <QueryState error={insurers.error} />
      <section className="kpi-grid arb-summary-grid" aria-label="차익거래 판정 요약" aria-live="polite">
        {summaryCards.map((card) => (
          <button
            type="button"
            key={card.status}
            className={`kpi-card arb-summary-card ${card.tone}`}
            aria-pressed={criteria.status === card.status}
            onClick={() => patch({ status: card.status })}
          >
            <span className="kpi-card-header">
              <span className="kpi-label">{card.label}</span>
            </span>
            <span className="kpi-value-row">
              <strong className="kpi-value">
                {query.data ? `${int(query.data.summary?.[card.count] ?? 0)}건` : '—'}
              </strong>
            </span>
            <span className="kpi-card-footer">
              <span className="kpi-comparison">{card.description}</span>
            </span>
          </button>
        ))}
      </section>
      <div className="arb-content-grid">
        <section className="surface arb-list-panel" aria-labelledby="arb-list-title">
          <header className="surface-header arb-panel-header">
            <h2 className="surface-title" id="arb-list-title">
              ③ 계약별 판정
            </h2>
            <span className="arb-result-count">
              <span className="tabular-nums">{int(query.data?.items?.totalElements ?? 0)}</span>건
            </span>
          </header>
          {busy || query.error ? (
            <div className="empty-state">
              <QueryState loading={busy && !query.error} error={query.error} />
            </div>
          ) : (
            <DataTable
              caption="검색 조건에 해당하는 계약별 차익거래 판정"
              className="arb-list-table"
              columns={columns}
              rows={rows}
              rowKey={(row) => String(row.arbitrageCheckId)}
              selectedKeys={selected ? [String(selected.arbitrageCheckId)] : []}
              onRowActivate={select}
              rowClassName={() => 'arb-result-row'}
              rowLabel={(row) => `계약 ${row.contractNo} 상세 보기`}
            />
          )}
          <footer className="arb-pagination-footer">
            {(query.data?.items?.totalPages ?? 0) > 1 && !query.error ? (
              <Pagination
                legacy
                label="차익거래 검증 목록 페이지"
                page={query.data?.items?.page ?? criteria.page}
                totalPages={query.data?.items?.totalPages ?? 0}
                onPageChange={(page) => patch({ page }, false)}
              />
            ) : null}
          </footer>
        </section>
        <section className="surface arb-detail-panel" aria-labelledby="arb-detail-title">
          <header className="surface-header arb-panel-header">
            <h2 className="surface-title" id="arb-detail-title">
              ④ 상세
            </h2>
            {selected && !query.error ? <ArbitrageBadge row={selected} /> : null}
          </header>
          <div className="surface-body arb-detail-body">
            {selected && !query.error && !busy ? (
              <ArbitrageDetail row={selected} />
            ) : (
              <p className="arb-detail-placeholder">왼쪽 목록에서 한 건을 고르세요.</p>
            )}
          </div>
          <div className="arb-detail-actions">
            <Button
              variant="secondary"
              disabled={!canRecheck || !selected?.contractId || busy || Boolean(query.error)}
              onClick={() => {
                if (selected && canRecheck) setModalRow(selected)
              }}
            >
              <span className="material-symbols-rounded arb-recheck-icon" aria-hidden="true">
                refresh
              </span>
              이 계약만 다시 검증
            </Button>
            {user.canProcess && selected?.paymentStage === 'INSURER_TO_GA' ? (
              <p className="field-helper">단건 재검증은 GA→설계사 단계만 지원합니다.</p>
            ) : null}
            {!user.canProcess ? (
              <p className="field-helper">재검증은 처리 권한이 있는 계정만 실행할 수 있습니다.</p>
            ) : null}
          </div>
        </section>
      </div>
      {modalRow ? (
        <RecheckModal row={modalRow} onClose={() => setModalRow(null)} onSuccess={() => setSelection(null)} />
      ) : null}
    </div>
  )
}
