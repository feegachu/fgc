import { typeBadgeTone } from '../exception/rules'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { openSettlementGuide } from '../assistant/settlement/entry'
import { useQuery } from '@tanstack/react-query'
import { Fragment, useMemo, useState, type CSSProperties } from 'react'
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
import { AgentStatusPanel } from './AgentStatusPanel'
import { MonthlyCommissions } from './MonthlyCommissions'
import { RecentExceptionTicker } from './RecentExceptionTicker'
import { DashboardSkeleton } from './DashboardSkeleton'
import { dashboardWalkthrough } from './dashboard-walkthrough'
import { RegionError, UpdatedAt } from './RegionStatus'
import { RiskComposition, type CompositionItem } from './RiskComposition'
import { exceptionStatusTone, runStatusTone, severityTone } from './tones'

const EMPTY_MESSAGE = '조건에 맞는 자료가 없습니다.'

/** 셸의 기준월과 내부 사용자 화면 범위를 대시보드 조회에 전달한다. */
export function DashboardPage() {
  const { month, revised = false } = useOutletContext<ShellContext>()
  if (!month) return null
  return <DashboardContent month={month} revised={revised} />
}

/** 검증·미처리 집계를 독립적으로 조회하고 확정 관리자 배치와 업무 화면 연결을 제공한다. */
function DashboardContent({ month, revised }: { month: string; revised: boolean }) {
  const [helpMotion, setHelpMotion] = useState<{ active: string | null; replays: Record<string, number> }>({ active: null, replays: {} })
  const walkthrough = useMemo(() => dashboardWalkthrough.map(step => ({
    ...step,
    onEnter: () => {
      setHelpMotion(current => ({ active: step.targetId, replays: { ...current.replays, [step.targetId]: (current.replays[step.targetId] ?? 0) + 1 } }))
      return () => setHelpMotion(current => ({ ...current, active: null }))
    },
  })), [])
  const replay = (id: string) => helpMotion.replays[id] ?? 0
  const { data, error, isPending, isFetching, dataUpdatedAt, refetch } = useQuery({
    ...dashboardSummaryQueryOptions(month),
    meta: { errorToast: false },
  })
  const openCounts = useQuery(openExceptionCountsQueryOptions())
  const summaryReady = !error && !isPending
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
          preview={
            <Link
              data-guide-feature="features.dashboard.dashboard-page.link"
              to={href('/exceptions', { contractNo: e.contractNo ?? '' })}
            >
              {screenMessage(e.title)}
            </Link>
          }
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
        footer: revised ? '전체 기간, 확정 전 0건이어야 합니다' : '확정 전 0건이어야 합니다',
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
            <button
              data-guide-feature="features.dashboard.dashboard-page.open-retry"
              type="button"
              className="kpi-retry"
              aria-label="미처리 예외 다시 불러오기"
              disabled={openCounts.isFetching}
              onClick={() => void openCounts.refetch()}
            >
              다시 시도
            </button>
            <UpdatedAt at={openCounts.dataUpdatedAt} />
          </>
        ) : openCounts.data?.total === 0 ? (
          '미처리 예외가 없습니다'
        ) : openCounts.isPending ? (
          '불러오는 중'
        ) : revised ? (
          '전체 기간 · 신규 + 검토중'
        ) : (
          '신규 + 검토중'
        ),
        tone: 'warning',
        to: href('/exceptions', { status: OPEN_STATUS }),
      },
    ] as const)

  const compositionItems: CompositionItem[] =
    kpis
      ?.filter((kpi) => kpi.id !== 'open')
      .map((kpi) => ({
        key: kpi.id,
        label: kpi.label,
        value: kpi.value ?? 0,
        color: kpi.color,
        to: kpi.to,
      })) ?? []

  const priorityPanel = (
    <section
      data-guide-feature="features.dashboard.dashboard-page.section-2"
      className="surface"
      aria-labelledby="priority-title"
    >
      <header className="surface-header">
        <h2 id="priority-title" className="surface-title">
          최근 예외
        </h2>
        <Link
          data-guide-feature="features.dashboard.dashboard-page.link-3"
          className="run-list-link"
          to={href('/exceptions')}
        >
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
  )
  const compositionPanel = (
    <section
      data-guide-feature="features.dashboard.dashboard-page.composition"
      className="surface risk-composition-card"
      aria-labelledby="composition-title"
    >
      <header className="surface-header">
        <h2 id="composition-title" className="surface-title">
          검증 항목별 건수 비중
        </h2>
      </header>
      <RiskComposition key={replay('features.dashboard.dashboard-page.composition')} items={compositionItems} />
      {!revised && (
        <p className="risk-composition-note">
          미처리 예외는 일부 항목과 겹쳐 비중에서 제외했습니다. 항목마다 세는 단위가 달라 건수의 단순 비율로 표시합니다.
        </p>
      )}
    </section>
  )
  const runsPanel = (
    <section
      data-guide-feature="features.dashboard.dashboard-page.section-3"
      className="surface run-list-card"
      aria-labelledby="run-list-title"
    >
      <div className="run-list-header">
        <h2 id="run-list-title" className="run-list-title">
          최근 통합검증 실행
        </h2>
        <Link
          data-guide-feature="features.dashboard.dashboard-page.link-4"
          className="run-list-link"
          to={href('/validation-runs')}
        >
          실행 목록
        </Link>
      </div>
      {data?.recentRuns?.length ? (
        <div className="run-list-body">
          {data.recentRuns.map((run) => (
            <RunRow key={run.validationRunId} run={run} to={href(`/validation-runs/${run.validationRunId}`)} />
          ))}
        </div>
      ) : (
        <div className="empty-state">{EMPTY_MESSAGE}</div>
      )}
    </section>
  )

  return (
    <div className="dashboard-page">
      <header className="page-header">
        <ScreenHelp screenId="DASH-W01" walkthrough={revised ? walkthrough : undefined} />
        <div className="page-header-copy">
          <h1 className="page-title">업무 대시보드</h1>
        </div>
        {!revised && (
          <div className="page-header-actions">
            <SettlementGuideEntry />
          </div>
        )}
      </header>
      {!revised && (
        <p className="dashboard-basis">
          <span>기준 정산월 {monthText(month)}</span>
          <span>미처리 예외와 원장 불균형은 정산월과 상관없이 전체 건수입니다.</span>
          {!error && <UpdatedAt at={dataUpdatedAt} />}
        </p>
      )}
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
        <section
          data-guide-feature="features.dashboard.dashboard-page.section"
          className="kpi-grid kpi-group"
          aria-label="주요 검증 지표"
        >
          {kpis?.map((kpi) => {
            const card = (
              <KpiCard
                key={replay('features.dashboard.dashboard-page.section')}
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
              <Link
                data-guide-feature={guideFeatureId('dashboard.kpi-link', kpi.id)}
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
      )}
      {(summaryReady || revised) && (
        <div className="dashboard-main-grid">
          <div className="dashboard-main-column">
            {revised ? (
              <>
                <MonthlyCommissions key={month} month={month} replay={replay('features.dashboard.monthly-commissions.section')} />
                <div className="dashboard-insight-grid">
                  {summaryReady && compositionPanel}
                  <AgentStatusPanel month={month} preview={helpMotion.active === 'features.dashboard.agent-status-panel.section'} replay={replay('features.dashboard.agent-status-panel.section')} />
                </div>
              </>
            ) : (
              summaryReady && priorityPanel
            )}
          </div>
          <div className="dashboard-side-column">
            {summaryReady && (
              <div className="dashboard-recent-panels" data-guide-target="dashboard.recent-activity">
                <Fragment key={replay('dashboard.recent-activity')}>
                  {revised ? (
                    <RecentExceptionTicker rows={data?.recentExceptions ?? []} month={month} />
                  ) : (
                    compositionPanel
                  )}
                  {runsPanel}
                </Fragment>
              </div>
            )}
            {revised && (
              <div className="dashboard-support-cards" data-guide-target="dashboard.support">
                <Fragment key={replay('dashboard.support')}>
                  <SettlementGuideEntry card />
                  <AssociationEntry />
                </Fragment>
              </div>
            )}
          </div>
        </div>
      )}
      {!revised && <AgentOverview month={month} />}
    </div>
  )
}

/** 기존 업무 가이드를 실행한다. 관리자 홈에서는 지원 카드 형태로 표시한다. */
function SettlementGuideEntry({ card = false }: { card?: boolean }) {
  return (
    <button
      data-guide-feature="features.dashboard.dashboard-page.settlement-guide"
      type="button"
      className={card ? 'dashboard-guide-card' : 'button button-secondary'}
      data-guide-target="dashboard.guide"
      onClick={openSettlementGuide}
      aria-label="전체 업무 가이드"
    >
      {card ? (
        <>
          <span className="dashboard-guide-eyebrow">정산 업무가 궁금한가요?</span>
          <strong>
            전체 업무
            <br />
            가이드
          </strong>
          <span className="dashboard-guide-caption">계약부터 예외함 확인까지</span>
          <span className="dashboard-guide-start">
            시작하기 <span aria-hidden="true">→</span>
          </span>
          <svg className="dashboard-guide-art" viewBox="0 0 96 80" aria-hidden="true">
            <path d="M10 51 48 30l38 21-38 22Z" fill="#92dfc0" />
            <path d="m10 43 38-21 38 21-38 22Z" fill="#2cae85" />
            <path d="M16 44v7l32 18v-7Z" fill="#d8f5e9" />
            <path d="M10 30 48 9l38 21-38 22Z" fill="#a8ebd1" />
            <path d="m10 30 38 22v9L10 40Z" fill="#45bf97" />
            <path d="m48 52 38-22v10L48 61Z" fill="#74d4b3" />
            <path d="m30 19 38 22v15l-6-1-6 8V47L18 26Z" fill="#f5fffb" />
          </svg>
        </>
      ) : (
        '전체 업무 가이드'
      )}
    </button>
  )
}

/** 보험GA협회 공식 사이트를 새 탭으로 연결하며 별도 상담 기능을 만들지 않는다. */
function AssociationEntry() {
  return (
    <a
      className="dashboard-guide-card dashboard-association-card"
      data-guide-feature="features.dashboard.dashboard-page.ga-association"
      href="https://www.igaa.or.kr/"
      target="_blank"
      rel="noopener noreferrer"
      aria-label="보험GA협회 홈페이지 열기 (새 탭)"
    >
      <span className="dashboard-guide-eyebrow">GA 업계 소식과 자료</span>
      <strong>
        보험GA협회
        <br />
        바로가기
      </strong>
      <span className="dashboard-guide-caption">업계 소식과 제도 안내</span>
      <span className="dashboard-guide-start">
        방문하기 <span aria-hidden="true">↗</span>
      </span>
      <svg className="dashboard-guide-art" viewBox="0 0 80 72" aria-hidden="true">
        <path d="M9 29 34 13l25 16v5H9Z" fill="#4773d0" />
        <path d="M13 34h42v25H13Z" fill="#d4e2ff" />
        <path d="M18 35v22m16-22v22m16-22v22" stroke="#7398e1" strokeWidth="6" />
        <path d="M8 58h52v7H8Z" fill="#4773d0" />
        <path d="m59 32 16 6v12c0 10-16 18-16 18S43 60 43 50V38Z" fill="#83b5e7" />
        <path d="m59 37 11 4v9c0 7-11 13-11 13S48 57 48 50v-9Z" fill="#2d5cae" />
        <path d="m53 49 4 4 8-9" fill="none" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </a>
  )
}

const RUN_STEPS = 10

function RunRow({ run, to }: { run: RecentRun; to: string }) {
  const step = run.currentStep ?? 0
  const progress = Math.max(0, Math.min(100, step * 10))
  return (
    <Link data-guide-feature="features.dashboard.dashboard-page.link-5" className="run-row" to={to}>
      <strong className="run-title">{`${monthText(run.validationMonth)} ${run.runNo}차 실행`}</strong>
      <span className="run-meta">{`${step}/${RUN_STEPS} 단계`}</span>
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
