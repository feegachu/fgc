import { SaveGuide } from '../assistant/guide/save-guide'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, useEffect, useRef, useSyncExternalStore } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useOutletContext } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { CategoryBadge } from '../../components/StatusBadge'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { ListHeader } from '../../components/ListHeader'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { sortBinding } from '../../lib/tableSort'
import { dateTime, errorText, month as formatMonth, today } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { validMonth, withMonth } from '../../stores/workspace'
import { usePermission } from '../auth/useAuth'
import {
  activeMonthlyQuery,
  createRun,
  normalizeCriteria,
  RUN_SORT_KEYS,
  RUN_STATUSES,
  RUN_TYPES,
  runsQuery,
} from './api'
import type { ValidationRun } from './api'
import { RunStatusBadge } from './RunStatusBadge'
import { failureText, progressText } from './steps'
import './validation.css'

/*
 * FGC-UI-VRUN-W01 월 통합검증 실행 목록 (FUN-041). 1차 vrun/list.html 과 같은 문구·흐름이다.
 * 생성은 PRG 폼 제출이 아니라 POST /api/v1/validation-runs 이고, 성공하면 상세로 이동해
 * [실행]을 누르게 한다. 상태 필터와 페이지는 URL 쿼리(status·page)에 둔다.
 * [막을 것] 같은 달에 진행 중(생성됨·실행중)인 월간 실행이 있으면 새로 만들 수 없다 —
 * 화면에서 버튼을 끄고, 서버(409 FGC-VRUN-001)가 최종 방어한다.
 */
const FILTER_KEYS = { validationMonth: '', status: '', sort: '', page: '' }
const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))
const finalizedText = (run: ValidationRun) =>
  run.finalizedAt ? `${run.finalizedBy ?? '-'}, ${dateTime(run.finalizedAt)}` : '-'

export default function ValidationRunListPage() {
  const canProcess = usePermission('canProcess')
  const [saveGuide] = useState(() => new SaveGuide())
  const { params, update } = useSearchParamsState(FILTER_KEYS)
  const criteria = normalizeCriteria(params)
  const runs = useQuery(runsQuery(criteria))
  const rows = runs.data?.content ?? []
  const totalPages = runs.data?.totalPages ?? 0

  const columns: TableColumn<ValidationRun>[] = [
    {
      key: 'month',
      sortKey: 'validationMonth',
      sortable: true,
      label: '검증월',
      width: '5.5rem',
      cellClassName: 'tabular-nums',
      render: (r) => formatMonth(r.validationMonth),
    },
    { key: 'runNo', label: '실행 차수', sortable: true, width: '4.5rem', align: 'left', render: (r) => `${r.runNo}차` },
    {
      key: 'type',
      label: '실행 유형',
      width: '5.5rem',
      render: (r) => (
        <CategoryBadge value={r.runType} optionCount={3}>
          {dash(r.runTypeLabel)}
        </CategoryBadge>
      ),
    },
    {
      key: 'status',
      label: '상태',
      width: '5.75rem',
      align: 'center',
      render: (r) => <RunStatusBadge status={r.status} label={r.statusLabel} />,
    },
    {
      key: 'progress',
      label: '진행',
      width: '8.5rem',
      cellClassName: 'tabular-nums',
      render: (r) => progressText(r.currentStep),
    },
    {
      key: 'actor',
      label: '실행자',
      sortKey: 'triggeredBy',
      sortable: true,
      width: '5rem',
      render: (r) => dash(r.triggeredBy),
    },
    {
      key: 'started',
      label: '시작',
      sortKey: 'startedAt',
      sortable: true,
      width: '7rem',
      cellClassName: 'tabular-nums',
      render: (r) => <CellDisclosure preview={dateTime(r.startedAt)} />,
    },
    {
      key: 'completed',
      label: '완료',
      sortKey: 'completedAt',
      sortable: true,
      width: '7rem',
      cellClassName: 'tabular-nums',
      render: (r) => <CellDisclosure preview={dateTime(r.completedAt)} />,
    },
    {
      key: 'finalized',
      label: '확정자, 확정시각',
      sortKey: 'finalizedAt',
      sortable: true,
      width: '9rem',
      render: (r) => (
        <CellDisclosure
          preview={<span className="tabular-nums">{finalizedText(r)}</span>}
          full={finalizedText(r)}
        />
      ),
    },
    {
      key: 'open',
      label: '상세',
      width: '5.5rem',
      cellClassName: 'vrun-open-cell',
      render: (r) => <OpenLink run={r} />,
    },
    {
      key: 'failure',
      label: '실패 사유',
      render: (r) =>
        r.status === 'FAILED' ? (
          <CellDisclosure
            preview={<span className="vrun-failure-text">{failureText(r.failureMessage, r.currentStep)}</span>}
            full={failureText(r.failureMessage, r.currentStep)}
          />
        ) : (
          '-'
        ),
    },
  ]

  return (
    <div className="vrun-page vrun-list-page list-page">
      <header className="page-header">

        <div className="page-header-copy">
          <h1 className="page-title">월 통합검증 실행 목록</h1>
        </div>
        <div className="page-header-actions">{canProcess ? <ScreenHelp screenId="VRUN-W01" saveGuide={saveGuide} /> : <ScreenHelp screenId="VRUN-W01" variant="reader" />}</div>
      </header>

      <CreateSection saveGuide={saveGuide} />

      <section data-guide-feature="features.validation.validation-run-list-page.section" data-guide-target="validation.runs" className="surface" aria-labelledby="vrun-list-title">
        <ListHeader total={runs.data?.totalElements ?? 0} pageSize={20}>
          <h2 className="surface-title" id="vrun-list-title">
            실행 목록
          </h2>
        </ListHeader>
        <StatusFilter key={`${criteria.validationMonth}:${criteria.status}`} validationMonth={criteria.validationMonth ?? ''} status={criteria.status} onSearch={(validationMonth, status) => update({ validationMonth, status })} />
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
          {...sortBinding(criteria.sort, RUN_SORT_KEYS, (sort) => update({ sort }))}
          rows={rows}
          rowKey={(r) => String(r.validationRunId)}
          emptyMessage={runs.isPending ? '불러오는 중입니다.' : undefined}
        />
        {totalPages > 1 && (
          <ListPagination
            className="vrun-pagination"
            label="월 통합검증 실행 목록 페이지 선택"
            page={criteria.page}
            totalPages={totalPages}
            onPageChange={(page) => update({ page: String(page) }, { resetPage: false })}
          />
        )}
      </section>
    </div>
  )
}

