import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useOutletContext } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { dateTime, errorText, month as formatMonth } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { validMonth, withMonth } from '../../stores/workspace'
import { usePermission } from '../auth/useAuth'
import { activeMonthlyQuery, createRun, normalizeCriteria, RUN_STATUSES, RUN_TYPES, runsQuery } from './api'
import type { ValidationRun } from './api'
import { RunStatusBadge } from './RunStatusBadge'
import { progressText } from './steps'
import './validation.css'

/*
 * FGC-UI-VRUN-W01 월 통합검증 실행 목록 (FUN-041). 1차 vrun/list.html 과 같은 문구·흐름이다.
 * 생성은 PRG 폼 제출이 아니라 POST /api/v1/validation-runs 이고, 성공하면 상세로 이동해
 * [실행]을 누르게 한다. 상태 필터와 페이지는 URL 쿼리(status·page)에 둔다.
 * [막을 것] 같은 달에 진행 중(생성됨·실행중)인 월간 실행이 있으면 새로 만들 수 없다 —
 * 화면에서 버튼을 끄고, 서버(409 FGC-VRUN-001)가 최종 방어한다.
 */
const FILTER_KEYS = { status: '', page: '' }
const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))
const finalizedText = (run: ValidationRun) =>
  run.finalizedAt ? `${run.finalizedBy ?? '-'} · ${dateTime(run.finalizedAt)}` : '-'

export default function ValidationRunListPage() {
  const { params, update } = useSearchParamsState(FILTER_KEYS)
  const criteria = normalizeCriteria(params)
  const runs = useQuery(runsQuery(criteria))
  const rows = runs.data?.content ?? []
  const totalPages = runs.data?.totalPages ?? 0

  const columns: TableColumn<ValidationRun>[] = [
    {
      key: 'month',
      label: '검증월',
      width: '5.5rem',
      cellClassName: 'tabular-nums',
      render: (r) => formatMonth(r.validationMonth),
    },
    { key: 'runNo', label: '회차', width: '3.5rem', align: 'number', render: (r) => r.runNo },
    { key: 'type', label: '실행 유형', width: '7rem', render: (r) => dash(r.runTypeLabel) },
    {
      key: 'status',
      label: '상태',
      width: '7rem',
      align: 'center',
      render: (r) => <RunStatusBadge status={r.status} label={r.statusLabel} />,
    },
    {
      key: 'progress',
      label: '진행',
      width: '9.5rem',
      cellClassName: 'tabular-nums',
      render: (r) => progressText(r.currentStep),
    },
    { key: 'actor', label: '실행자', width: '6rem', render: (r) => dash(r.triggeredBy) },
    {
      key: 'started',
      label: '시작',
      width: '8.5rem',
      cellClassName: 'tabular-nums',
      render: (r) => dateTime(r.startedAt),
    },
    {
      key: 'completed',
      label: '완료',
      width: '8.5rem',
      cellClassName: 'tabular-nums',
      render: (r) => dateTime(r.completedAt),
    },
    {
      key: 'finalized',
      label: '확정자 · 확정시각',
      width: '10.5rem',
      render: (r) => (
        <CellDisclosure
          singleLine
          preview={<span className="tabular-nums">{finalizedText(r)}</span>}
          full={finalizedText(r)}
        />
      ),
    },
    {
      key: 'failure',
      label: '실패 사유',
      width: '14rem',
      render: (r) =>
        r.status === 'FAILED' ? (
          <CellDisclosure
            singleLine
            preview={<span className="vrun-failure-text">{dash(r.failureMessage)}</span>}
            full={dash(r.failureMessage)}
          />
        ) : (
          '-'
        ),
    },
    {
      key: 'open',
      label: '상세',
      width: '5rem',
      align: 'center',
      render: (r) => <OpenLink run={r} />,
    },
  ]

  return (
    <div className="page-content vrun-page vrun-list-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">월 통합검증 실행 목록</h1>
        </div>
      </header>

      <CreateSection />

      <section className="surface" aria-labelledby="vrun-list-title">
        <header className="surface-header">
          <h2 className="surface-title" id="vrun-list-title">
            ② 실행 목록
          </h2>
        </header>
        <StatusFilter key={criteria.status} status={criteria.status} onSearch={(status) => update({ status })} />
        {runs.isError && (
          <p className="field-error" role="alert">
            {errorText(runs.error as Error, '실행 목록을 불러오지 못했습니다.')}
          </p>
        )}
        <DataTable
          caption="월 통합검증 실행 목록"
          className="vrun-run-table"
          viewportClassName="vrun-table-viewport"
          columns={columns}
          rows={rows}
          rowKey={(r) => String(r.validationRunId)}
          emptyMessage={runs.isPending ? '불러오는 중입니다.' : undefined}
        />
        {totalPages > 1 && (
          <nav className="vrun-pagination" aria-label="월 통합검증 실행 목록 페이지">
            <span className="tabular-nums" aria-live="polite">
              {criteria.page} / {totalPages} 페이지
            </span>
            <Pagination
              compact
              page={criteria.page}
              totalPages={totalPages}
              onPageChange={(page) => update({ page: String(page) }, { resetPage: false })}
            />
          </nav>
        )}
      </section>
    </div>
  )
}

