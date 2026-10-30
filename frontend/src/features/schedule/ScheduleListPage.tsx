import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useLocation } from 'react-router'
import { ContextLink } from '../../components/ContextLink'
import { returnLink } from '../transaction/payment-entry'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { exportCsv } from '../../lib/exportCsv'
import { sortBinding } from '../../lib/tableSort'
import { errorText, int, isNegative, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import {
  criteriaFromParams,
  DEFAULT_PURPOSE,
  listCsvPath,
  normalizePage,
  PAGE_SIZE,
  SCHEDULE_SORT_KEYS,
  scheduleListQuery,
} from './api'
import type { ScheduleFilters, ScheduleHeader } from './api'
import { ActiveBadge, ClassificationBadge, RegimeEvidence, ScheduleStatusBadge } from './badges'
import { EMPTY, PAYMENT_STAGE, responseLabel, SCHEDULE_PURPOSE, SCHEDULE_REGIME, SCHEDULE_STATUS } from './labels'
import './schedule.css'

/*
 * FGC-UI-SCHE-W01 예상 스케줄 목록. 1차 schedule/list.html · schedule-list.js 와 같은 문구·딥링크를 쓴다.
 * 검색조건은 URL 쿼리(contractNo·stage·regime·purpose·status·page)가 단일 출처다.
 */
const FILTER_KEYS = { contractNo: '', stage: '', regime: '', purpose: '', status: '', sort: '', page: '' }
const DEFAULT_FILTERS: ScheduleFilters = { contractNo: '', stage: '', regime: '', purpose: DEFAULT_PURPOSE, status: '' }

const detailHref = (row: ScheduleHeader, back: string) =>
  returnLink(`/schedules/${encodeURIComponent(String(row.scheduleHeaderId))}`, back, String(row.scheduleHeaderId))
const hasId = (row: ScheduleHeader) => row.scheduleHeaderId != null

/** 같은 계약·지급단계에 사용 중인 운영 스케줄이 둘 이상이면 데이터 이상이다(1차 hasDuplicateActiveOperational). */
function hasDuplicateActiveOperational(rows: ScheduleHeader[]) {
  const seen = new Set<string>()
  return rows.some((row) => {
    if (row.activeYn !== true || row.schedulePurpose !== 'OPERATIONAL') return false
    const key = `${row.contractNo ?? ''}|${row.paymentStage ?? ''}`
    if (seen.has(key)) return true
    seen.add(key)
    return false
  })
}

// 열 너비는 1차 schedule.css 의 .schedule-col-* 와 같은 값이다.
const columns = (back: string): TableColumn<ScheduleHeader>[] => [
  {
    key: 'contractNo',
    label: '계약번호',
    sortable: true,
    width: '12rem',
    cellClassName: 'tabular-nums',
    // 계약번호는 폭을 넓게 잡아 토글 없이 항상 전체를 보여준다(#326).
    render: (row) =>
      hasId(row) ? (
        <ContextLink data-guide-feature="features.schedule.schedule-list-page.context-link" to={detailHref(row, back)}>{row.contractNo || EMPTY}</ContextLink>
      ) : (
        row.contractNo || EMPTY
      ),
  },
  {
    key: 'stage',
    label: '지급단계',
    width: '7.5rem',
    render: (row) => responseLabel(row.paymentStageLabel, PAYMENT_STAGE, row.paymentStage),
  },
  {
    key: 'regime',
    // 태그 컬럼은 머리글과 값을 함께 가운데 정렬한다.
    label: <RegimeEvidence />,
    width: '7.75rem',
    align: 'center',
    render: (row) => (
      <ClassificationBadge label={responseLabel(row.scheduleRegimeLabel, SCHEDULE_REGIME, row.scheduleRegime)} />
    ),
  },
  {
    key: 'purpose',
    // 태그 컬럼은 머리글과 값을 함께 가운데 정렬한다.
    label: '용도',
    width: '6.5rem',
    align: 'center',
    render: (row) => (
      <ClassificationBadge label={responseLabel(row.schedulePurposeLabel, SCHEDULE_PURPOSE, row.schedulePurpose)} />
    ),
  },
  {
    key: 'version',
    label: '버전',
    sortable: true,
    width: '4.25rem',
    align: 'left',
    cellClassName: 'tabular-nums',
    render: (row) => (row.scheduleVersionNo == null ? EMPTY : `v${row.scheduleVersionNo}`),
  },
  {
    key: 'status',
    label: '상태',
    width: '6.5rem',
    align: 'center',
    render: (row) => (
      <ScheduleStatusBadge code={row.status} label={responseLabel(row.statusLabel, SCHEDULE_STATUS, row.status)} />
    ),
  },
  {
    key: 'active',
    label: '사용중',
    width: '5.5rem',
    align: 'center',
    render: (row) => <ActiveBadge active={row.activeYn} />,
  },
  {
    key: 'lines',
    label: '회차 수',
    sortable: true,
    width: '5.5rem',
    align: 'left',
    render: (row) => int(row.lineCount),
  },
  {
    key: 'total',
    label: '예상 총액',
    sortable: true,
    width: '8.5rem',
    align: 'number',
    render: (row) => (
      <span className={isNegative(row.expectedTotal) ? 'is-negative-amount' : undefined}>{won(row.expectedTotal)}</span>
    ),
  },
  {
    key: 'policy',
    label: '정책버전',
    sortable: true,
    width: '13rem',
    render: (row) => (
      <CellDisclosure
        singleLine
        preview={<span className="tabular-nums">{row.policyVersionLabel || EMPTY}</span>}
        full={row.policyVersionLabel || EMPTY}
      />
    ),
  },
  {
    key: 'action',
    label: '상세',
    width: '6.5rem',
    align: 'left',
    render: (row) =>
      hasId(row) ? (
        <ContextLink data-guide-feature="features.schedule.schedule-list-page.context-link-2"
          className="button button-secondary schedule-row-button"
          to={detailHref(row, back)}
          aria-label={`${row.contractNo || '선택한 계약'} 회차 보기`}
        >
          회차 보기
        </ContextLink>
      ) : (
        <span className="button button-secondary schedule-row-button is-disabled" aria-disabled="true">
          회차 보기
        </span>
      ),
  },
]

export default function ScheduleListPage() {
  const location = useLocation()
  const { params, update } = useSearchParamsState(FILTER_KEYS)
  const criteria = criteriaFromParams(params)
  const list = useQuery(scheduleListQuery(criteria))
  const data = list.data
  const rows = data?.content ?? []
  const totalPages = data?.totalPages ?? 0
  const exporting = useMutation({
    mutationFn: () => exportCsv(listCsvPath(criteria), { filename: 'schedules.csv' }),
    // exportCsv 가 이미 공통 오류 Toast 를 띄운다.
    meta: { errorToast: false },
    onSuccess: () => toast('예상 스케줄 CSV를 내려받았습니다.', 'success', 3500),
  })

  // 범위를 넘은 page 는 1차처럼 마지막 페이지로 바꿔(기록 대체) 다시 조회한다.
  const outOfRange = data && !list.isPlaceholderData ? normalizePage(criteria.page, data.totalPages) : criteria.page
  useEffect(() => {
    if (outOfRange !== criteria.page)
      update({ page: outOfRange > 1 ? String(outOfRange) : '' }, { resetPage: false, replace: true })
  }, [outOfRange, criteria.page, update])

  const search = (filters: ScheduleFilters) => update({ ...filters })

  return (
    <div className="schedule-list-page">
      <header className="page-header schedule-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">예상 스케줄 목록</h1>
        </div>
        <div className="page-header-actions">
          <Button data-guide-feature="features.schedule.schedule-list-page.button" variant="secondary" loading={exporting.isPending} onClick={() => exporting.mutate()}>
            <span className="material-symbols-rounded" aria-hidden="true">
              download
            </span>
            CSV 내보내기
          </Button>
        </div>
      </header>

      {/* 뒤로·앞으로 이동으로 URL 이 바뀌면 입력값도 그 URL 기준으로 다시 채운다. */}
      <ScheduleFilter
        key={params.toString()}
        initial={criteria}
        onSearch={search}
        onReset={() => search(DEFAULT_FILTERS)}
      />

      <section data-guide-feature="features.schedule.schedule-list-page.section" className="surface schedule-results-card" aria-labelledby="schedule-results-title">
        <header className="surface-header schedule-results-header">
          <h2 id="schedule-results-title" className="surface-title">
            스케줄
          </h2>
          <p className="schedule-result-summary" aria-live="polite">
            <strong>{data ? int(data.totalElements ?? 0) : EMPTY}</strong>건 · 한 화면 {data?.size ?? PAGE_SIZE}행
          </p>
        </header>

        {hasDuplicateActiveOperational(rows) && (
          <div className="schedule-data-warning" role="alert">
            <span className="material-symbols-rounded" aria-hidden="true">
              warning
            </span>
            <p>같은 계약과 지급단계에 사용 중인 운영 스케줄이 중복되었습니다. 데이터 확인이 필요합니다.</p>
          </div>
        )}

        {list.isError ? (
          <div className="schedule-error-state" role="alert">
            <strong>예상 스케줄을 불러오지 못했습니다.</strong>
            <p>{errorText(list.error)}</p>
            <Button data-guide-feature="features.schedule.schedule-list-page.button-2" variant="secondary" onClick={() => void list.refetch()}>
              다시 시도
            </Button>
          </div>
        ) : (
          <DataTable
            className="schedule-table"
            viewportClassName="schedule-table-viewport"
            caption="예상 지급 스케줄 검색 결과"
            columns={columns(location.pathname + location.search)}
            selectedKeys={[params.get('selectedId') ?? '']}
            {...sortBinding(criteria.sort, SCHEDULE_SORT_KEYS, (sort) => update({ sort }))}
            rows={rows}
            rowKey={(row) =>
              String(row.scheduleHeaderId ?? `${row.contractNo}-${row.paymentStage}-${row.scheduleVersionNo}`)
            }
            emptyMessage={list.isPending ? '스케줄을 불러오는 중입니다.' : '조건에 맞는 예상 스케줄이 없습니다.'}
          />
        )}

        {data && !list.isError && (
          <ListPagination
            className="schedule-pagination"
            label="예상 스케줄 목록 페이지 선택"
            page={criteria.page}
            totalPages={totalPages}
            onPageChange={(page) => update({ page: String(page) }, { resetPage: false })}
          />
        )}
      </section>
    </div>
  )
}

function ScheduleFilter({
  initial,
  onSearch,
  onReset,
}: {
  initial: ScheduleFilters
  onSearch: (filters: ScheduleFilters) => void
  onReset: () => void
}) {
  const [filters, setFilters] = useState<ScheduleFilters>({
    contractNo: initial.contractNo,
    stage: initial.stage,
    regime: initial.regime,
    purpose: initial.purpose,
    status: initial.status,
  })
  const bind = (key: keyof ScheduleFilters) => ({
    id: `schedule-filter-${key}`,
    name: key,
    value: filters[key],
    onChange: (event: { target: { value: string } }) => setFilters({ ...filters, [key]: event.target.value }),
  })
  const options = (labels: Record<string, string>) =>
    Object.entries(labels).map(([code, label]) => (
      <option key={code} value={code}>
        {label}
      </option>
    ))
  return (
    <FilterBar
      className="schedule-filter-bar"
      wrapFields={false}
      resetFirst
      icons
      onSubmit={() => onSearch({ ...filters, contractNo: filters.contractNo.trim() })}
      onReset={onReset}
    >
      <div className="filter-fields schedule-filter-fields">
        <div className="filter-field schedule-filter-contract">
          <label className="filter-field-label" htmlFor="schedule-filter-contractNo">
            계약번호
          </label>
          <input data-guide-feature="features.schedule.schedule-list-page.input"
            className="field-control"
            type="search"
            placeholder="계약번호 입력"
            autoComplete="off"
            {...bind('contractNo')}
          />
        </div>
        <div className="filter-field">
          <label className="filter-field-label" htmlFor="schedule-filter-stage">
            지급단계
          </label>
          <select data-guide-feature="features.schedule.schedule-list-page.select" className="select-control" {...bind('stage')}>
            <option value="">전체</option>
            {options(PAYMENT_STAGE)}
          </select>
        </div>
        <div className="filter-field">
          <label className="filter-field-label" htmlFor="schedule-filter-regime">
            적용 체계
          </label>
          <select data-guide-feature="features.schedule.schedule-list-page.select-2" className="select-control" {...bind('regime')}>
            <option value="">전체</option>
            {options(SCHEDULE_REGIME)}
          </select>
        </div>
        <div className="filter-field">
          <label className="filter-field-label" htmlFor="schedule-filter-purpose">
            용도
          </label>
          <select data-guide-feature="features.schedule.schedule-list-page.select-3" className="select-control" {...bind('purpose')}>
            {options(SCHEDULE_PURPOSE)}
          </select>
        </div>
        <div className="filter-field">
          <label className="filter-field-label" htmlFor="schedule-filter-status">
            상태
          </label>
          <select data-guide-feature="features.schedule.schedule-list-page.select-4" className="select-control" {...bind('status')}>
            <option value="">전체</option>
            {options(SCHEDULE_STATUS)}
          </select>
        </div>
      </div>
    </FilterBar>
  )
}
