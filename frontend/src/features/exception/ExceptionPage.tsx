import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useOutletContext, useSearchParams } from 'react-router'
import { DataTable } from '../../components/DataTable'
import { ResizableSplit } from '../../components/ResizableSplit'
import type { TableColumn } from '../../components/DataTable'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { StatusBadge } from '../../components/StatusBadge'
import { dateTime, errorText, int, rateText, usageRate } from '../../lib/format'
import { formatSort, parseSort, sortBinding } from '../../lib/tableSort'
import type { ShellContext } from '../../app/shell/AppShell'
import { withMonth } from '../../stores/workspace'
import { ActionPanel } from './ActionPanel'
import { Disclosure } from './Disclosure'
import {
  ALL_STATUS,
  EXCEPTION_SORT_KEYS,
  exceptionOptionsQueryOptions,
  exceptionSearchQueryOptions,
  OPEN_STATUS,
} from './api'
import type { ExceptionCase, ExceptionFilters, ExceptionOptions } from './api'
import { severityTone, statusOptions, statusTone, typeTone } from './rules'
import { exceptionSummaryDescription } from './labels'

const EMPTY_MESSAGE = '조건에 맞는 자료가 없습니다.'
const cardTone = (type: string) => {
  const tone = typeTone(type)
  return tone === 'review' ? 'risk' : tone
}

/** URL 이 상태의 단일 출처다 — status 가 없으면 미처리, 빈 값이면 전체(레거시 링크 호환). */
function filtersFromParams(params: URLSearchParams): ExceptionFilters {
  const status = params.get('status')
  const page = Number(params.get('page'))
  return {
    type: params.get('type') ?? '',
    reasonCode: params.get('reasonCode') ?? '',
    severity: params.get('severity') ?? '',
    status: status === null ? OPEN_STATUS : status === '' ? ALL_STATUS : status,
    assigneeFilter: params.get('assigneeFilter') ?? '',
    validationMonth: params.get('validationMonth') ?? '',
    contractNo: params.get('contractNo') ?? '',
    sort: formatSort(parseSort(params.get('sort'), EXCEPTION_SORT_KEYS)),
    page: Number.isInteger(page) && page > 0 ? page : 1,
  }
}

