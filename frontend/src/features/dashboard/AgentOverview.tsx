import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { DataTable } from '../../components/DataTable'
import { EvidenceLink } from '../../components/EvidenceLink'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { KpiCard } from '../../components/KpiCard'
import { ListPagination } from '../../components/ListPagination'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { int, today } from '../../lib/format'
import { withMonth } from '../../stores/workspace'
import { useAgents } from '../base/api'
import type { Agent } from '../base/api'
import { positivePage, validDate } from '../contract/api'
import { homeSummaryQueryOptions } from './api'
import type { HomeAttentionAgent, HomeBranch } from './api'
import { RegionError, UpdatedAt } from './RegionStatus'

const SEARCH_PAGE_SIZE = 10
const PARAMS = { asOf: '', agentKeyword: '', branchId: '', agentPage: '' }

const reasonText = (agent: HomeAttentionAgent) =>
  agent.reasons?.map((reason) => `${reason.label} ${int(reason.count)}건`).join(', ')

const branchColumns: TableColumn<HomeBranch>[] = [
  { key: 'name', label: '지점', render: (branch) => branch.name ?? '-' },
  { key: 'agentCount', label: '설계사', align: 'number', width: '4.5rem', render: (branch) => `${int(branch.agentCount)}명` },
  {
    key: 'activeContractCount',
    label: '유효 계약',
    align: 'number',
    width: '5.5rem',
    render: (branch) => `${int(branch.activeContractCount)}건`,
  },
  {
    key: 'attentionCount',
    label: '확인할 설계사',
    align: 'number',
    width: '6.5rem',
    render: (branch) => `${int(branch.attentionCount)}명`,
  },
]

/** 설계사를 고르면 기존 계약 목록을 그 설계사와 기준일까지의 계약 조건으로 연다. 화면의 연결 계약 수와 목록 건수가 같다. */
function AgentLink({
  id,
  name,
  code,
  statusNote,
  asOf,
  month,
}: {
  id?: number
  name?: string
  code?: string
  statusNote?: string
  asOf: string
  month: string
}) {
  return (
    <Link
      data-guide-feature="features.dashboard.agent-overview.contracts-link"
      to={withMonth(`/contracts?agentId=${id}&contractDateTo=${asOf}`, month)}
      aria-label={`${name} (${code}) 계약 목록 열기`}
    >
      {name ?? '-'}
      {statusNote ? ` (${statusNote})` : ''}
    </Link>
  )
}

