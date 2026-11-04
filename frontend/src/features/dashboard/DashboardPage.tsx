import { typeBadgeTone } from '../exception/rules'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { openSettlementGuide } from '../assistant/settlement/entry'
import { useQuery } from '@tanstack/react-query'
import type { CSSProperties } from 'react'
import { Link, useOutletContext } from 'react-router'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { KpiCard } from '../../components/KpiCard'
import { StatusBadge } from '../../components/StatusBadge'
import { dateTime, int } from '../../lib/format'
import { monthText, screenMessage } from '../../lib/screenText'
import { withMonth } from '../../stores/workspace'
import type { ShellContext } from '../../app/shell/AppShell'
import { OPEN_STATUS } from '../exception/api'
import { dashboardSummaryQueryOptions, openExceptionCountsQueryOptions } from './api'
import type { RecentException, RecentRun } from './api'
import { AgentOverview } from './AgentOverview'
import { DashboardSkeleton } from './DashboardSkeleton'
import { RegionError, UpdatedAt } from './RegionStatus'
import { RiskComposition, type CompositionItem } from './RiskComposition'
import { exceptionStatusTone, runStatusTone, severityTone } from './tones'

const EMPTY_MESSAGE = '조건에 맞는 자료가 없습니다.'

export function DashboardPage() {
  const { month } = useOutletContext<ShellContext>()
  if (!month) return null
  return <DashboardContent month={month} />
}

