import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { date, int, usageRate, won } from '../../lib/format'
import { useWorkspaceStore } from '../../stores/workspace'
import { capInsurersQuery, capListQuery, capOrganizationsQuery, criteriaFromParams, FILTER_KEYS } from './api'
import type { CapAgent, CapCriteria, CapItem, CapStage } from './api'
import { CapDetailModal } from './CapDetailModal'
import { Amount, CapError, CapEvidence, CapStatus, CapUsage } from './badges'
import { monitoringStatus, STAGES, STATUSES, STATUS_LABELS } from './labels'
import './cap.css'

const summaryKeys = {
  NORMAL: 'normal',
  WARNING: 'warning',
  VIOLATION: 'violation',
  REVIEW_REQUIRED: 'reviewRequired',
} as const
const agentColumns: TableColumn<CapAgent>[] = [
  {
    key: 'agent',
    label: '설계사',
    width: '12rem',
    render: (row) => (
      <CellDisclosure
        preview={[row.agentName, row.agentCode].filter(Boolean).join(' · ') || '—'}
        full={[row.agentName, row.agentCode].filter(Boolean).join(' · ') || '—'}
      />
    ),
  },
  {
    key: 'org',
    label: '조직',
    width: '12rem',
    render: (row) => (
      <CellDisclosure
        preview={[row.organizationCode, row.organizationName].filter(Boolean).join(' · ') || '—'}
        full={[row.organizationCode, row.organizationName].filter(Boolean).join(' · ') || '—'}
      />
    ),
  },
  { key: 'count', label: '계약 수', align: 'number', render: (row) => `${int(row.contractCount)}건` },
  { key: 'limit', label: '한도 합계', align: 'number', render: (row) => <Amount value={row.limitAmountTotal} /> },
  { key: 'included', label: '산입 합계', align: 'number', render: (row) => <Amount value={row.includedAmountTotal} /> },
  {
    key: 'usage',
    label: '사용률',
    width: '14rem',
    render: (row) => <CapUsage value={row.usagePct} status={monitoringStatus(row).code} />,
  },
  { key: 'status', label: '모니터링', render: (row) => <CapStatus {...monitoringStatus(row)} /> },
]

