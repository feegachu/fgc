import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { dateTimeSeconds, errorText, int } from '../../lib/format'
import { auditLogDetailQuery, auditLogOptionsQuery, auditLogsQuery, normalizeCriteria } from './api'
import type { AuditCriteria, AuditLog, AuditLogOptions } from './api'
import './audit.css'
import './audit-react.css'

/*
 * FGC-UI-AUDT-W01 감사로그 조회 (FUN-061). 1차 audit/list.html 과 같은 문구·딥링크를 쓴다.
 * [막을 것] 수정·삭제 버튼 자체를 만들지 않는다 — 감사로그를 고칠 수 있으면 감사로그가 아니다.
 * 검색조건과 선택 행은 URL 쿼리(entityType·entityId·userId·action·from·to·page·selected)에 둔다.
 */
const FILTER_KEYS = { entityType: '', entityId: '', userId: '', action: '', from: '', to: '', page: '', selected: '' }
type Filters = Omit<AuditCriteria, 'page'>

const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))

const columns: TableColumn<AuditLog>[] = [
  { key: 'occurredAt', label: '발생시각', render: (row) => dateTimeSeconds(row.occurredAt) },
  { key: 'user', label: '행위자', render: (row) => row.userLoginId ?? 'BATCH' },
  { key: 'action', label: '행위', render: (row) => dash(row.actionCode) },
  { key: 'entityType', label: '대상 종류', render: (row) => dash(row.entityType) },
  { key: 'entityId', label: '대상 ID', render: (row) => dash(row.entityId) },
  { key: 'reason', label: '사유', render: (row) => dash(row.reason), expandable: true },
  { key: 'policy', label: '정책버전', render: (row) => dash(row.policyVersionId) },
  { key: 'requestId', label: '요청 ID', render: (row) => dash(row.requestId) },
]

