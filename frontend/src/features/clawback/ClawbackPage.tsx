import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useOutletContext, useSearchParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { ResizableSplit } from '../../components/ResizableSplit'
import { int, today } from '../../lib/format'
import { sortBinding } from '../../lib/tableSort'
import { useAgentOptions, useInsurerOptions, useProductOptions } from '../base/api'
import { QueryState } from '../reference/ReferenceUI'
import {
  CLAWBACK_SORT_KEYS,
  candidateDetailQuery,
  candidateListQuery,
  criteriaFromParams,
  defaultCriteria,
} from './api'
import type { CandidateRow, Criteria } from './api'
import { ClawbackDetail } from './ClawbackDetail'
import { columns } from './columns'
import { CalculationBadge } from './badges'
import { calculationStatuses, triggerLabels } from './labels'
type Draft = Omit<Criteria, 'sort' | 'page'>
const draftOf = (criteria: Criteria): Draft => {
  const { sort, page, ...rest } = criteria
  void sort
  void page
  return rest
}
export default function ClawbackPage() {
  const { month } = useOutletContext<ShellContext>()
  const [params, setParams] = useSearchParams()
  const criteria = criteriaFromParams(params, month ?? '')
  const source = JSON.stringify(criteria)
  // 조회 조건은 [후보 조회]를 눌러야 URL(=서버 요청)에 반영된다. URL이 바뀌면 입력 중 값도 새 조건으로 맞춘다.
  const [draftState, setDraftState] = useState<{ source: string; value: Draft } | null>(null)
  const draft = draftState?.source === source ? draftState.value : draftOf(criteria)
  const query = useQuery(candidateListQuery(criteria))
  const asOf = today()
  const insurers = useInsurerOptions()
  const products = useProductOptions(asOf, draft.insurerId, true, true, true)
  // 전체 설계사 이력은 많을 수 있어 선택 목록을 열거나 URL에 설계사 조건이 있을 때만 조회한다.
  const [agentsRequested, setAgentsRequested] = useState(Boolean(criteria.agentId))
  const agents = useAgentOptions(asOf, '', agentsRequested, true, true)
  const [selection, setSelection] = useState<{ source: string; id: number } | null>(null)
  const rows = query.data?.content ?? []
  const selected = rows.find((row) => selection?.source === source && row.candidateId === selection.id) ?? rows[0]
  const detail = useQuery({ ...candidateDetailQuery(selected?.candidateId), enabled: selected?.candidateId != null })
  function set<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraftState({
      source,
      value: { ...draft, [key]: value, ...(key === 'insurerId' ? { productOfferingId: '' } : {}) },
    })
  }
  function apply(values: Partial<Criteria>) {
    setSelection(null)
    setDraftState(null)
    setParams(() => {
      const next = new URLSearchParams()
      Object.entries({ ...criteria, ...values }).forEach(([key, value]) => {
        // 기간은 비운 값도 남겨 '제한 없음'을 기본값(작업월)과 구분한다.
        const keepEmpty = key === 'dateFrom' || key === 'dateTo'
        if ((value !== '' || keepEmpty) && !(key === 'page' && value === 1)) next.set(key, String(value))
      })
      return next
    })
  }
  const busy = query.isLoading || query.isFetching
  const dateError =
    draft.dateFrom && draft.dateTo && draft.dateFrom > draft.dateTo ? '종료일이 시작일보다 빠릅니다.' : ''
  return (
    <div className="claw-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">환수 검토·계산</h1>
        </div>
      </header>
      <FilterBar
        className="claw-filter-bar"
        fieldsClassName="claw-filter-fields"
        icons
        onSubmit={() => {
          if (!dateError) apply({ ...draft, contractNo: draft.contractNo.trim(), page: 1 })
        }}
        onReset={() => apply(defaultCriteria(month ?? ''))}
        actions={
          <Button variant="secondary" disabled={busy} onClick={() => void query.refetch()}>
            <span className="material-symbols-rounded" aria-hidden="true">
              refresh
            </span>
            새로고침
          </Button>
        }
      >
        <Field variant="filter" className="claw-filter-basis" label="기간 기준">
          <select
            className="select-control"
            value={draft.dateBasis}
            onChange={(e) => set('dateBasis', e.target.value as Draft['dateBasis'])}
          >
            <option value="EVENT_DATE">발생기준일</option>
            <option value="CONTRACT_DATE">계약일</option>
          </select>
        </Field>
        <Field
          variant="filter"
          className="claw-filter-date"
          label="시작일"
          helper={!draft.dateFrom && !draft.dateTo ? '비우면 기간 제한 없음' : undefined}
        >
          <input type="date" value={draft.dateFrom} onChange={(e) => set('dateFrom', e.target.value)} />
        </Field>
        <Field variant="filter" className="claw-filter-date" label="종료일" error={dateError || undefined}>
          <input
            type="date"
            value={draft.dateTo}
            aria-invalid={Boolean(dateError)}
            onChange={(e) => set('dateTo', e.target.value)}
          />
        </Field>
        <Field variant="filter" className="claw-filter-contract" label="계약번호">
          <input
            type="search"
            placeholder="계약번호 일부"
            maxLength={80}
            value={draft.contractNo}
            onChange={(e) => set('contractNo', e.target.value)}
          />
        </Field>
        <Field variant="filter" className="claw-filter-insurer" label="보험회사">
          <select
            className="select-control"
            value={draft.insurerId}
            disabled={insurers.isLoading || Boolean(insurers.error)}
            onChange={(e) => set('insurerId', e.target.value)}
          >
            <option value="">{insurers.error ? '보험회사 조회 실패' : '전체'}</option>
            {insurers.data?.map((row) => (
              <option key={row.insurerId} value={row.insurerId}>
                {row.insurerCode} · {row.insurerName}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" className="claw-filter-product" label="상품(판매버전)">
          <select
            className="select-control"
            value={draft.productOfferingId}
            disabled={!draft.insurerId || products.isLoading || Boolean(products.error)}
            onChange={(e) => set('productOfferingId', e.target.value)}
          >
            <option value="">
              {!draft.insurerId ? '보험회사 먼저 선택' : products.error ? '상품 조회 실패' : '전체'}
            </option>
            {products.data?.map((row) => (
              <option key={row.productOfferingId} value={row.productOfferingId}>
                {row.productName} · {row.offeringVersion}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" className="claw-filter-agent" label="설계사">
          <select
            className="select-control"
            value={draft.agentId}
            disabled={Boolean(agents.error)}
            onFocus={() => setAgentsRequested(true)}
            onMouseDown={() => setAgentsRequested(true)}
            onChange={(e) => set('agentId', e.target.value)}
          >
            <option value="">{agents.error ? '설계사 조회 실패' : agents.isLoading ? '불러오는 중…' : '전체'}</option>
            {/* 목록을 아직 받지 않았어도 URL에서 온 선택값은 유지한다. */}
            {!agents.data && draft.agentId ? <option value={draft.agentId}>설계사 #{draft.agentId}</option> : null}
            {agents.data?.map((row) => (
              <option key={row.agentId} value={row.agentId}>
                {row.agentName} · {row.agentCode}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" className="claw-filter-trigger" label="발생 사유">
          <select
            className="select-control"
            value={draft.triggerType}
            onChange={(e) => set('triggerType', e.target.value)}
          >
            <option value="">전체</option>
            {Object.entries(triggerLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field variant="filter" className="claw-filter-status" label="계산 여부">
          <select
            className="select-control"
            value={draft.calculationStatus}
            onChange={(e) => set('calculationStatus', e.target.value)}
          >
            <option value="">전체</option>
            {calculationStatuses.map((status) => (
              <option key={status.value} value={status.value}>
                {status.label}
              </option>
            ))}
          </select>
        </Field>
      </FilterBar>
      <QueryState error={insurers.error} />
      <ResizableSplit
        className="claw-content-grid"
        storageKey="clawback-workspace"
        label="환수 검토 후보 목록과 상세 너비"
        defaultRatio={58}
      >
        <section className="surface claw-list-panel" aria-labelledby="claw-list-title">
          <header className="surface-header claw-panel-header">
            <h2 className="surface-title" id="claw-list-title">
              ② 환수 검토 후보
            </h2>
            <span className="claw-result-count">
              <span className="tabular-nums">{int(query.data?.totalElements ?? 0)}</span>건
            </span>
          </header>
          {busy || query.error ? (
            <div className="empty-state">
              <QueryState loading={busy && !query.error} error={query.error} />
            </div>
          ) : rows.length === 0 ? (
            <p className="empty-state">조건에 맞는 환수 검토 대상이 없습니다.</p>
          ) : (
            <DataTable
              caption="검색 조건에 해당하는 환수 검토 후보"
              className="claw-list-table"
              columns={columns}
              {...sortBinding(criteria.sort, CLAWBACK_SORT_KEYS, (sort) => apply({ sort, page: 1 }))}
              rows={rows}
              rowKey={(row) => String(row.candidateId)}
              selectedKeys={selected ? [String(selected.candidateId)] : []}
              onRowActivate={(row: CandidateRow) => {
                if (row.candidateId != null) setSelection({ source, id: row.candidateId })
              }}
              rowClassName={() => 'claw-result-row'}
              rowLabel={(row) => `후보 ${row.candidateId} 상세 보기`}
            />
          )}
          {!query.error && (
            <ListPagination
              className="claw-pagination-footer"
              label="환수 검토 후보 페이지"
              page={query.data?.page ?? criteria.page}
              totalPages={query.data?.totalPages ?? 0}
              onPageChange={(page) => apply({ page })}
            />
          )}
        </section>
        <section className="surface claw-detail-panel" aria-labelledby="claw-detail-title">
          <header className="surface-header claw-panel-header">
            <h2 className="surface-title" id="claw-detail-title">
              ③ 후보 근거 · ④ 계산
            </h2>
            {selected && !query.error ? <CalculationBadge row={selected} /> : null}
          </header>
          <div className="surface-body claw-detail-body">
            {!selected || query.error || busy ? (
              <p className="claw-detail-placeholder">왼쪽 목록에서 후보 한 건을 고르세요.</p>
            ) : detail.isLoading || detail.error ? (
              <div className="empty-state">
                <QueryState loading={detail.isLoading} error={detail.error} />
                {detail.error ? (
                  <Button variant="secondary" onClick={() => void detail.refetch()}>
                    다시 조회
                  </Button>
                ) : null}
              </div>
            ) : detail.data ? (
              <ClawbackDetail detail={detail.data} />
            ) : null}
          </div>
        </section>
      </ResizableSplit>
    </div>
  )
}