export function ExceptionPage() {
  const [params, setParams] = useSearchParams()
  const filters = filtersFromParams(params)
  const selectedId = params.get('selected')
  const { month } = useOutletContext<ShellContext>()
  const search = useQuery(exceptionSearchQueryOptions(filters))
  const options = useQuery(exceptionOptionsQueryOptions())
  const data = search.data
  const rows = data?.content ?? []
  const selected = rows.find((row) => String(row.exceptionCaseId) === selectedId)

  function apply(next: Partial<Record<keyof ExceptionFilters, string>>, resetPage = true) {
    setParams((current) => {
      const merged = new URLSearchParams(current)
      for (const [key, value] of Object.entries(next)) {
        if (key === 'status') merged.set('status', value || OPEN_STATUS)
        else if (value) merged.set(key, value)
        else merged.delete(key)
      }
      if (resetPage) merged.delete('page')
      merged.delete('selected')
      return merged
    })
  }
  function select(id: string) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.set('selected', id)
        return next
      },
      { replace: true },
    )
  }
  function reset() {
    setParams((current) => {
      const next = new URLSearchParams()
      const keep = current.get('month')
      if (keep) next.set('month', keep)
      return next
    })
  }

  const columns: TableColumn<ExceptionCase>[] = [
    {
      key: 'id',
      label: '번호',
      sortKey: 'exceptionCaseId',
      sortable: true,
      width: '4rem',
      align: 'left',
      render: (row) => row.exceptionCaseId },
    {
      key: 'type',
      label: '유형',
      width: '8.5rem',
      align: 'center',
      render: (row) => <StatusBadge tone={typeTone(row.type)}>{row.typeLabel ?? '-'}</StatusBadge>,
    },
    {
      key: 'reason',
      label: '상세 원인',
      align: 'center',
      width: '8.5rem',
      cellClassName: 'exception-disclosure-cell',
      render: (row) => <Disclosure preview={row.reasonLabel || '-'} singleLine />,
    },
    {
      key: 'severity',
      label: '심각도',
      sortKey: 'severity',
      sortable: true,
      width: '5rem',
      align: 'center',
      render: (row) => <StatusBadge tone={severityTone(row.severity)}>{row.severityLabel ?? '-'}</StatusBadge>,
    },
    {
      key: 'contract',
      label: '계약',
      sortKey: 'contractNo',
      sortable: true,
      width: '12rem',
      render: (row) => row.contractNo ?? '-' },
    {
      key: 'title',
      label: '내용',
      sortKey: 'title',
      sortable: true,
      cellClassName: 'exception-title-cell exception-disclosure-cell',
      render: (row) => <Disclosure preview={rateText(row.title ?? '-')} />,
    },
    {
      key: 'status',
      label: '상태',
      width: '5.75rem',
      align: 'center',
      render: (row) => <StatusBadge tone={statusTone(row.status)}>{row.statusLabel ?? '-'}</StatusBadge>,
    },
    {
      key: 'assignee',
      label: '담당자',
      sortKey: 'assigneeLoginId',
      sortable: true,
      width: '6rem',
      render: (row) => row.assigneeLoginId ?? '미배정' },
    {
      key: 'detectedAt',
      label: '최근 검출',
      sortKey: 'lastDetectedAt',
      sortable: true,
      width: '8.5rem',
      render: (row) => dateTime(row.lastDetectedAt),
    },
    {
      key: 'count',
      label: '검출',
      sortKey: 'detectionCount',
      sortable: true,
      width: '4rem',
      render: (row) => `${row.detectionCount ?? 0}회` },
    {
      key: 'reference',
      label: '참조',
      align: 'center',
      width: '8rem',
      cellClassName: 'tabular-nums exception-disclosure-cell',
      render: (row) => {
        const reference = `${row.sourceEntityType}:${row.sourceEntityId}`
        return row.sourceLink ? (
          <Disclosure
            singleLine
            preview={<Link data-guide-feature="features.exception.exception-page.link" to={row.sourceLink}>{reference}</Link>}
            full={
              <Link data-guide-feature="features.exception.exception-page.link-2" className="button button-secondary" to={row.sourceLink}>
                {reference}
              </Link>
            }
          />
        ) : (
          <Disclosure singleLine preview={reference} />
        )
      },
    },
  ]

  return (
    <div className="exception-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">예외함</h1>
        </div>
      </header>

      <section data-guide-feature="features.exception.exception-page.section" className="exception-summary-grid" aria-label="유형별 미처리 요약">
        {data?.summary?.map((summary) => (
          <Link data-guide-feature="features.exception.exception-page.link-3"
            key={summary.type}
            className={`kpi-card kpi-card-${cardTone(summary.type ?? '')} exception-summary-card ${filters.type === summary.type ? 'is-active' : ''}`}
            aria-current={filters.type === summary.type ? 'true' : undefined}
            aria-label={`${summary.typeLabel} ${int(summary.count)}건`}
            to={
              month
                ? withMonth(`/exceptions?type=${summary.type}&status=${OPEN_STATUS}`, month)
                : `/exceptions?type=${summary.type}&status=${OPEN_STATUS}`
            }
          >
            <div className="kpi-card-header">
              <span className="kpi-label">{summary.typeLabel}</span>
            </div>
            <div className="kpi-value-row">
              <strong className="kpi-value">{int(summary.count)}</strong>
              <span className="kpi-unit">건</span>
            </div>
            <div className="kpi-card-footer">
              <span className="kpi-comparison">{exceptionSummaryDescription(summary.type)}</span>
            </div>
          </Link>
        ))}
        {data && !data.summary?.length && (
          <p className="empty-state exception-summary-empty">미처리 예외가 없습니다.</p>
        )}
      </section>

      <FilterForm
        key={JSON.stringify(filters)}
        filters={filters}
        options={options.data}
        onApply={apply}
        onReset={reset}
      />

      {search.error && !data ? (
        <div role="alert" className="empty-state">
          <p>{errorText(search.error as Error, '예외 목록을 불러오지 못했습니다.')}</p>
          <button data-guide-feature="features.exception.exception-page.button" type="button" className="button button-secondary" onClick={() => void search.refetch()}>
            다시 시도
          </button>
        </div>
      ) : (
        <ResizableSplit
          className="exception-work-grid"
          storageKey="exceptions-workspace"
          label="예외 목록과 상세 카드 너비"
          primary={
            <section data-guide-feature="features.exception.exception-page.section-2" className="surface">
              <div className="surface-header">
                <span className="surface-title">예외 목록</span>
                <span className="text-secondary exception-count-note">
                  <span aria-live="polite">{int(data?.totalElements ?? 0)}</span>건
                </span>
              </div>
              {search.isPending ? (
                <p role="status" className="empty-state">
                  불러오는 중입니다.
                </p>
              ) : (
                <>
                  <DataTable
                    caption="예외 목록"
                    className="exception-table"
                    viewportClassName="exception-table-viewport"
                    rowClassName={() => 'exception-row'}
                    onRowActivate={(row) => select(String(row.exceptionCaseId))}
                    columns={columns}
                    {...sortBinding(filters.sort, EXCEPTION_SORT_KEYS, (sort) => apply({ sort }))}
                    rows={rows}
                    rowKey={(row) => String(row.exceptionCaseId)}
                    selectedKeys={selectedId ? [selectedId] : []}
                    onRowClick={(row) => select(String(row.exceptionCaseId))}
                    emptyMessage={EMPTY_MESSAGE}
                  />
                  <ListPagination
                    className="exception-pagination"
                    label="예외 목록 페이지 선택"
                    page={data?.page ?? 1}
                    totalPages={data?.totalPages ?? 1}
                    onPageChange={(page) => apply({ page: String(page) }, false)}
                  />
                </>
              )}
            </section>
          }
          secondary={
            <div className="exception-side-panels">
              <section data-guide-feature="features.exception.exception-page.section-3" className="surface">
                <div className="surface-header">
                  <span className="surface-title">검출 이력</span>
                  <span className="text-secondary exception-card-note">실행별 증거는 고치거나 지울 수 없습니다</span>
                </div>
                <div className="surface-body">
                  {selected ? (
                    <Occurrences item={selected} />
                  ) : (
                    <p className="text-secondary exception-placeholder">—</p>
                  )}
                </div>
              </section>
              <section data-guide-feature="features.exception.exception-page.section-4" className="surface">
                <div className="surface-header">
                  <span className="surface-title">처리</span>
                  {selected && <span>#{selected.exceptionCaseId}</span>}
                </div>
                <div className="surface-body">
                  {selected ? (
                    <ActionPanel key={selected.exceptionCaseId} item={selected} />
                  ) : (
                    <p className="text-secondary exception-placeholder">왼쪽 목록에서 한 건을 고르세요.</p>
                  )}
                </div>
              </section>
              <section data-guide-feature="features.exception.exception-page.section-5" className="surface">
                <div className="surface-header">
                  <span className="surface-title">처리 이력</span>
                  <span className="text-secondary exception-card-note">기록만 쌓입니다. 고치거나 지울 수 없습니다</span>
                </div>
                <div className="surface-body">
                  {selected ? <History item={selected} /> : <p className="text-secondary exception-placeholder">—</p>}
                </div>
              </section>
            </div>
          }
        />
      )}
    </div>
  )
}