export function AgentOverview({ month }: { month: string }) {
  const { values, update } = useSearchParamsState(PARAMS)
  const asOf = validDate(values.asOf) ? values.asOf : today()
  const [typedAsOf, setTypedAsOf] = useState<string | null>(null)
  const summary = useQuery(homeSummaryQueryOptions(asOf))
  const data = summary.data

  const attentionColumns: TableColumn<HomeAttentionAgent>[] = [
    {
      key: 'name',
      label: '이름',
      width: '7rem',
      render: (agent) => (
        <AgentLink
          id={agent.agentId}
          name={agent.name}
          code={agent.code}
          statusNote={agent.status !== 'ACTIVE' ? agent.statusLabel : agent.activeYn === false ? '사용 중지' : undefined}
          asOf={asOf}
          month={month}
        />
      ),
    },
    { key: 'code', label: '설계사 코드', width: '7rem', cellClassName: 'tabular-nums', render: (agent) => agent.code ?? '-' },
    { key: 'branch', label: '소속 지점', width: '7rem', render: (agent) => agent.branch ?? '-' },
    {
      key: 'contractCount',
      label: '연결 계약',
      align: 'number',
      width: '5rem',
      render: (agent) => `${int(agent.contractCount)}건`,
    },
    { key: 'reasons', label: '확인 사유', cellClassName: 'agent-reason-cell', render: reasonText },
  ]

  return (
    <section
      data-guide-feature="features.dashboard.agent-overview.section"
      className="surface agent-overview"
      aria-labelledby="agent-overview-title"
    >
      <header className="surface-header agent-overview-header">
        <h2 id="agent-overview-title" className="surface-title">
          GA·설계사 현황 {data?.basis && <EvidenceLink label="현황 집계 기준" icon="help">{data.basis}</EvidenceLink>}
        </h2>
        <div className="agent-overview-basis">
          <Field label="현황 기준일" className="agent-overview-date">
            <input
              data-guide-feature="features.dashboard.agent-overview.as-of"
              type="date"
              value={typedAsOf ?? asOf}
              onChange={(event) => {
                const value = event.target.value
                // 연도를 한 자리씩 치는 동안의 값(0002, 0020, 0202)이나 지운 칸으로 조회하지 않는다.
                setTypedAsOf(validDate(value) && value >= '1000' ? null : value)
                if (validDate(value) && value >= '1000')
                  update({ asOf: value, agentPage: '' }, { resetPage: false, replace: true })
              }}
              onBlur={() => setTypedAsOf(null)}
            />
          </Field>
          {!summary.isError && <UpdatedAt at={summary.dataUpdatedAt} />}
        </div>
      </header>
      {summary.isError ? (
        <RegionError
          error={summary.error}
          fallback="설계사 현황을 불러오지 못했습니다."
          updatedAt={summary.dataUpdatedAt}
          busy={summary.isFetching}
          onRetry={() => void summary.refetch()}
        />
      ) : summary.isPending ? (
        <div role="status" className="agent-overview-loading">
          <span className="visually-hidden">설계사 현황을 불러오는 중입니다.</span>
          <span className="skeleton-line skeleton-line-value" aria-hidden="true" />
          <span className="skeleton-line" aria-hidden="true" />
          <span className="skeleton-line" aria-hidden="true" />
        </div>
      ) : (
        <div className="agent-overview-body">
          <div className="kpi-grid kpi-group agent-overview-counts">
            <KpiCard
              guideId="features.dashboard.agent-overview.registered"
              label="등록 설계사"
              value={data?.agents?.registered ?? '-'}
              unit="명"
              footer="기준일에 위촉 중"
            />
            <KpiCard
              guideId="features.dashboard.agent-overview.active"
              label="활동 설계사"
              value={data?.agents?.active ?? '-'}
              unit="명"
              footer="등록 중 활동 상태"
            />
            <KpiCard
              guideId="features.dashboard.agent-overview.terminated"
              label="해촉 설계사"
              value={data?.agents?.terminated ?? '-'}
              unit="명"
              footer="기준일 전에 위촉 종료"
            />
          </div>
          <div className="agent-overview-tables">
            <section data-guide-feature="features.dashboard.agent-overview.branches" aria-labelledby="branch-status-title">
              <h3 id="branch-status-title" className="agent-overview-subtitle">
                지점별 현황
              </h3>
              <DataTable
                className="agent-branch-table"
                caption="지점별 설계사와 유효 계약 수"
                columns={branchColumns}
                rows={data?.branches ?? []}
                rowKey={(branch) => String(branch.organizationId)}
                emptyMessage="표시할 지점이 없습니다"
              />
            </section>
            <section data-guide-feature="features.dashboard.agent-overview.attention" aria-labelledby="attention-agents-title">
              <h3 id="attention-agents-title" className="agent-overview-subtitle">
                확인할 설계사
                {data?.attentionAgents?.length ? (
                  <span className="agent-overview-total tabular-nums">
                    {` 전체 ${int(data.attentionTotal)}명 중 상위 ${int(data.attentionAgents.length)}명`}
                  </span>
                ) : null}
              </h3>
              <DataTable
                className="agent-attention-table"
                caption="확인할 설계사 상위 10명"
                columns={attentionColumns}
                rows={data?.attentionAgents ?? []}
                rowKey={(agent) => String(agent.agentId)}
                emptyMessage="확인할 설계사가 없습니다"
              />
            </section>
          </div>
        </div>
      )}
      <AgentSearch
        asOf={asOf}
        month={month}
        branches={data?.branches}
        keyword={values.agentKeyword}
        branchId={values.branchId}
        page={positivePage(values.agentPage)}
        onSearch={(agentKeyword, branchId) => update({ agentKeyword, branchId, agentPage: '' }, { resetPage: false })}
        onPage={(next) => update({ agentPage: String(next) }, { resetPage: false })}
      />
    </section>
  )
}

