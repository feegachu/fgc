import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Modal } from '../../components/Modal'
import { ListPagination } from '../../components/ListPagination'
import { AnimatedNumber } from '../../components/AnimatedNumber'
import { int, won } from '../../lib/format'
import { monthText } from '../../lib/screenText'
import { withMonth } from '../../stores/workspace'
import { contractStatusLabels } from '../contract/labels'
import { ChartViewSelector } from './ChartViewSelector'
import type { ChartView } from './ChartViewSelector'
import { branchDashboardQuery, branchDetailsQuery } from './api'
import type { BranchMetrics, BranchMetric, BranchDetailRow } from './api'
import { RegionError } from './RegionStatus'

const metricChoices: (ChartView & { metric: BranchMetric })[] = [
  { id: 'branch-commissions', metric: 'COMMISSIONS', label: '수수료', icon: 'money' },
  { id: 'branch-agents', metric: 'AGENTS', label: '설계사', icon: 'people' },
  { id: 'branch-contracts', metric: 'CONTRACTS', label: '계약', icon: 'contract' },
  { id: 'branch-exceptions', metric: 'EXCEPTIONS', label: '예외', icon: 'exception' },
]
type AmountKey =
  | 'gaAmount'
  | 'agentAmount'
  | 'registeredAgents'
  | 'activeAgents'
  | 'newContracts'
  | 'validContracts'
  | 'openExceptions'
type MetricDefinition = {
  label: string
  primary: AmountKey
  primaryLabel: string
  secondary?: AmountKey
  secondaryLabel?: string
  unit: string
}
const metrics: Record<BranchMetric, MetricDefinition> = {
  COMMISSIONS: {
    label: '수수료',
    primary: 'gaAmount',
    primaryLabel: 'GA 수령액',
    secondary: 'agentAmount',
    secondaryLabel: '설계사 지급액',
    unit: '원',
  },
  AGENTS: {
    label: '설계사',
    primary: 'registeredAgents',
    primaryLabel: '등록 설계사',
    secondary: 'activeAgents',
    secondaryLabel: '활동 설계사',
    unit: '명',
  },
  CONTRACTS: {
    label: '계약',
    primary: 'newContracts',
    primaryLabel: '신규 계약',
    secondary: 'validContracts',
    secondaryLabel: '유효 계약',
    unit: '건',
  },
  EXCEPTIONS: { label: '예외', primary: 'openExceptions', primaryLabel: '미처리 예외', unit: '건' },
}
const compact = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 })
const valueOf = (row: BranchMetrics, key: AmountKey) => row[key] ?? 0