function Occurrences({ item }: { item: ExceptionCase }) {
  const occurrences = item.occurrences ?? []
  if (!occurrences.length) return <p className="text-secondary exception-placeholder">실행별 검출 이력이 없습니다.</p>
  return (
    <ol className="exception-history-list">
      {occurrences.map((occurrence) => (
        <li className="exception-history-item" key={occurrence.exceptionOccurrenceId}>
          <strong>
            {(occurrence.validationMonth ?? '').slice(0, 7)} · {occurrence.runNo}회차 · {occurrence.detectionLabel}
          </strong>
          <p className="text-secondary exception-occurrence-meta">
            실행 <span className="tabular-nums">{occurrence.validationRunId}</span> ·{' '}
            <span className="tabular-nums">
              {occurrence.sourceEntityType}:{occurrence.sourceEntityId}
            </span>
          </p>
          {!!occurrence.evidenceItems?.length && (
            <details className="exception-evidence">
              <summary data-guide-feature="features.exception.exception-page.summary" className="text-secondary">검출 증거</summary>
              <ul className="exception-evidence-list">
                {occurrence.evidenceItems.map((evidence) => (
                  <li key={evidence.label}>
                    <span className="text-secondary">{evidence.label}</span>{' '}
                    <span className="tabular-nums">
                      {evidence.label === '사용률' && /%$/.test(evidence.value ?? '')
                        ? `${usageRate(evidence.value?.replace(/[% ,]/g, ''))}%`
                        : rateText(evidence.value)}
                    </span>
                  </li>
                ))}
              </ul>
            </details>
          )}
          <small className="text-secondary">{dateTime(occurrence.detectedAt)}</small>
        </li>
      ))}
    </ol>
  )
}