function OpenLink({ run }: { run: ValidationRun }) {
  const { month } = useOutletContext<ShellContext>()
  const to = `/validation-runs/${run.validationRunId}`
  return (
    <Link data-guide-feature="features.validation.validation-run-list-page.link"
      className="button button-secondary vrun-row-button"
      to={month ? withMonth(to, month) : to}
      aria-label={`${formatMonth(run.validationMonth)} ${run.runNo}차 실행 열기`}
    >
      열기
    </Link>
  )
}

/** ① 새 실행 만들기 — 검증월 기본값은 셸 기준월, 활성 월간 실행 여부는 선택한 검증월로 판정한다. */
function CreateSection({ saveGuide }: { saveGuide: SaveGuide }) {
  const [showCreateHint, setShowCreateHint] = useState(false)
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
  const blockedByActive = runType === 'MONTHLY' && hasActive
  const savedId = useSyncExternalStore(saveGuide.subscribe, saveGuide.getSnapshot).savedId
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => { saveGuide.update(`${month}:${runType}`, canProcess && monthOk && !blockedByActive && !active.isError && !active.isPending) }, [saveGuide, month, runType, canProcess, monthOk, blockedByActive, active.isError, active.isPending])
  const mutation = useMutation({
    mutationFn: () => { saveGuide.begin(); return createRun(month, runType) },
    meta: { errorToast: false },
    onSuccess: async (created) => {
      void client.invalidateQueries({ queryKey: ['validation-runs'] })
      if (!mounted.current || !(await saveGuide.saved(Number(created.validationRunId))) || !mounted.current) return
      toast(`${month} 검증월 ${created.runNo}차 실행이 생성되었습니다.`, 'success')
      const to = `/validation-runs/${created.validationRunId}`
      void navigate(shellMonth ? withMonth(to, shellMonth) : to)
    },
    onError: (error) => {
      saveGuide.fail(errorText(error))
      toast(errorText(error as Error, '검증월과 실행 유형을 확인하세요.'), 'error')
      void client.invalidateQueries({ queryKey: ['validation-runs', 'active-monthly'] })
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    if (monthOk && canProcess && !blockedByActive && !mutation.isPending) mutation.mutate()
  }
  const createDisabled = !canProcess || !monthOk || blockedByActive || mutation.isPending
  const createHint = !canProcess
    ? '실행 생성은 정산담당자와 시스템관리자만 할 수 있습니다.'
    : !monthOk ? '검증월을 선택해 주세요.'
      : mutation.isPending ? '실행을 생성하고 있습니다. 잠시 기다려 주세요.'
        : blockedByActive ? '선택한 검증월에 진행 중인 월간 실행이 있어 새로 만들 수 없습니다.'
          : '선택한 검증월과 실행 유형으로 새 검증 실행을 만듭니다. 같은 달에 진행 중인 월간 실행이 있으면 새로 만들 수 없습니다.'
  return (
    <section data-guide-feature="features.validation.validation-run-list-page.section-2" data-guide-target="validation.create" className="surface" aria-labelledby="vrun-create-title">
      <header className="surface-header">
        <h2 className="surface-title" id="vrun-create-title">
          새 실행 만들기
        </h2>
      </header>
      <form data-guide-feature="features.validation.validation-run-list-page.form" className="vrun-create-form search-conditions" onSubmit={submit}>
        <div className="vrun-create-month">
          <Field label="검증월" required>
            <input data-guide-feature="features.validation.validation-run-list-page.input" type="month" max={today().slice(0, 7)} disabled={mutation.isPending} value={month} onChange={(event) => setPicked(event.target.value)} />
          </Field>
        </div>
        <div className="vrun-create-type">
          <Field label="실행 유형" required>
            <select data-guide-feature="features.validation.validation-run-list-page.select" className="select-control" disabled={mutation.isPending} value={runType} onChange={(event) => setRunType(event.target.value)}>
              {RUN_TYPES.map((type) => (
                <option key={type.value} value={type.value}>
                  {type.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {/* 실행 생성은 SETTLEMENT (화면정의서 :1379, IF-API-45). 해당 월에 활성 MONTHLY 가 있으면 비활성 */}
        <div className="vrun-finalize-trigger"
          tabIndex={createDisabled ? 0 : undefined}
          aria-label={createDisabled ? '실행 생성 불가 사유' : undefined}
          aria-describedby="create-hint"
          onMouseEnter={() => setShowCreateHint(true)}
          onMouseLeave={() => setShowCreateHint(false)}
          onFocus={() => setShowCreateHint(true)}
          onBlur={() => setShowCreateHint(false)}
          onKeyDown={event => { if (event.key === 'Escape') setShowCreateHint(false) }}
        >
        <Button data-guide-feature="features.validation.validation-run-list-page.btn-create"
          type="submit"
          data-guide-target="validation.createSubmit"
          id="btn-create"
          aria-describedby="create-hint"
          loading={mutation.isPending && savedId === null}
          disabled={createDisabled}
        >
          <span className="material-symbols-rounded" aria-hidden="true">
            add
          </span>
          실행 생성
        </Button>
        <div className="vrun-finalize-tooltip" id="create-hint" role="tooltip" hidden={!showCreateHint}>{createHint}</div>
        </div>
      </form>
    </section>
  )
}

function StatusFilter({ validationMonth, status, onSearch }: { validationMonth: string; status: string; onSearch: (validationMonth: string, status: string) => void }) {
  const [draftMonth, setDraftMonth] = useState(validationMonth)
  const [draft, setDraft] = useState(status)
  return (
    <FilterBar className="vrun-filter-bar" embedded onSubmit={() => onSearch(draftMonth, draft)}>
      <div className="filter-field vrun-filter-status">
        <label className="filter-field-label" htmlFor="f-validation-month">검증월</label>
        <input className="field-control" id="f-validation-month" type="month" value={draftMonth}
          aria-label="검증월 (비워두면 전체 월)"
          onChange={(event) => setDraftMonth(event.target.value)} />
      </div>
      <div className="filter-field vrun-filter-status">
        <label className="filter-field-label" htmlFor="f-status">
          상태
        </label>
        <select data-guide-feature="features.validation.validation-run-list-page.f-status"
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
