import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useOutletContext, useSearchParams } from 'react-router'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { StatusBadge } from '../../components/StatusBadge'
import { dateTime, errorText, int } from '../../lib/format'
import type { ShellContext } from '../../app/shell/AppShell'
import { withMonth } from '../../stores/workspace'
import { ActionPanel } from './ActionPanel'
import { Disclosure } from './Disclosure'
import { ALL_STATUS, exceptionOptionsQueryOptions, exceptionSearchQueryOptions, OPEN_STATUS } from './api'
import type { ExceptionCase, ExceptionFilters, ExceptionOptions } from './api'
import { severityTone, statusOptions, statusTone, typeTone } from './rules'

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
    { key: 'id', label: '번호', width: '4rem', align: 'number', render: (row) => row.exceptionCaseId },
    {
      key: 'type',
      label: '유형',
      width: '8.5rem',
      render: (row) => <StatusBadge tone={typeTone(row.type)}>{row.typeLabel ?? '-'}</StatusBadge>,
    },
    {
      key: 'reason',
      label: '상세 원인',
      width: '8.5rem',
      cellClassName: 'exception-disclosure-cell',
      render: (row) => <Disclosure preview={row.reasonLabel || '-'} singleLine />,
    },
    {
      key: 'severity',
      label: '심각도',
      width: '5rem',
      render: (row) => <StatusBadge tone={severityTone(row.severity)}>{row.severityLabel ?? '-'}</StatusBadge>,
    },
    { key: 'contract', label: '계약', width: '12rem', align: 'number', render: (row) => row.contractNo ?? '-' },
    {
      key: 'title',
      label: '내용',
      cellClassName: 'exception-title-cell exception-disclosure-cell',
      render: (row) => <Disclosure preview={row.title ?? '-'} />,
    },
    {
      key: 'status',
      label: '상태',
      width: '5.75rem',
      render: (row) => <StatusBadge tone={statusTone(row.status)}>{row.statusLabel ?? '-'}</StatusBadge>,
    },
    { key: 'assignee', label: '담당자', width: '6rem', render: (row) => row.assigneeLoginId ?? '미배정' },
    {
      key: 'detectedAt',
      label: '최근 검출',
      width: '8.5rem',
      align: 'number',
      render: (row) => dateTime(row.lastDetectedAt),
    },
    { key: 'count', label: '검출', width: '4rem', align: 'number', render: (row) => `${row.detectionCount ?? 0}회` },
    {
      key: 'reference',
      label: '참조',
      width: '8rem',
      cellClassName: 'tabular-nums exception-disclosure-cell',
      render: (row) => {
        const reference = `${row.sourceEntityType}:${row.sourceEntityId}`
        return row.sourceLink ? (
          <Disclosure
            singleLine
            preview={<Link to={row.sourceLink}>{reference}</Link>}
            full={
              <Link className="button button-secondary" to={row.sourceLink}>
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
    <div className="page-content exception-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">예외함</h1>
        </div>
      </header>

      <section className="exception-summary-grid" aria-label="유형별 미처리 요약">
        {data?.summary?.map((summary) => (
          <Link
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
              <span className="kpi-comparison tabular-nums">{summary.type}</span>
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
          <button type="button" className="button button-secondary" onClick={() => void search.refetch()}>
            다시 시도
          </button>
        </div>
      ) : (
        <div className="exception-work-grid">
          <section className="surface">
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
                  rows={rows}
                  rowKey={(row) => String(row.exceptionCaseId)}
                  selectedKeys={selectedId ? [selectedId] : []}
                  onRowClick={(row) => select(String(row.exceptionCaseId))}
                  emptyMessage={EMPTY_MESSAGE}
                />
                <div className="exception-pagination">
                  <span>
                    {data?.page ?? 1} / {data?.totalPages || 1} 페이지
                  </span>
                  <Pagination
                    page={data?.page ?? 1}
                    totalPages={data?.totalPages ?? 1}
                    onPageChange={(page) => apply({ page: String(page) }, false)}
                  />
                </div>
              </>
            )}
          </section>

          <div className="exception-side-panels">
            <section className="surface">
              <div className="surface-header">
                <span className="surface-title">검출 이력</span>
                <span className="text-secondary exception-card-note">실행별 증거는 고치거나 지울 수 없습니다</span>
              </div>
              <div className="surface-body">
                {selected ? <Occurrences item={selected} /> : <p className="text-secondary exception-placeholder">—</p>}
              </div>
            </section>
            <section className="surface">
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
            <section className="surface">
              <div className="surface-header">
                <span className="surface-title">처리 이력</span>
                <span className="text-secondary exception-card-note">기록만 쌓입니다. 고치거나 지울 수 없습니다</span>
              </div>
              <div className="surface-body">
                {selected ? <History item={selected} /> : <p className="text-secondary exception-placeholder">—</p>}
              </div>
            </section>
          </div>
        </div>
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
              <summary className="text-secondary">검출 증거</summary>
              <ul className="exception-evidence-list">
                {occurrence.evidenceItems.map((evidence) => (
                  <li key={evidence.label}>
                    <span className="text-secondary">{evidence.label}</span>{' '}
                    <span className="tabular-nums">{evidence.value}</span>
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
      <select
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
          <input
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