/** CAP-W01. 검색조건·상세 ID는 URL에, 판정·전체 검색범위 집계는 서버에 둔다. */
export default function CapListPage() {
  const { params, update } = useSearchParamsState(FILTER_KEYS)
  const workspaceMonth = useWorkspaceStore((state) => state.month)
  const criteria = criteriaFromParams(params, workspaceMonth)
  const list = useQuery(capListQuery(criteria))
  // 오류가 난 뒤 이전 월의 KPI/집계를 현재 월의 데이터로 표시하지 않는다.
  const data = list.isError ? undefined : list.data
  const rows = data?.content ?? []
  const selected = params.get('capCheckId') || null
  const openDetail = (row: CapItem) => {
    if (row.capCheckId != null) update({ capCheckId: String(row.capCheckId) }, { resetPage: false })
  }
  const columns: TableColumn<CapItem>[] = [
    {
      key: 'contract',
      label: '계약번호',
      width: '12rem',
      render: (row) =>
        row.contractId == null ? (
          row.contractNo || '—'
        ) : (
          <Link to={`/contracts/${encodeURIComponent(String(row.contractId))}?tab=cap`}>{row.contractNo || '—'}</Link>
        ),
    },
    {
      key: 'stage',
      label: '지급단계',
      width: '9rem',
      render: (row) => row.paymentStageLabel || STAGES[row.paymentStage as keyof typeof STAGES] || '—',
    },
    { key: 'date', label: '기준일', width: '7rem', render: (row) => date(row.asOfDate) },
    {
      key: 'premium',
      label: '기준 보험료',
      width: '8rem',
      align: 'number',
      render: (row) => <Amount value={row.basePremiumAmount} />,
    },
    {
      key: 'refund',
      label: '해약환급금 합산',
      width: '9rem',
      align: 'number',
      render: (row) => (row.refund12mAmount === 0 ? '해당없음' : <Amount value={row.refund12mAmount} />),
    },
    {
      key: 'deduction',
      label: '준법경영비 공제',
      width: '9rem',
      align: 'number',
      render: (row) =>
        row.paymentStage === 'GA_TO_FC' ? '적용하지 않음' : <Amount value={row.complianceDeductionAmount} />,
    },
    {
      key: 'limit',
      label: (
        <>
          한도 <CapEvidence />
        </>
      ),
      width: '8rem',
      align: 'number',
      render: (row) => <Amount value={row.limitAmount} />,
    },
    {
      key: 'included',
      label: '산입금액',
      width: '9rem',
      align: 'number',
      render: (row) => (
        <Button
          variant="ghost"
          disabled={row.capCheckId == null}
          onClick={() => openDetail(row)}
          aria-label={`${row.contractNo || '선택한 계약'} 계산근거 열기`}
        >
          {row.resultStatus === 'NOT_APPLICABLE' ? '사유 보기' : <Amount value={row.includedAmount} />}
        </Button>
      ),
    },
    {
      key: 'remaining',
      label: '잔여',
      width: '8rem',
      align: 'number',
      render: (row) => <Amount value={row.remainingAmount} />,
    },
    {
      key: 'usage',
      label: '사용률',
      width: '14rem',
      render: (row) => <CapUsage value={row.usagePct} status={row.resultStatus} />,
    },
    {
      key: 'status',
      label: '판정',
      width: '6rem',
      render: (row) => <CapStatus code={row.resultStatus} label={row.resultStatusLabel} />,
    },
    {
      key: 'ruleset',
      label: '룰셋',
      width: '6rem',
      render: (row) => (row.capRuleSetId == null ? '—' : `ID ${row.capRuleSetId}`),
    },
  ]
  return (
    <div className="cap-react-page">
      <header className="page-header">
        <h1 className="page-title">1,200% 한도 검증 현황</h1>
      </header>
      <CapFilter
        key={`${criteria.month}|${criteria.stage}|${criteria.status}|${criteria.insurerId}|${criteria.orgId}|${criteria.contractNo}`}
        initial={criteria}
        onSearch={(filters) => update({ ...filters, size: String(criteria.size), capCheckId: '' })}
        onReset={() => update({ ...FILTER_KEYS, month: workspaceMonth ?? '', size: '20' })}
      />
      <div className="cap-react-kpis" aria-busy={list.isFetching}>
        {Object.entries(STATUSES)
          .filter(([code]) => code !== 'NOT_APPLICABLE')
          .map(([code, label]) => (
            <button
              key={code}
              type="button"
              className={`surface cap-react-kpi cap-react-kpi-${code}`}
              aria-pressed={criteria.status === code}
              aria-label={`${code === 'REVIEW_REQUIRED' ? '추가 확인 필요' : `저장 판정 ${label}`} ${data ? int(data.summary?.[summaryKeys[code as keyof typeof summaryKeys]] ?? 0) : '—'}`}
              onClick={() => update({ status: criteria.status === code ? '' : code, capCheckId: '' })}
            >
              <span>{label}</span>
              <strong>{data ? int(data.summary?.[summaryKeys[code as keyof typeof summaryKeys]] ?? 0) : '—'}</strong>
              <small>건 · {code === 'REVIEW_REQUIRED' ? '추가 확인 필요' : `저장 판정 ${label}`}</small>
            </button>
          ))}
      </div>
      <section className="surface">
        <header className="surface-header">
          <h2 className="surface-title">지급단계별 한도 게이지</h2>
          <span>전체 검색범위 집계</span>
        </header>
        <div className="cap-react-stages">
          {data?.stageSummary?.length ? (
            data.stageSummary.map((stage) => <StageCard key={stage.paymentStage} stage={stage} />)
          ) : (
            <p>
              {list.isPending
                ? '지급단계별 집계를 불러오는 중입니다.'
                : list.isError
                  ? '지급단계별 집계를 불러오지 못했습니다.'
                  : '조건에 맞는 지급단계별 집계가 없습니다.'}
            </p>
          )}
        </div>
      </section>
      <section className="surface">
        <header className="surface-header">
          <h2 className="surface-title">계약별 판정 목록</h2>
          <span>총 {data ? int(data.totalElements ?? 0) : '—'}건</span>
        </header>
        {list.isError ? (
          <CapError error={list.error} retry={() => void list.refetch()} />
        ) : (
          <DataTable
            caption="계약별 판정 목록"
            columns={columns}
            rows={rows}
            rowKey={(row) => String(row.capCheckId)}
            onRowActivate={openDetail}
            emptyMessage={list.isPending ? '판정 목록을 불러오는 중입니다.' : '조건에 맞는 계약 판정이 없습니다.'}
          />
        )}
        <ListPagination
          className="cap-react-pagination"
          label="1,200% 한도 검증 목록 페이지 선택"
          page={criteria.page}
          totalPages={data?.totalPages ?? 0}
          onPageChange={(page) => update({ page: String(page) }, { resetPage: false })}
          controls={
            <label>
              한 화면{' '}
              <select
                className="select-control"
                aria-label="한 화면 행 수"
                value={criteria.size}
                onChange={(event) => update({ size: event.target.value })}
              >
                {[10, 20, 50].map((size) => (
                  <option key={size} value={size}>
                    {size}행
                  </option>
                ))}
              </select>
            </label>
          }
        />
      </section>
      <section className="surface">
        <header className="surface-header">
          <h2 className="surface-title">설계사별 합계</h2>
          <span>모니터링 지표 · 규제 판정 아님</span>
        </header>
        <DataTable
          caption="설계사별 모니터링 합계"
          columns={agentColumns}
          rows={data?.agentSummary ?? []}
          rowKey={(row) => String(row.agentId)}
          emptyMessage={
            list.isPending
              ? '설계사별 모니터링 지표를 불러오는 중입니다.'
              : list.isError
                ? '설계사별 모니터링 지표를 불러오지 못했습니다.'
                : '조건에 맞는 GA → 설계사 단계 모니터링 대상이 없습니다.'
          }
        />
      </section>
      <CapDetailModal capCheckId={selected} onClose={() => update({ capCheckId: '' }, { resetPage: false })} />
    </div>
  )
}

