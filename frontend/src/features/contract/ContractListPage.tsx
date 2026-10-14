import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { EvidenceLink } from '../../components/EvidenceLink'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { exportCsv } from '../../lib/exportCsv'
import { date, int, today } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { usePermission } from '../auth/useAuth'
import { useAgentOptions, useInsurerOptions, useOrganizationOptions, useProductOptions } from '../base/api'
import type { Agent } from '../base/api'
import { Amount, CapStatus } from '../cap/badges'
import { STATUSES } from '../cap/labels'
import { QueryState } from '../reference/ReferenceUI'
import { contractListQuery, criteriaFromParams, listCsvPath, normalizePage, PAGE_SIZE } from './api'
import type { ContractCriteria, ContractSummary } from './api'
import { contractStatusLabels } from './labels'
import { ContractStatusBadge, DataOriginBadge } from './badges'
import { contractErrorText } from './detailState'
import './legacy.css'
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
const agentStatusLabels = { ACTIVE: '활동', INACTIVE: '비활동', TERMINATED: '해촉' }
function agentOptionLabel(agent: Agent) {
  const status = agent.agentStatusLabel || (agent.agentStatus ? agentStatusLabels[agent.agentStatus] : '')
  const details = [status, agent.activeYn === false ? '기준정보 사용중지' : ''].filter(Boolean)
  return `${agent.agentCode} · ${agent.agentName}${details.length ? ` (${details.join(' · ')})` : ''}`
}
const columns: TableColumn<ContractSummary>[] = [
  {
    key: 'contractNo',
    label: '계약번호',
    width: '12rem',
    cellClassName: 'contract-number-cell',
    render: (row) =>
      row.contractId ? <Link to={detailHref(row)}>{row.contractNo || '-'}</Link> : row.contractNo || '-',
  },
  {
    key: 'insurerName',
    label: '보험회사',
    width: '6.5rem',
    cellClassName: 'contract-disclosure-cell',
    render: (row) => textCell(row.insurerName),
  },
  {
    key: 'productName',
    label: '상품명',
    width: '11.75rem',
    cellClassName: 'contract-disclosure-cell',
    render: (row) => textCell(row.productName),
  },
  { key: 'contractDate', label: '계약일', width: '5.75rem', render: (row) => date(row.contractDate) },
  {
    key: 'premium',
    label: (
      <>
        <span>월납환산 초회보험료</span>
        <EvidenceLink label="월납환산 초회보험료 설명 보기" icon="help">
          주기 보험료를 월 기준으로 환산한 값입니다. 1,200% 한도 계산 기준으로 사용합니다.
        </EvidenceLink>
      </>
    ),
    width: '10.25rem',
    align: 'number',
    render: (row) => <Amount value={row.monthlyEquivalentFirstPremium} />,
  },
  {
    key: 'agent',
    label: '모집 설계사',
    width: '8.5rem',
    cellClassName: 'contract-disclosure-cell',
    render: (row) => textCell(row.agentIdName),
  },
  {
    key: 'status',
    label: '계약상태',
    width: '5.25rem',
    align: 'center',
    render: (row) => <ContractStatusBadge code={row.contractStatus} label={row.contractStatusLabel} />,
  },
  {
    key: 'cap',
    label: '1,200% 판정',
    width: '7rem',
    align: 'center',
    render: (row) =>
      row.capResultStatus ? <CapStatus code={row.capResultStatus} label={row.capResultStatusLabel} /> : '-',
  },
  {
    key: 'origin',
    label: '데이터 출처',
    width: '6rem',
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
      <header className="page-header contract-page-header">
        <h1 className="page-title">보험계약 목록</h1>
        <div className="page-header-actions">
          <Button variant="secondary" loading={exporting.isPending} onClick={() => exporting.mutate()}>
            {!exporting.isPending && (
              <span className="material-symbols-rounded" aria-hidden="true">
                download
              </span>
            )}
            CSV 내보내기
          </Button>
          {canProcess && (
            <Link className="button button-primary" to="/contracts/new">
              <span className="material-symbols-rounded" aria-hidden="true">
                add
              </span>
              신규 등록
            </Link>
          )}
        </div>
      </header>
      <section className="surface contract-filter-card" aria-labelledby="contract-filter-title">
        <ContractFilters
          key={JSON.stringify(criteria)}
          initial={criteria}
          onSearch={(values) => update(values)}
          onReset={() => update(FILTER_KEYS)}
        />
      </section>
      <section className="surface contract-results-card" aria-labelledby="contract-results-title">
        <header className="surface-header contract-results-header">
          <h2 className="surface-title" id="contract-results-title">
            검색 결과
          </h2>
          <p className="contract-result-summary">
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
              className="contract-table"
              viewportClassName="contract-table-viewport"
              caption="보험계약 검색 결과"
              columns={columns}
              rows={list.data?.content ?? []}
              rowKey={(row) => String(row.contractId)}
              onRowActivate={(row) => {
                if (row.contractId) void navigate(detailHref(row))
              }}
            />
            <div className="contract-pagination">
              <span>
                {criteria.page} / {Math.max(1, list.data?.totalPages ?? 0)} 페이지
              </span>
              <Pagination
                legacy
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
  const [agentOptionsRequested, setAgentOptionsRequested] = useState(Boolean(initial.agentId || initial.orgId))
  const insurers = useInsurerOptions()
  const organizations = useOrganizationOptions(asOf, true, true)
  // 과거 계약 검색에는 판매가 종료된 버전도 포함한다. 등록·수정은 기본 조회를 사용한다.
  const products = useProductOptions(asOf, draft.insurerId, true, true, true)
  // 전체 설계사 이력은 많을 수 있으므로 목록 진입 시에는 조회하지 않는다.
  // 조직/설계사 URL 필터가 있거나 사용자가 선택 목록을 열 때 조회한다.
  const agents = useAgentOptions(asOf, draft.orgId, agentOptionsRequested, true, true)
  const set = (key: string, value: string) => {
    if (key === 'orgId' && value) setAgentOptionsRequested(true)
    setDraft((current) => ({
      ...current,
      [key]: value,
      ...(key === 'insurerId' ? { productOfferingId: '' } : {}),
      ...(key === 'orgId' ? { agentId: '' } : {}),
    }))
  }
  const input = (key: string, label: string, type = 'text', placeholder?: string) => (
    <Field key={key} label={label} className="filter-field">
      <input
        type={type}
        placeholder={placeholder}
        value={draft[key]}
        onChange={(event) => set(key, event.target.value)}
      />
    </Field>
  )
  const select = (
    key: string,
    label: string,
    options: { value: string; label: string; disabled?: boolean }[],
    query?: { isFetching: boolean; error: Error | null; refetch: () => unknown },
    placeholder = '전체',
    disabled = false,
    onFocus?: () => void,
    blockWhileFetching = true,
  ) => (
    <div key={key} className="filter-field">
      <Field label={label}>
        <select
          className="select-control"
          value={draft[key]}
          disabled={disabled || (blockWhileFetching && query?.isFetching) || Boolean(query?.error)}
          aria-busy={query?.isFetching}
          onFocus={onFocus}
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
    <>
      <header className="contract-section-header">
        <h2 className="surface-title" id="contract-filter-title">
          검색 조건
        </h2>
        <Button
          variant="ghost"
          className="contract-reset-button"
          onClick={() => {
            setDraft(Object.fromEntries(Object.keys(draft).map((key) => [key, ''])))
            setAgentOptionsRequested(false)
            onReset()
          }}
        >
          초기화
        </Button>
      </header>
      <FilterBar className="contract-filter-bar" wrapFields={false} icons onSubmit={() => onSearch(draft)}>
        <div className="filter-fields contract-filter-fields">
          {input('contractNo', '계약번호', 'text', '계약번호 입력')}
          {select(
            'insurerId',
            '보험회사',
            (insurers.data ?? []).map((row) => ({
              value: String(row.insurerId),
              label: `${row.insurerCode} · ${row.insurerName}${row.activeYn === false ? ' (사용중지)' : ''}`,
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
            (agents.data ?? []).map((row) => ({ value: String(row.agentId), label: agentOptionLabel(row) })),
            agents,
            '전체',
            false,
            () => setAgentOptionsRequested(true),
            false,
          )}
          {select(
            'orgId',
            '조직',
            (organizations.data ?? []).map((row) => ({
              value: String(row.organizationId),
              label: `${row.organizationCode} · ${row.organizationName}${row.activeYn === false ? ' (사용중지)' : ''}`,
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
        </div>
      </FilterBar>
    </>
  )
}