export default function AuditLogPage() {
  const { params, update, reset } = useSearchParamsState(FILTER_KEYS)
  const criteria = normalizeCriteria(params)
  const logs = useQuery(auditLogsQuery(criteria))
  const options = useQuery(auditLogOptionsQuery())
  const rows = logs.data?.content ?? []
  const selectedId = params.get('selected')
  const selected = rows.find((row) => String(row.auditLogId) === selectedId)
  const totalPages = logs.data?.totalPages ?? 0

  return (
    <div className="audit-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">감사로그 조회</h1>
        </div>
      </header>

      {/* 뒤로·앞으로 이동으로 URL 이 바뀌면 입력값도 그 URL 기준으로 다시 채운다. */}
      <AuditFilter
        key={params.toString()}
        initial={criteria}
        options={options.data}
        onSearch={(filters) => update({ ...filters, selected: '' })}
        onReset={reset}
      />
      {options.isError && (
        <p className="field-error" role="alert">
          {errorText(options.error)}
        </p>
      )}

      <div className="audit-layout">
        <section className="surface audit-list-panel" aria-labelledby="audit-list-title">
          <header className="surface-header audit-panel-header">
            <h2 id="audit-list-title" className="surface-title">
              감사 기록
            </h2>
            <p className="audit-panel-caption">
              <span className="tabular-nums">{int(logs.data?.totalElements ?? 0)}</span>건 · 한 화면 20행
            </p>
          </header>
          {logs.isError && (
            <p className="field-error" role="alert">
              {errorText(logs.error)}
            </p>
          )}
          <DataTable
            caption="검색조건에 해당하는 감사 기록 목록"
            columns={columns}
            rows={rows}
            rowKey={(row) => String(row.auditLogId)}
            selectedKeys={selected ? [String(selected.auditLogId)] : []}
            onRowClick={(row) => update({ selected: String(row.auditLogId) }, { resetPage: false })}
            emptyMessage={logs.isPending ? '불러오는 중입니다.' : undefined}
          />
          {totalPages > 1 && (
            <footer className="audit-pagination">
              <span className="audit-pagination-status tabular-nums" aria-live="polite">
                {criteria.page} / {totalPages} 페이지
              </span>
              <Pagination
                page={criteria.page}
                totalPages={totalPages}
                onPageChange={(page) => update({ page: String(page), selected: '' }, { resetPage: false })}
              />
            </footer>
          )}
        </section>

        <section className="surface audit-diff-panel" aria-labelledby="audit-diff-title">
          <header className="surface-header audit-panel-header">
            <h2 id="audit-diff-title" className="surface-title">
              변경 내용 비교
            </h2>
          </header>
          <div className="surface-body audit-diff-body" id="diff-body" aria-live="polite">
            {selected?.auditLogId == null ? (
              <p className="audit-diff-prompt">왼쪽 목록에서 한 건을 고르세요.</p>
            ) : (
              <AuditDiff log={selected} auditLogId={selected.auditLogId} />
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

function AuditFilter({
  initial,
  options,
  onSearch,
  onReset,
}: {
  initial: Filters
  options?: AuditLogOptions
  onSearch: (filters: Filters) => void
  onReset: () => void
}) {
  const [filters, setFilters] = useState<Filters>({
    entityType: initial.entityType,
    entityId: initial.entityId,
    userId: initial.userId,
    action: initial.action,
    from: initial.from,
    to: initial.to,
  })
  const bind = (key: keyof Filters) => ({
    id: `f-${key}`,
    name: key,
    value: filters[key],
    onChange: (event: { target: { value: string } }) => setFilters({ ...filters, [key]: event.target.value }),
  })
  return (
    <FilterBar onSubmit={() => onSearch(filters)} onReset={onReset}>
      <div className="filter-field audit-filter-date">
        <label className="filter-field-label" htmlFor="f-from">
          발생 시각 (시작)
        </label>
        <input className="field-control" type="date" {...bind('from')} />
      </div>
      <div className="filter-field audit-filter-date">
        <label className="filter-field-label" htmlFor="f-to">
          발생 시각 (끝)
        </label>
        <input className="field-control" type="date" {...bind('to')} />
      </div>
      <div className="filter-field audit-filter-user">
        <label className="filter-field-label" htmlFor="f-userId">
          행위자
        </label>
        <select className="select-control" {...bind('userId')}>
          <option value="">전체</option>
          {options?.users?.map((user) => (
            <option key={user.userId} value={String(user.userId)}>
              {user.loginId}
            </option>
          ))}
        </select>
      </div>
      <div className="filter-field audit-filter-code">
        <label className="filter-field-label" htmlFor="f-action">
          행위 종류
        </label>
        <select className="select-control" {...bind('action')}>
          <option value="">전체</option>
          {options?.actionCodes?.map((code) => (
            <option key={code}>{code}</option>
          ))}
        </select>
      </div>
      <div className="filter-field audit-filter-code">
        <label className="filter-field-label" htmlFor="f-entityType">
          대상 종류
        </label>
        <select className="select-control" {...bind('entityType')}>
          <option value="">전체</option>
          {options?.entityTypes?.map((type) => (
            <option key={type}>{type}</option>
          ))}
        </select>
      </div>
      <div className="filter-field audit-filter-entity-id">
        <label className="filter-field-label" htmlFor="f-entityId">
          대상 ID
        </label>
        <input className="field-control" type="text" placeholder="4104" {...bind('entityId')} />
      </div>
    </FilterBar>
  )
}

function AuditDiff({ log, auditLogId }: { log: AuditLog; auditLogId: number }) {
  const detail = useQuery(auditLogDetailQuery(auditLogId))
  const entries = detail.data?.diff ?? []
  return (
    <div>
      <p className="audit-diff-context">
        <span className="tabular-nums">{log.actionCode}</span> ·{' '}
        <span className="tabular-nums">{`${log.entityType} #${log.entityId}`}</span>
      </p>
      {detail.isError && (
        <p className="field-error" role="alert">
          {errorText(detail.error)}
        </p>
      )}
      <div className="data-table-viewport">
        <table className="data-table audit-diff-table">
          <caption className="visually-hidden">선택한 감사 기록의 변경 전후 값 비교</caption>
          <colgroup>
            <col className="audit-diff-field" />
            <col />
            <col />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">항목</th>
              <th scope="col">이전값</th>
              <th scope="col">이후값</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 ? (
              <tr className="audit-state-row">
                <td colSpan={3}>
                  <div className="empty-state audit-empty-state">
                    {detail.isPending ? '불러오는 중입니다.' : '기록된 변경 전·후 값이 없습니다.'}
                  </div>
                </td>
              </tr>
            ) : (
              entries.map((entry) => {
                const changed = entry.changed ? ' audit-diff-cell-changed' : ''
                return (
                  <tr key={entry.field}>
                    <td className="tabular-nums audit-diff-value">{entry.field}</td>
                    <td className={`tabular-nums audit-diff-value${changed}`}>{entry.before}</td>
                    <td className={`tabular-nums audit-diff-value${changed}`}>{entry.after}</td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