function StageCard({ stage }: { stage: CapStage }) {
  const status = monitoringStatus(stage)
  return (
    <article className="cap-react-stage">
      <header>
        <h3>{stage.paymentStageLabel || STAGES[stage.paymentStage as keyof typeof STAGES]}</h3>
        <CapStatus {...status} />
      </header>
      <p>{stage.paymentStage}</p>
      <dl>
        <div>
          <dt>계약 수</dt>
          <dd>{int(stage.contractCount)}건</dd>
        </div>
        <div>
          <dt>한도 합계</dt>
          <dd>
            <Amount value={stage.limitAmountTotal} />
          </dd>
        </div>
        <div>
          <dt>산입 합계</dt>
          <dd>
            <Amount value={stage.includedAmountTotal} />
          </dd>
        </div>
      </dl>
      <CapUsage value={stage.usagePct} status={status.code} />
      <p>
        {stage.paymentStage === 'GA_TO_FC'
          ? '준법경영비 공제를 적용하지 않습니다.'
          : `준법경영비 공제 합계 ${won(stage.complianceDeductionAmountTotal)}`}
      </p>
      <p>
        {stage.worstContractNo
          ? `최고 사용률 ${stage.worstContractNo} · ${usageRate(stage.worstUsagePct)}%`
          : '판정 대상 계약이 없습니다.'}
      </p>
    </article>
  )
}

type Filters = Omit<CapCriteria, 'page' | 'size'>
function CapFilter({
  initial,
  onSearch,
  onReset,
}: {
  initial: CapCriteria
  onSearch: (filters: Filters) => void
  onReset: () => void
}) {
  const [filters, setFilters] = useState<Filters>({
    month: initial.month,
    stage: initial.stage,
    status: initial.status,
    insurerId: initial.insurerId,
    orgId: initial.orgId,
    contractNo: initial.contractNo,
  })
  const insurers = useQuery(capInsurersQuery())
  const organizations = useQuery(capOrganizationsQuery(filters.month))
  const bind = (name: keyof Filters) => ({
    id: `cap-filter-${name}`,
    value: filters[name],
    onChange: (event: { target: { value: string } }) => setFilters({ ...filters, [name]: event.target.value }),
  })
  const options = (labels: Record<string, string>) =>
    Object.entries(labels).map(([code, label]) => (
      <option key={code} value={code}>
        {label}
      </option>
    ))
  return (
    <section className="surface">
      <header className="surface-header">
        <h2 className="surface-title">검색 조건</h2>
      </header>
      <FilterBar
        className="cap-react-filter"
        resetFirst
        icons
        onSubmit={() => onSearch({ ...filters, contractNo: filters.contractNo.trim() })}
        onReset={onReset}
      >
        <div className="filter-field">
          <label htmlFor="cap-filter-month">기준월</label>
          <input className="field-control" type="month" required {...bind('month')} />
        </div>
        <div className="filter-field">
          <label htmlFor="cap-filter-stage">지급단계</label>
          <select className="select-control" {...bind('stage')}>
            <option value="">전체</option>
            {options(STAGES)}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="cap-filter-status">판정</label>
          <select className="select-control" {...bind('status')}>
            <option value="">전체</option>
            {options(STATUS_LABELS)}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="cap-filter-insurerId">보험회사</label>
          <select className="select-control" disabled={!insurers.data || insurers.isError} {...bind('insurerId')}>
            <option value="">{insurers.isPending ? '불러오는 중' : '전체'}</option>
            {insurers.data?.content?.map((row) => (
              <option key={row.insurerId} value={row.insurerId} disabled={row.activeYn === false}>
                {row.insurerCode} · {row.insurerName}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="cap-filter-orgId">조직</label>
          <select className="select-control" disabled={!organizations.data || organizations.isError} {...bind('orgId')}>
            <option value="">{organizations.isPending ? '불러오는 중' : '전체'}</option>
            {organizations.data?.content?.map((row) => (
              <option key={row.organizationId} value={row.organizationId} disabled={row.activeYn === false}>
                {row.organizationCode} · {row.organizationName}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="cap-filter-contractNo">계약번호</label>
          <input type="search" className="field-control" placeholder="계약번호 입력" {...bind('contractNo')} />
        </div>
        {insurers.isError && <CapError error={insurers.error} retry={() => void insurers.refetch()} />}
        {organizations.isError && <CapError error={organizations.error} retry={() => void organizations.refetch()} />}
      </FilterBar>
    </section>
  )
}
