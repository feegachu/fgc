import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { exportCsv } from '../../lib/exportCsv'
import { date, int, today } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { usePermission } from '../auth/useAuth'
import { useAgentOptions, useInsurerOptions, useOrganizationOptions, useProductOptions } from '../base/api'
import { Amount, CapStatus } from '../cap/badges'
import { STATUSES } from '../cap/labels'
import { QueryState } from '../reference/ReferenceUI'
import { contractListQuery, criteriaFromParams, listCsvPath, normalizePage, PAGE_SIZE } from './api'
import type { ContractCriteria, ContractSummary } from './api'
import { contractStatusLabels } from './labels'
import { ContractStatusBadge, DataOriginBadge } from './badges'
import { contractErrorText } from './detailState'
import './list.css'

const FILTER_KEYS = {
  contractNo: '',
  insurerId: '',
  productOfferingId: '',
  agentId: '',
  orgId: '',
  contractDateFrom: '',
  contractDateTo: '',
  currentStatus: '',
  capResultStatus: '',
  page: '',
  size: '',
}
const detailHref = (row: ContractSummary) => `/contracts/${encodeURIComponent(String(row.contractId))}`
const textCell = (value?: string) => <CellDisclosure singleLine preview={value || '-'} full={value || '-'} />
const columns: TableColumn<ContractSummary>[] = [
  {
    key: 'contractNo',
    label: '계약번호',
    width: '13rem',
    cellClassName: 'contract-number-cell',
    render: (row) =>
      row.contractId ? <Link to={detailHref(row)}>{row.contractNo || '-'}</Link> : row.contractNo || '-',
  },
  { key: 'insurerName', label: '보험회사', width: '10rem', render: (row) => textCell(row.insurerName) },
  { key: 'productName', label: '상품명', width: '12rem', render: (row) => textCell(row.productName) },
  { key: 'contractDate', label: '계약일', width: '7rem', render: (row) => date(row.contractDate) },
  {
    key: 'premium',
    label: '월납환산 보험료',
    width: '10rem',
    align: 'number',
    render: (row) => <Amount value={row.monthlyEquivalentFirstPremium} />,
  },
  { key: 'agent', label: '모집 설계사', width: '10rem', render: (row) => textCell(row.agentIdName) },
  {
    key: 'status',
    label: '계약상태',
    width: '7rem',
    align: 'center',
    render: (row) => <ContractStatusBadge code={row.contractStatus} label={row.contractStatusLabel} />,
  },
  {
    key: 'cap',
    label: '1,200% 판정',
    width: '8rem',
    align: 'center',
    render: (row) =>
      row.capResultStatus ? <CapStatus code={row.capResultStatus} label={row.capResultStatusLabel} /> : '-',
  },
  {
    key: 'origin',
    label: '데이터 출처',
    width: '8rem',
    align: 'center',
    render: (row) => <DataOriginBadge code={row.dataOrigin} label={row.dataOriginLabel} />,
  },
]

export default function ContractListPage() {
  const { params, update } = useSearchParamsState(FILTER_KEYS)
  const criteria = criteriaFromParams(params)
  const list = useQuery(contractListQuery(criteria))
  const canProcess = usePermission('canProcess')
  const navigate = useNavigate()
  const exporting = useMutation({
    mutationFn: () => exportCsv(listCsvPath(criteria), { filename: 'contracts.csv', notifyError: false }),
    meta: { errorToast: false },
    onSuccess: () => toast('CSV 파일을 내려받았습니다.', 'success'),
    onError: (error) => toast(contractErrorText(error, 'CSV 내보내기에 실패했습니다.'), 'error'),
  })
  const page = list.data && !list.isPlaceholderData ? normalizePage(criteria.page, list.data.totalPages) : criteria.page
  useEffect(() => {
    if (page !== criteria.page) update({ page: String(page) }, { resetPage: false, replace: true })
  }, [page, criteria.page, update])
  return (
    <div className="contract-list-page">
      <header className="page-header">
        <h1 className="page-title">보험계약 목록</h1>
        <div className="page-header-actions">
          <Button variant="secondary" loading={exporting.isPending} onClick={() => exporting.mutate()}>
            CSV 내보내기
          </Button>
          {canProcess && (
            <Link className="button button-primary" to="/contracts/new">
              신규 등록
            </Link>
          )}
        </div>
      </header>
      <section className="surface" aria-labelledby="contract-filter-title">
        <header className="surface-header">
          <h2 className="surface-title" id="contract-filter-title">
            검색 조건
          </h2>
        </header>
        <ContractFilters
          key={JSON.stringify(criteria)}
          initial={criteria}
          onSearch={(values) => update(values)}
          onReset={() => update(FILTER_KEYS)}
        />
      </section>
      <section className="surface" aria-labelledby="contract-results-title">
        <header className="surface-header">
          <h2 className="surface-title" id="contract-results-title">
            검색 결과
          </h2>
          <p>
            {int(list.data?.totalElements ?? 0)}건 · 한 화면 {PAGE_SIZE}행
          </p>
        </header>
        <QueryState loading={list.isPending} error={list.error} />
        {list.isError && (
          <Button variant="secondary" onClick={() => void list.refetch()}>
            다시 시도
          </Button>
        )}
        {!list.isError && !list.isPending && (
          <>
            <DataTable
              caption="보험계약 검색 결과"
              columns={columns}
              rows={list.data?.content ?? []}
              rowKey={(row) => String(row.contractId)}
              onRowActivate={(row) => {
                if (row.contractId) void navigate(detailHref(row))
              }}
            />
            <div className="contract-list-pagination">
              <span>
                {criteria.page} / {Math.max(1, list.data?.totalPages ?? 0)} 페이지
              </span>
              <Pagination
                page={criteria.page}
                totalPages={list.data?.totalPages ?? 0}
                onPageChange={(next) => update({ page: String(next) }, { resetPage: false })}
              />
            </div>
          </>
        )}
      </section>
    </div>
  )
}

