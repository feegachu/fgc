import { useQuery } from '@tanstack/react-query'
import type { CSSProperties } from 'react'
import { Link, useOutletContext } from 'react-router'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { AnimatedNumber } from '../../components/AnimatedNumber'
import { KpiCard } from '../../components/KpiCard'
import { StatusBadge } from '../../components/StatusBadge'
import { dateTime, errorText, int } from '../../lib/format'
import { useDrawn } from '../../lib/motion'
import { withMonth } from '../../stores/workspace'
import type { ShellContext } from '../../app/shell/AppShell'
import { dashboardSummaryQueryOptions } from './api'
import type { RecentException, RecentRun } from './api'
import { DashboardSkeleton } from './DashboardSkeleton'
import { RiskComposition } from './RiskComposition'
import type { CompositionItem } from './RiskComposition'
import { exceptionStatusTone, exceptionTypeTone, runStatusTone, severityTone } from './tones'

const EMPTY_MESSAGE = '조건에 맞는 자료가 없습니다.'

export function DashboardPage() {
  const { month } = useOutletContext<ShellContext>()
  if (!month) return null
  return <DashboardContent month={month} />
}

function DashboardContent({ month }: { month: string }) {
  const { data, error, isPending, refetch } = useQuery({
    ...dashboardSummaryQueryOptions(month),
    meta: { errorToast: false },
  })
  const href = (path: string, params: Record<string, string> = {}) => {
    const search = new URLSearchParams(params).toString()
    return withMonth(search ? `${path}?${search}` : path, month)
  }

  const exceptionColumns: TableColumn<RecentException>[] = [
    {
      key: 'severity',
      label: '심각도',
      align: 'center',
      render: (e) => <StatusBadge tone={severityTone(e.severity)}>{e.severityLabel ?? '-'}</StatusBadge>,
    },
    {
      key: 'type',
      label: '유형',
      align: 'center',
      render: (e) => <StatusBadge tone={exceptionTypeTone(e.exceptionType)}>{e.exceptionTypeLabel ?? '-'}</StatusBadge>,
    },
    { key: 'contractNo', label: '계약번호', cellClassName: 'tabular-nums', render: (e) => e.contractNo ?? '-' },
    {
      key: 'title',
      label: '예외 내용',
      cellClassName: 'dashboard-disclosure-cell',
      render: (e) => (
        <CellDisclosure
          singleLine
          preview={<Link to={href('/exceptions', { contractNo: e.contractNo ?? '' })}>{e.title ?? '-'}</Link>}
          full={e.title ?? '-'}
        />
      ),
    },
    {
      key: 'status',
      label: '상태',
      align: 'center',
      render: (e) => <StatusBadge tone={exceptionStatusTone(e.status)}>{e.statusLabel ?? '-'}</StatusBadge>,
    },
    { key: 'createdAt', label: '발생', cellClassName: 'tabular-nums', render: (e) => dateTime(e.createdAt) },
  ]

  const kpis =
    data &&
    ([
      {
        id: 'cap-violation',
        label: '1,200% 위반',
        value: data.capViolation,
        footer: '확정 위반 판정',
        tone: 'error',
        to: href('/cap-checks', { status: 'VIOLATION' }),
      },
      {
        id: 'cap-warning',
        label: '1,200% 주의',
        value: data.capWarning,
        footer: '사용률 경고 기준 이상',
        tone: 'warning',
        to: href('/cap-checks', { status: 'WARNING' }),
      },
      {
        id: 'arbitrage',
        label: '차익거래 검토대상',
        value: data.arbitrageCandidate,
        footer: '위반 확정 아님',
        tone: 'warning',
        to: href('/arbitrage-checks', { status: 'CANDIDATE' }),
      },
      {
        id: 'recon',
        label: '대사 불일치',
        value: data.reconMismatch,
        footer: '허용오차 0원',
        tone: 'risk',
        to: href('/reconciliations', { onlyMismatch: 'true' }),
      },
      {
        id: 'journal',
        label: '원장 불균형',
        value: data.journalImbalance,
        footer: '기표 전 0건 필수',
        tone: data.journalImbalance === 0 ? 'success' : 'error',
        to: href('/journals', { imbalanceOnly: 'true' }),
      },
      // OPEN 은 DB 상태값이 아니라 EXCP-W01 과 공유하는 화면 묶음 필터(NEW + IN_REVIEW)다.
      {
        id: 'open',
        label: '미처리 예외',
        value: data.openException,
        footer: '신규 + 검토중',
        tone: 'warning',
        to: href('/exceptions', { status: 'OPEN' }),
      },
    ] as const)

  const compositionItems: CompositionItem[] = data
    ? [
        {
          key: 'cap-violation',
          label: '1,200% 위반',
          value: data.capViolation ?? 0,
          color: 'error',
          to: href('/cap-checks', { status: 'VIOLATION' }),
        },
        {
          key: 'cap-warning',
          label: '1,200% 주의',
          value: data.capWarning ?? 0,
          color: 'warning',
          to: href('/cap-checks', { status: 'WARNING' }),
        },
        {
          key: 'arbitrage',
          label: '차익거래 검토대상',
          value: data.arbitrageCandidate ?? 0,
          color: 'review',
          to: href('/arbitrage-checks', { status: 'CANDIDATE' }),
        },
        {
          key: 'recon',
          label: '대사 불일치',
          value: data.reconMismatch ?? 0,
          color: 'info',
          to: href('/reconciliations', { onlyMismatch: 'true' }),
        },
        {
          key: 'journal',
          label: '원장 불균형',
          value: data.journalImbalance ?? 0,
          color: 'success',
          to: href('/journals', { imbalanceOnly: 'true' }),
        },
      ]
    : []

  return (
    <div className="dashboard-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">업무 대시보드</h1>
        </div>
      </header>
      {error ? (
        <div role="alert" className="empty-state">
          <p>{errorText(error as Error, '대시보드를 불러오지 못했습니다.')}</p>
          <button type="button" className="button button-secondary" onClick={() => void refetch()}>
            다시 시도
          </button>
        </div>
      ) : isPending ? (
        <DashboardSkeleton />
      ) : (
        <>
          <section className="kpi-grid kpi-group" aria-label="주요 검증 지표">
            {kpis?.map((kpi, index) => (
              <Link
                key={kpi.label}
                to={kpi.to}
                className="kpi-card-link"
                data-kpi={kpi.id}
                data-zero={(kpi.value ?? 0) === 0}
                data-attention={kpi.tone === 'error' && (kpi.value ?? 0) > 0}
                style={{ '--i': index } as CSSProperties}
                aria-label={`${kpi.label} ${int(kpi.value ?? 0)}건`}
              >
                <KpiCard
                  label={kpi.label}
                  value={<AnimatedNumber value={kpi.value ?? 0} />}
                  unit="건"
                  footer={<span className="kpi-comparison">{kpi.footer}</span>}
                  tone={kpi.tone}
                  zero={(kpi.value ?? 0) === 0}
                />
              </Link>
            ))}
          </section>
          <div className="dashboard-main-grid">
            <div className="dashboard-primary-column">
              <section className="surface" aria-labelledby="priority-title">
                <header className="surface-header">
                  <h2 id="priority-title" className="surface-title">
                    최근 예외
                  </h2>
                  <Link className="run-list-link" to={href('/exceptions')}>
                    예외함 전체 보기 →
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
              <section className="surface run-list-card" aria-labelledby="run-list-title">
                <div className="run-list-header">
                  <h2 id="run-list-title" className="run-list-title">
                    최근 통합검증 실행
                  </h2>
                  <Link className="run-list-link" to={href('/validation-runs')}>
                    실행 목록 →
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
            </div>
            <aside className="dashboard-secondary-column" aria-label="검증 현황 요약">
              <section className="surface risk-composition-card" aria-labelledby="composition-title">
                <header className="surface-header">
                  <h2 id="composition-title" className="surface-title">
                    검증 항목별 건수 비중
                  </h2>
                </header>
                <RiskComposition items={compositionItems} />
                <p className="risk-composition-note">미처리 예외는 일부가 위 항목과 겹치고 세는 단위(예외 건)가 달라 비중에서 제외했습니다. 비중은 항목별 건수의 단순 비율이며 세는 단위가 서로 다릅니다.</p>
              </section>
            </aside>
          </div>
        </>
      )}
    </div>
  )
}

const RUN_STEPS = 10

function RunRow({ run, to }: { run: RecentRun; to: string }) {
  const step = run.currentStep ?? 0
  const progress = Math.max(0, Math.min(100, step * 10))
  const drawn = useDrawn()
  const running = run.status === 'RUNNING'
  return (
    <Link className="run-row" to={to}>
      <strong className="run-title">{`${run.validationMonth ?? ''} · ${run.runNo}회차`}</strong>
      <span className="run-meta">{`${step}/${RUN_STEPS} 단계 · 실행 ${run.triggeredBy ?? '-'}`}</span>
      <StatusBadge tone={runStatusTone(run.status)}>{run.statusLabel ?? '-'}</StatusBadge>
      <span
        className="run-steps"
        role="progressbar"
        aria-label="검증 진행률"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress}
      >
        {Array.from({ length: RUN_STEPS }, (_, index) => {
          const done = drawn && index < step
          const current = running && index === step - 1
          return (
            <span
              key={index}
              className={`run-step${done ? ' is-done' : ''}${current ? ' is-current' : ''}`}
              style={{ '--i': index } as CSSProperties}
            />
          )
        })}
      </span>
    </Link>
  )
}