function OpenLink({ run }: { run: ValidationRun }) {
  const { month } = useOutletContext<ShellContext>()
  const to = `/validation-runs/${run.validationRunId}`
  return (
    <Link
      className="button button-secondary vrun-row-button"
      to={month ? withMonth(to, month) : to}
      aria-label={`${formatMonth(run.validationMonth)} ${run.runNo}회차 실행 열기`}
    >
      열기
    </Link>
  )
}

/** ① 새 실행 만들기 — 검증월 기본값은 셸 기준월, 활성 월간 실행 여부는 선택한 검증월로 판정한다. */
function CreateSection() {
  const { month: shellMonth } = useOutletContext<ShellContext>()
  const canProcess = usePermission('canProcess')
  const navigate = useNavigate()
  const client = useQueryClient()
  // 셸 기준월은 화면이 뜬 뒤에 정해질 수 있어, 사용자가 고르기 전까지는 기준월을 그대로 따른다.
  const [picked, setPicked] = useState<string | null>(null)
  const month = picked ?? shellMonth ?? ''
  const [runType, setRunType] = useState<string>('MONTHLY')
  const monthOk = validMonth(month)
  const active = useQuery({ ...activeMonthlyQuery(month), enabled: monthOk })
  const hasActive = active.data?.exists === true
  const mutation = useMutation({
    mutationFn: () => createRun(month, runType),
    meta: { errorToast: false },
    onSuccess: (created) => {
      toast(`${month} 검증월 ${created.runNo}회차 실행이 생성되었습니다.`, 'success')
      void client.invalidateQueries({ queryKey: ['validation-runs'] })
      const to = `/validation-runs/${created.validationRunId}`
      void navigate(shellMonth ? withMonth(to, shellMonth) : to)
    },
    onError: (error) => {
      toast(errorText(error as Error, '검증월(yyyy-MM)과 실행 유형을 확인하세요.'), 'error')
      void client.invalidateQueries({ queryKey: ['validation-runs', 'active-monthly'] })
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    if (monthOk) mutation.mutate()
  }
  return (
    <section className="surface" aria-labelledby="vrun-create-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-create-title">
          ① 새 실행 만들기
        </h2>
      </header>
      <form className="vrun-create-form" onSubmit={submit}>
        <div className="vrun-create-month">
          <Field label="검증월" required>
            <input type="month" value={month} onChange={(event) => setPicked(event.target.value)} />
          </Field>
        </div>
        <div className="vrun-create-type">
          <Field label="실행 유형" required>
            <select className="select-control" value={runType} onChange={(event) => setRunType(event.target.value)}>
              {RUN_TYPES.map((type) => (
                <option key={type.value} value={type.value}>
                  {type.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {/* 실행 생성은 SETTLEMENT (화면정의서 :1379, IF-API-45). 해당 월에 활성 MONTHLY 가 있으면 비활성 */}
        <Button
          type="submit"
          id="btn-create"
          aria-describedby="create-hint"
          loading={mutation.isPending}
          disabled={!canProcess || !monthOk || (runType === 'MONTHLY' && hasActive)}
        >
          <span className="material-symbols-rounded" aria-hidden="true">
            add
          </span>
          실행 생성
        </Button>
        <p className="field-helper vrun-create-hint" id="create-hint">
          {!canProcess
            ? '실행 생성은 정산 담당자만 할 수 있습니다.'
            : hasActive
              ? '기준월에 진행 중인 월간 실행이 있어 새로 만들 수 없습니다.'
              : '같은 달에 진행 중인 월간 실행이 있으면 새로 만들 수 없습니다.'}
        </p>
      </form>
    </section>
  )
}

function StatusFilter({ status, onSearch }: { status: string; onSearch: (status: string) => void }) {
  const [draft, setDraft] = useState(status)
  return (
    <FilterBar className="vrun-filter-bar" icons onSubmit={() => onSearch(draft)}>
      <div className="filter-field vrun-filter-status">
        <label className="filter-field-label" htmlFor="f-status">
          상태
        </label>
        <select
          className="select-control"
          id="f-status"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
        >
          <option value="">전체</option>
          {RUN_STATUSES.map((item) => (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          ))}
        </select>
      </div>
    </FilterBar>
  )
}