function ContractFilters({
  initial,
  onSearch,
  onReset,
}: {
  initial: ContractCriteria
  onSearch: (values: Record<string, string>) => void
  onReset: () => void
}) {
  const [draft, setDraft] = useState(() =>
    Object.fromEntries(
      Object.entries(initial)
        .filter(([key]) => key !== 'page' && key !== 'size')
        .map(([key, value]) => [key, String(value)]),
    ),
  )
  const asOf = today()
  const insurers = useInsurerOptions()
  const organizations = useOrganizationOptions(asOf)
  const products = useProductOptions(asOf, draft.insurerId)
  const agents = useAgentOptions(asOf, draft.orgId)
  const set = (key: string, value: string) =>
    setDraft((current) => ({
      ...current,
      [key]: value,
      ...(key === 'insurerId' ? { productOfferingId: '' } : {}),
      ...(key === 'orgId' ? { agentId: '' } : {}),
    }))
  const input = (key: string, label: string, type = 'text') => (
    <Field key={key} label={label}>
      <input type={type} value={draft[key]} onChange={(event) => set(key, event.target.value)} />
    </Field>
  )
  const select = (
    key: string,
    label: string,
    options: { value: string; label: string; disabled?: boolean }[],
    query?: { isFetching: boolean; error: Error | null; refetch: () => unknown },
    placeholder = '전체',
    disabled = false,
  ) => (
    <div key={key}>
      <Field label={label}>
        <select
          value={draft[key]}
          disabled={disabled || query?.isFetching || Boolean(query?.error)}
          aria-busy={query?.isFetching}
          onChange={(event) => set(key, event.target.value)}
        >
          <option value="">{query?.isFetching ? '불러오는 중' : placeholder}</option>
          {draft[key] && !options.some((option) => option.value === draft[key]) && (
            <option value={draft[key]}>선택된 ID: {draft[key]}</option>
          )}
          {options.map((option) => (
            <option key={option.value} value={option.value} disabled={option.disabled}>
              {option.label}
            </option>
          ))}
        </select>
      </Field>
      {query?.error && (
        <>
          <QueryState error={query.error} />
          <Button variant="ghost" onClick={() => query.refetch()} aria-label={`${label} 목록 다시 불러오기`}>
            다시 시도
          </Button>
        </>
      )}
    </div>
  )
  return (
    <FilterBar
      className="contract-list-filters"
      onSubmit={() => onSearch(draft)}
      onReset={() => {
        setDraft(Object.fromEntries(Object.keys(draft).map((key) => [key, ''])))
        onReset()
      }}
    >
      {input('contractNo', '계약번호')}
      {select(
        'insurerId',
        '보험회사',
        (insurers.data ?? []).map((row) => ({
          value: String(row.insurerId),
          label: `${row.insurerCode} · ${row.insurerName}`,
          disabled: row.activeYn === false,
        })),
        insurers,
      )}
      {select(
        'productOfferingId',
        '상품',
        (products.data ?? []).map((row) => ({
          value: String(row.productOfferingId),
          label: `${row.productName} · ${row.offeringVersion}`,
        })),
        products,
        draft.insurerId ? '전체' : '보험회사를 선택하세요',
        !draft.insurerId,
      )}
      {select(
        'agentId',
        '모집 설계사',
        (agents.data ?? [])
          .filter((row) => row.activeYn !== false && row.agentStatus === 'ACTIVE')
          .map((row) => ({ value: String(row.agentId), label: `${row.agentCode} · ${row.agentName}` })),
        agents,
      )}
      {select(
        'orgId',
        '조직',
        (organizations.data ?? []).map((row) => ({
          value: String(row.organizationId),
          label: `${row.organizationCode} · ${row.organizationName}`,
          disabled: row.activeYn === false,
        })),
        organizations,
      )}
      {input('contractDateFrom', '계약일 시작', 'date')}
      {input('contractDateTo', '계약일 끝', 'date')}
      {select(
        'currentStatus',
        '계약상태',
        Object.entries(contractStatusLabels).map(([value, label]) => ({ value, label })),
      )}
      {select(
        'capResultStatus',
        '1,200% 판정',
        Object.entries(STATUSES).map(([value, label]) => ({ value, label })),
      )}
    </FilterBar>
  )
}