/** 같은 월의 지점별 네 지표를 막대로 비교하고 선택 지점·지표의 상세를 연결한다. */
export function AgentStatusPanel({ month, preview = false, replay = 0 }: { month: string; preview?: boolean; replay?: number }) {
  const [metric, setMetric] = useState<BranchMetric>('COMMISSIONS')
  const [hovered, setHovered] = useState<number | null>(null)
  const [focused, setFocused] = useState<number | null>(null)
  const [selected, setSelected] = useState<BranchMetrics | null>(null)
  useEffect(() => {
    if (!preview || selected) return
    const timer = window.setInterval(() => {
      if (document.hidden) return
      setMetric(current => metricChoices[(metricChoices.findIndex(choice => choice.metric === current) + 1) % metricChoices.length].metric)
      setHovered(null)
      setFocused(null)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [preview, selected, metric])
  const contentId = useId(),
    tooltipId = useId()
  const query = useQuery(branchDashboardQuery(month))
  const definition = metrics[metric]
  const rows = [...(query.data?.branches ?? [])].sort(
    (a, b) =>
      valueOf(b, definition.primary) - valueOf(a, definition.primary) ||
      (a.name ?? '').localeCompare(b.name ?? '', 'ko'),
  )
  const active = rows.find((row) => row.organizationId === (hovered ?? focused))
  const format = (value: number) => (definition.unit === '원' ? won(value) : `${int(value)}${definition.unit}`)
  const max = Math.max(
    1,
    ...rows.flatMap((row) => [
      valueOf(row, definition.primary),
      definition.secondary ? valueOf(row, definition.secondary) : 0,
    ]),
  )
  const buttonProps = (row: BranchMetrics) => ({
    type: 'button' as const,
    'aria-label': `${row.name} ${definition.label} 상세 보기`,
    'aria-describedby': active?.organizationId === row.organizationId ? tooltipId : undefined,
    onMouseEnter: () => setHovered(row.organizationId ?? null),
    onMouseLeave: () => setHovered(null),
    onFocus: () => setFocused(row.organizationId ?? null),
    onBlur: () => setFocused(null),
    onClick: () => {
      setHovered(null)
      setFocused(null)
      setSelected(row)
    },
  })
  return (
    <section
      data-guide-feature="features.dashboard.agent-status-panel.section"
      className="surface agent-status-panel"
      aria-labelledby="agent-status-title"
    >
      <header className="surface-header">
        <h2 id="agent-status-title" className="surface-title">
          지점별 현황
        </h2>
        <span
          className="agent-status-basis"
          title={`인원·유효 계약: ${query.data?.asOf ?? '-'} 기준. 활동 여부는 현재 상태. 수수료·신규 계약: 선택한 정산월. 미처리 예외: 현재 상태.`}
        >
          {metric === 'EXCEPTIONS' ? '전체 기간' : metric === 'AGENTS' ? (query.data?.asOf ?? '-') : monthText(month)}
        </span>
      </header>
      <div className="branch-dashboard-body">
        <div className="branch-dashboard-toolbar" data-guide-target="dashboard.branches.metrics">
          <ChartViewSelector
            views={metricChoices}
            selected={metricChoices.findIndex((item) => item.metric === metric)}
            label="지점별 현황 지표"
            controls={contentId}
            onSelect={(index) => {
              setMetric(metricChoices[index].metric)
              setHovered(null)
              setFocused(null)
            }}
          />
        </div>
        {query.isError ? (
          <RegionError
            error={query.error}
            fallback="지점별 현황을 불러오지 못했습니다."
            updatedAt={query.dataUpdatedAt}
            busy={query.isFetching}
            onRetry={() => void query.refetch()}
          />
        ) : !query.data ? (
          <p className="empty-state" role="status">
            지점별 현황을 불러오는 중입니다.
          </p>
        ) : (
          <div id={contentId} className="branch-chart-content" data-guide-target="dashboard.branches.chart">
            <div className="branch-series-legend">
              <span>
                <i style={{ background: '#4266db' }} />
                {definition.primaryLabel}
              </span>
              {definition.secondary && (
                <span>
                  <i style={{ background: '#b6c4f1' }} />
                  {definition.secondaryLabel}
                </span>
              )}
            </div>
            <div key={`${month}-${metric}-${replay}`} className="agent-branch-slide">
              {!rows.length ? (
                <p className="empty-state">표시할 지점이 없습니다.</p>
              ) : (
                <ul className="branch-metric-bars" aria-label={`지점별 ${definition.label}`}>
                  {rows.map((row, index) => (
                    <li key={row.organizationId}>
                      <button
                        data-guide-feature="features.dashboard.branch.bar-detail"
                        className="branch-metric-button"
                        style={{ '--bar-delay': `${Math.min(index, 6) * 55}ms` } as CSSProperties}
                        {...buttonProps(row)}
                      >
                        <strong>
                          <AnimatedNumber
                            value={valueOf(row, definition.primary)}
                            format={definition.unit === '원' ? value => `${compact.format(value)}원` : format}
                          />
                        </strong>
                        <span className="branch-bar-pair" aria-hidden="true">
                          <span
                            style={{
                              height: `${(valueOf(row, definition.primary) / max) * 100}%`,
                              background: '#4266db',
                            }}
                          />
                          {definition.secondary && (
                            <span
                              style={{
                                height: `${(valueOf(row, definition.secondary) / max) * 100}%`,
                                background: '#b6c4f1',
                              }}
                            />
                          )}
                        </span>
                        <span className="branch-bar-label">{row.name}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            {active && !selected && (
              <div id={tooltipId} role="tooltip" className="branch-metric-tooltip">
                <strong>{active.name}</strong>
                <dl>
                  <div>
                    <dt>등록 · 활동</dt>
                    <dd>
                      {int(active.registeredAgents)} · {int(active.activeAgents)}명
                    </dd>
                  </div>
                  <div>
                    <dt>신규 · 유효 계약</dt>
                    <dd>
                      {int(active.newContracts)} · {int(active.validContracts)}건
                    </dd>
                  </div>
                  <div>
                    <dt>GA 수령액</dt>
                    <dd>{won(active.gaAmount)}</dd>
                  </div>
                  <div>
                    <dt>설계사 지급액</dt>
                    <dd>{won(active.agentAmount)}</dd>
                  </div>
                  <div>
                    <dt>미처리 예외</dt>
                    <dd>{int(active.openExceptions)}건</dd>
                  </div>
                </dl>
              </div>
            )}
          </div>
        )}
      </div>
      {selected && (
        <BranchDetails
          key={`${month}-${selected.organizationId}-${metric}`}
          branch={selected}
          month={month}
          asOf={query.data?.asOf ?? '-'}
          metric={metric}
          onClose={() => setSelected(null)}
        />
      )}
    </section>
  )
}

/** 요약과 같은 범위의 지점 상세를 10건씩 조회한다. 페이지 로딩 중 이전 자료임을 표시하고 이동 위치의 포커스를 보존한다. */
function BranchDetails({
  branch,
  month,
  asOf,
  metric,
  onClose,
}: {
  branch: BranchMetrics
  month: string
  asOf: string
  metric: BranchMetric
  onClose: () => void
}) {
  const [page, setPage] = useState(1)
  const paginationRef = useRef<HTMLDivElement>(null)
  const query = useQuery({ ...branchDetailsQuery(month, branch.organizationId!, metric, page), placeholderData: keepPreviousData })
  const definition = metrics[metric]
  const status = (row: BranchDetailRow) => {
    const labels: Record<string, string> =
      metric === 'AGENTS'
        ? { ACTIVE: '활동', INACTIVE: '비활동', TERMINATED: '해촉' }
        : metric === 'EXCEPTIONS'
          ? { NEW: '신규', IN_REVIEW: '검토 중' }
          : contractStatusLabels
    return labels[row.status ?? ''] ?? '-'
  }
  const columns: TableColumn<BranchDetailRow>[] = [
    {
      key: 'label',
      label: metric === 'AGENTS' ? '설계사' : metric === 'EXCEPTIONS' ? '예외 내용' : '계약번호',
      render: (row) =>
        metric === 'COMMISSIONS' || metric === 'CONTRACTS' ? (
          <Link
            data-guide-feature="features.dashboard.branch.contract-link"
            onClick={onClose}
            to={withMonth(`/contracts/${row.id}`, month)}
          >
            {row.label}
          </Link>
        ) : (
          row.label
        ),
    },
    ...(metric === 'COMMISSIONS'
      ? [
          { key: 'ga', label: 'GA 수령액', align: 'number' as const, render: (row: BranchDetailRow) => won(row.value) },
          {
            key: 'agent',
            label: '설계사 지급액',
            align: 'number' as const,
            render: (row: BranchDetailRow) => won(row.secondaryValue),
          },
        ]
      : [
          {
            key: 'detail',
            label: metric === 'AGENTS' ? '설계사 코드' : metric === 'CONTRACTS' ? '계약일' : '계약번호',
            render: (row: BranchDetailRow) => row.detail || '-',
          },
          { key: 'status', label: '상태', render: status },
        ]),
  ]
  return (
    <Modal open title={`${branch.name} · ${definition.label} 상세`} onClose={onClose} className="branch-detail-modal">
      <p className="branch-detail-basis">
        {metric === 'EXCEPTIONS'
          ? '전체 기간 · 현재 미처리'
          : metric === 'AGENTS'
            ? `${asOf} 기준`
            : metric === 'CONTRACTS'
              ? `${monthText(month)} 신규 · ${asOf} 기준 유효 계약`
              : `${monthText(month)} 확정 지급`}
        {' · 하위 팀 포함'}
      </p>
      {query.isError ? (
        <RegionError
          error={query.error}
          fallback="상세 내역을 불러오지 못했습니다."
          updatedAt={query.dataUpdatedAt}
          busy={query.isFetching}
          onRetry={() => void query.refetch()}
        />
      ) : query.data ? (
        <>
          {query.isPlaceholderData && <p role="status">{page}페이지를 불러오는 중입니다. 현재 {query.data.page}페이지입니다.</p>}
          <div aria-busy={query.isPlaceholderData} inert={query.isPlaceholderData}>
          <DataTable
            caption={`${branch.name} ${definition.label} 상세 내역`}
            columns={columns}
            rows={query.data.content ?? []}
            rowKey={(row) => String(row.id)}
          />
          </div>
          <div ref={paginationRef} className="branch-detail-pagination" role="group" aria-label="지점 상세 페이지 이동" tabIndex={-1}>
          <ListPagination
            page={query.data.page ?? page}
            totalPages={query.data.totalPages ?? 1}
            label="지점 상세 내역 페이지"
            onPageChange={next => {
              // A selected page becomes a span; keep keyboard focus at the pagination instead of removing it.
              paginationRef.current?.focus()
              setPage(next)
            }}
          />
          </div>
        </>
      ) : (
        <p role="status" className="empty-state">
          상세 내역을 불러오는 중입니다.
        </p>
      )}
    </Modal>
  )
}