function AgentSearch({
  asOf,
  month,
  branches,
  keyword,
  branchId,
  page,
  onSearch,
  onPage,
}: {
  asOf: string
  month: string
  branches?: HomeBranch[]
  keyword: string
  branchId: string
  page: number
  onSearch: (keyword: string, branchId: string) => void
  onPage: (page: number) => void
}) {
  const searched = Boolean(keyword || branchId)
  // 이름 600개를 한 번에 나열하지 않는다. 조건이 있을 때만 한 쪽씩 찾고, 해촉 설계사도 찾을 수 있게 한다.
  const result = useAgents(
    { asOf, keyword, branchId, includeTerminated: true, page, size: SEARCH_PAGE_SIZE },
    searched,
  )
  const columns: TableColumn<Agent>[] = [
    {
      key: 'name',
      label: '이름',
      render: (agent) => (
        <AgentLink id={agent.agentId} name={agent.agentName} code={agent.agentCode} asOf={asOf} month={month} />
      ),
    },
    { key: 'code', label: '설계사 코드', cellClassName: 'tabular-nums', render: (agent) => agent.agentCode ?? '-' },
    { key: 'organization', label: '소속 조직', render: (agent) => agent.organizationName ?? '-' },
    { key: 'status', label: '상태', width: '5rem', render: (agent) => agent.agentStatusLabel ?? '-' },
  ]
  return (
    <section
      data-guide-feature="features.dashboard.agent-overview.search"
      className="agent-search"
      aria-labelledby="agent-search-title"
    >
      <h3 id="agent-search-title" className="agent-overview-subtitle">
        설계사 찾기
      </h3>
      <AgentSearchForm key={`${keyword}|${branchId}`} keyword={keyword} branchId={branchId} branches={branches} onSearch={onSearch} />
      {!searched ? (
        <p className="agent-overview-note">이름이나 코드를 입력하거나 지점을 고르면 찾아 드립니다. 해촉 설계사도 나옵니다.</p>
      ) : result.isError ? (
        <RegionError
          error={result.error}
          fallback="설계사를 찾지 못했습니다."
          updatedAt={result.dataUpdatedAt}
          busy={result.isFetching}
          onRetry={() => void result.refetch()}
        />
      ) : result.isPending ? (
        <p role="status" className="agent-overview-note">
          설계사를 찾는 중입니다.
        </p>
      ) : (
        <>
          <p className="agent-overview-note tabular-nums" aria-live="polite">
            {`찾은 설계사 ${int(result.data?.totalElements)}명`}
          </p>
          <DataTable
            caption="설계사 검색 결과"
            columns={columns}
            rows={result.data?.content ?? []}
            rowKey={(agent) => String(agent.agentId)}
            emptyMessage="조건에 맞는 설계사가 없습니다"
          />
          <ListPagination
            label="설계사 검색 결과 페이지 선택"
            page={page}
            totalPages={result.data?.totalPages ?? 0}
            onPageChange={onPage}
          />
        </>
      )}
    </section>
  )
}

function AgentSearchForm({
  keyword,
  branchId,
  branches,
  onSearch,
}: {
  keyword: string
  branchId: string
  branches?: HomeBranch[]
  onSearch: (keyword: string, branchId: string) => void
}) {
  const [draftKeyword, setDraftKeyword] = useState(keyword)
  const [draftBranch, setDraftBranch] = useState(branchId)
  return (
    <FilterBar
      embedded
      onSubmit={() => onSearch(draftKeyword.trim(), draftBranch)}
      onReset={() => {
        setDraftKeyword('')
        setDraftBranch('')
        onSearch('', '')
      }}
    >
      <Field label="지점" className="filter-field">
        <select
          data-guide-feature="features.dashboard.agent-overview.branch"
          className="select-control"
          value={draftBranch}
          onChange={(event) => setDraftBranch(event.target.value)}
        >
          <option value="">전체</option>
          {draftBranch && !branches?.some((branch) => String(branch.organizationId) === draftBranch) && (
            <option value={draftBranch}>선택한 지점</option>
          )}
          {branches?.map((branch) => (
            <option key={branch.organizationId} value={branch.organizationId}>
              {branch.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="이름 또는 코드" className="filter-field">
        <input
          data-guide-feature="features.dashboard.agent-overview.keyword"
          type="search"
          placeholder="이름 또는 설계사 코드"
          value={draftKeyword}
          onChange={(event) => setDraftKeyword(event.target.value)}
        />
      </Field>
    </FilterBar>
  )
}