function History({ item }: { item: ExceptionCase }) {
  const actions = item.actions ?? []
  if (!actions.length) return <p className="text-secondary exception-placeholder">처리 이력이 없습니다.</p>
  return (
    <ol className="exception-history-list">
      {actions.map((action) => (
        <li className="exception-history-item" key={action.actionSeq}>
          <strong>
            {action.actionSeq}. {action.actionTypeLabel}
          </strong>
          <span className="text-secondary">
            {' '}
            · {action.fromStatusLabel} → {action.toStatusLabel}
          </span>
          <p className="exception-history-reason">{action.reason}</p>
          {action.evidenceRef && <p className="text-secondary exception-occurrence-meta">증빙: {action.evidenceRef}</p>}
          <small className="text-secondary">
            {action.actionByLoginId} · {dateTime(action.actionAt)}
          </small>
        </li>
      ))}
    </ol>
  )
}

function FilterForm({
  filters,
  options,
  onApply,
  onReset,
}: {
  filters: ExceptionFilters
  options?: ExceptionOptions
  onApply: (next: Partial<Record<keyof ExceptionFilters, string>>) => void
  onReset: () => void
}) {
  const [draft, setDraft] = useState(filters)
  const set = (key: keyof ExceptionFilters) => (value: string) => setDraft((current) => ({ ...current, [key]: value }))
  const select = (
    id: string,
    label: string,
    key: keyof ExceptionFilters,
    items: { value: string; label: string }[],
    className: string,
    all = true,
  ) => (
    <div className={`filter-field ${className}`}>
      <label className="filter-field-label" htmlFor={id}>
        {label}
      </label>
      <select data-guide-feature="features.exception.exception-page.select"
        className="select-control"
        id={id}
        value={String(draft[key])}
        onChange={(event) => set(key)(event.target.value)}
      >
        {all && <option value="">전체</option>}
        {items.map((item) => (
          <option key={item.value} value={item.value}>
            {item.label}
          </option>
        ))}
      </select>
    </div>
  )
  return (
    <FilterBar
      className="exception-filter-bar"
      wrapFields={false}
      resetFirst
      icons
      onSubmit={() =>
        onApply({
          type: draft.type,
          reasonCode: draft.reasonCode,
          severity: draft.severity,
          status: draft.status,
          assigneeFilter: draft.assigneeFilter,
          validationMonth: draft.validationMonth,
          contractNo: draft.contractNo.trim(),
        })
      }
      onReset={onReset}
    >
      <div className="filter-fields exception-filter-fields">
        {select(
          'f-type',
          '예외 유형',
          'type',
          (options?.types ?? []).map((o) => ({ value: o.code ?? '', label: o.label ?? '' })),
          'exception-filter-wide',
        )}
        {select(
          'f-reason',
          '상세 원인',
          'reasonCode',
          (options?.reasons ?? []).map((o) => ({ value: o.code ?? '', label: o.label ?? '' })),
          'exception-filter-wide',
        )}
        {select(
          'f-severity',
          '심각도',
          'severity',
          (options?.severities ?? []).map((o) => ({ value: o.code ?? '', label: o.label ?? '' })),
          'exception-filter-narrow',
        )}
        {select('f-status', '상태', 'status', statusOptions, 'exception-filter-narrow', false)}
        {select(
          'f-assignee',
          '담당자',
          'assigneeFilter',
          [
            { value: 'unassigned', label: '(미배정)' },
            ...(options?.assignees ?? []).map((a) => ({ value: String(a.userId), label: a.loginId ?? '' })),
          ],
          'exception-filter-narrow',
        )}
        {select(
          'f-validation-month',
          '검증월',
          'validationMonth',
          (options?.validationMonths ?? []).map((m) => ({ value: m, label: m.slice(0, 7) })),
          'exception-filter-narrow',
        )}
        <div className="filter-field exception-filter-mid">
          <label className="filter-field-label" htmlFor="f-contract">
            계약번호
          </label>
          <input data-guide-feature="features.exception.exception-page.f-contract"
            className="field-control"
            id="f-contract"
            type="text"
            placeholder="계약번호 입력"
            value={draft.contractNo}
            onChange={(event) => set('contractNo')(event.target.value)}
          />
        </div>
      </div>
    </FilterBar>
  )
}