function DashboardContent({ month }: { month: string }) {
  const { data, error, isPending, isFetching, dataUpdatedAt, refetch } = useQuery({
    ...dashboardSummaryQueryOptions(month),
    meta: { errorToast: false },
  })
  const openCounts = useQuery(openExceptionCountsQueryOptions())
  const href = (path: string, params: Record<string, string> = {}) => {
    const search = new URLSearchParams(params).toString()
    return withMonth(search ? `${path}?${search}` : path, month)
  }

  const exceptionColumns: TableColumn<RecentException>[] = [
    {
      key: 'severity',
      label: '심각도',
      align: 'center',
      render: (e) => (
        <StatusBadge kind="severity" optionCount={4} tone={severityTone(e.severity)} tablePresentation="text">
          {e.severityLabel ?? '-'}
        </StatusBadge>
      ),
    },
    {
      key: 'type',
      label: '유형',
      render: (e) => (
        <StatusBadge optionCount={14} kind="category" tone={typeBadgeTone(e.exceptionType)}>
          {e.exceptionTypeLabel ?? '-'}
        </StatusBadge>
      ),
    },
    { key: 'contractNo', label: '계약번호', cellClassName: 'tabular-nums', render: (e) => e.contractNo ?? '-' },
    {
      key: 'title',
      label: '예외 내용',
      cellClassName: 'dashboard-disclosure-cell',
      render: (e) => (
        <CellDisclosure
          singleLine
          preview={<Link data-guide-feature="features.dashboard.dashboard-page.link" to={href('/exceptions', { contractNo: e.contractNo ?? '' })}>{screenMessage(e.title)}</Link>}
          full={screenMessage(e.title)}
        />
      ),
    },
    {
      key: 'status',
      label: '상태',
      align: 'center',
      render: (e) => (
        <StatusBadge optionCount={4} tone={exceptionStatusTone(e.status)} tablePresentation="dot">
          {e.statusLabel ?? '-'}
        </StatusBadge>
      ),
    },
    { key: 'createdAt', label: '발생', cellClassName: 'tabular-nums', render: (e) => dateTime(e.createdAt) },
  ]

  const kpis =
    data &&
    ([
      {
        id: 'cap-violation',
        color: 'error',
        label: '1,200% 위반',
        value: data.capViolation,
        footer: '확정 위반 판정',
        tone: 'error',
        to: href('/cap-checks', { status: 'VIOLATION' }),
      },
      {
        id: 'cap-warning',
        color: 'warning',
        label: '1,200% 주의',
        value: data.capWarning,
        footer: '사용률 경고 기준 이상',
        tone: 'warning',
        to: href('/cap-checks', { status: 'WARNING' }),
      },
      {
        id: 'arbitrage',
        color: 'review',
        label: '차익거래 검토대상',
        value: data.arbitrageCandidate,
        footer: '위반 확정 아님',
        tone: 'warning',
        to: href('/arbitrage-checks', { status: 'CANDIDATE' }),
      },
      {
        id: 'recon',
        color: 'info',
        label: '대사 불일치',
        value: data.reconMismatch,
        footer: '허용오차 0원',
        tone: 'risk',
        to: href('/reconciliations', { onlyMismatch: 'true' }),
      },
      {
        id: 'journal',
        color: 'success',
        label: '원장 불균형',
        value: data.journalImbalance,
        footer: '기표 전 0건 필수',
        tone: data.journalImbalance === 0 ? 'success' : 'error',
        to: href('/journals'),
      },
      {
        id: 'open',
        color: 'warning',
        label: '미처리 예외',
        value: openCounts.isError ? undefined : openCounts.data?.total,
        footer: openCounts.isError ? (
          <>
            <span role="alert">불러오지 못했습니다</span>
            <button data-guide-feature="features.dashboard.dashboard-page.open-retry"
              type="button"
              className="kpi-retry"
              aria-label="미처리 예외 다시 불러오기"
              disabled={openCounts.isFetching}
              onClick={() => void openCounts.refetch()}
            >
              다시 시도
            </button>
          </>
        ) : openCounts.data?.total === 0 ? (
          '미처리 예외가 없습니다'
        ) : openCounts.isPending ? (
          '불러오는 중'
        ) : (
          '신규 + 검토중'
        ),
        tone: 'warning',
        to: href('/exceptions', { status: OPEN_STATUS }),
      },
    ] as const)

  const compositionItems: CompositionItem[] = kpis?.filter((kpi) => kpi.id !== 'open').map((kpi) => ({
    key: kpi.id,
    label: kpi.label,
    value: kpi.value ?? 0,
    color: kpi.color,
    to: kpi.to,
  })) ?? []

  return (
    <div className="dashboard-page">
      <header className="page-header">
        <ScreenHelp screenId="DASH-W01" />
        <div className="page-header-copy">
          <h1 className="page-title">업무 대시보드</h1>
        </div>
        <div className="page-header-actions">
          <button data-guide-feature="features.dashboard.dashboard-page.settlement-guide"
            type="button"
            className="button button-secondary"
            data-guide-target="dashboard.guide"
            onClick={openSettlementGuide}
          >
            전체 업무 가이드
          </button>
        </div>
      </header>
      <p className="dashboard-basis">
        <span>기준 정산월 {monthText(month)}</span>
        <span>미처리 예외와 원장 불균형은 정산월과 상관없이 전체 건수입니다.</span>
        {!error && <UpdatedAt at={dataUpdatedAt} />}
      </p>
      {error ? (
        <RegionError
          error={error}
          fallback="대시보드를 불러오지 못했습니다."
          updatedAt={dataUpdatedAt}
          busy={isFetching}
          onRetry={() => void refetch()}
        />
      ) : isPending ? (
        <DashboardSkeleton />
      ) : (
        <>
          <section data-guide-feature="features.dashboard.dashboard-page.section" className="kpi-grid kpi-group" aria-label="주요 검증 지표">
            {kpis?.map((kpi) => {
              const card = (
                <KpiCard
                  guideId={guideFeatureId('dashboard.kpi', kpi.id)}
                  label={kpi.label}
                  value={kpi.value ?? '-'}
                  unit={kpi.value == null ? undefined : '건'}
                  footer={<span className="kpi-comparison">{kpi.footer}</span>}
                  tone={kpi.tone}
                  zero={kpi.value === 0}
                />
              )
              // 아직 받지 못한 건수는 0으로 보이지 않고, 숫자가 없으면 눌러서 갈 조건도 없다.
              return kpi.value == null ? (
                <div key={kpi.label} className="kpi-card-link" data-kpi={kpi.id}>
                  {card}
                </div>
              ) : (
                <Link data-guide-feature={guideFeatureId('dashboard.kpi-link', kpi.id)}
                  key={kpi.label}
                  to={kpi.to}
                  className="kpi-card-link"
                  data-kpi={kpi.id}
                  data-zero={kpi.value === 0}
                  data-attention={kpi.tone === 'error' && kpi.value > 0}
                  aria-label={`${kpi.label} ${int(kpi.value)}건`}
                >
                  {card}
                </Link>
              )
            })}
          </section>
          <div className="dashboard-main-grid">
            <section data-guide-feature="features.dashboard.dashboard-page.section-2" className="surface" aria-labelledby="priority-title">
              <header className="surface-header">
                <h2 id="priority-title" className="surface-title">
                  최근 예외
                </h2>
                <Link data-guide-feature="features.dashboard.dashboard-page.link-3" className="run-list-link" to={href('/exceptions')}>
                  예외함 전체 보기
                </Link>
              </header>
              <div className="priority-table">
                <DataTable
                  caption="최근 예외 목록"
                  columns={exceptionColumns}
                  rows={data?.recentExceptions ?? []}
                  rowKey={(e) => String(e.exceptionCaseId)}
                  emptyMessage={EMPTY_MESSAGE}
                />
              </div>
            </section>
            <div className="dashboard-side-column">
              <section data-guide-feature="features.dashboard.dashboard-page.composition"
                className="surface risk-composition-card" aria-labelledby="composition-title"
              >
                <header className="surface-header">
                  <h2 id="composition-title" className="surface-title">검증 항목별 건수 비중</h2>
                </header>
                <RiskComposition items={compositionItems} />
                <p className="risk-composition-note">
                  미처리 예외는 일부 항목과 겹쳐 비중에서 제외했습니다. 항목마다 세는 단위가 달라 건수의 단순 비율로 표시합니다.
                </p>
              </section>
              <section data-guide-feature="features.dashboard.dashboard-page.section-3" className="surface run-list-card" aria-labelledby="run-list-title">
                <div className="run-list-header">
                  <h2 id="run-list-title" className="run-list-title">
                    최근 통합검증 실행
                  </h2>
                  <Link data-guide-feature="features.dashboard.dashboard-page.link-4" className="run-list-link" to={href('/validation-runs')}>
                    실행 목록
                  </Link>
                </div>
                {data?.recentRuns?.length ? (
                  <div className="run-list-body">
                    {data.recentRuns.map((run) => (
                      <RunRow
                        key={run.validationRunId}
                        run={run}
                        to={href(`/validation-runs/${run.validationRunId}`)}
                      />
                    ))}
                  </div>
                ) : (
                  <div className="empty-state">{EMPTY_MESSAGE}</div>
                )}
              </section>
            </div>
          </div>
        </>
      )}
      <AgentOverview month={month} />
    </div>
  )
}


const RUN_STEPS = 10

function RunRow({ run, to }: { run: RecentRun; to: string }) {
  const step = run.currentStep ?? 0
  const progress = Math.max(0, Math.min(100, step * 10))
  return (
    <Link data-guide-feature="features.dashboard.dashboard-page.link-5" className="run-row" to={to}>
      <strong className="run-title">{`${monthText(run.validationMonth)} ${run.runNo}회차`}</strong>
      <span className="run-meta">{`${step}/${RUN_STEPS} 단계, 실행 ${run.triggeredBy ?? '-'}`}</span>
      <StatusBadge tone={runStatusTone(run.status)}>{run.statusLabel ?? '-'}</StatusBadge>
      <span
        className="run-steps"
        role="progressbar"
        aria-label="검증 진행률"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress}
      >
        {Array.from({ length: RUN_STEPS }, (_, index) => (
          <span
            key={index}
            className={`run-step${index < step ? ' is-done' : ''}`}
            style={{ '--i': index } as CSSProperties}
          />
        ))}
      </span>
    </Link>
  )
}
import { guideFeatureId } from '../assistant/feature-ids'
