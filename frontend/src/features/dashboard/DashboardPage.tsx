import { useQuery } from '@tanstack/react-query'
import type { CSSProperties } from 'react'
import { Link, useOutletContext } from 'react-router'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { KpiCard } from '../../components/KpiCard'
import { StatusBadge } from '../../components/StatusBadge'
import { dateTime, errorText, int } from '../../lib/format'
import { withMonth } from '../../stores/workspace'
import type { ShellContext } from '../../app/shell/AppShell'
import { dashboardSummaryQueryOptions } from './api'
import type { RecentException, RecentRun } from './api'
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
      render: (e) => <StatusBadge tone={severityTone(e.severity)}>{e.severityLabel ?? '-'}</StatusBadge>,
    },
    {
      key: 'type',
      label: '유형',
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
      render: (e) => <StatusBadge tone={exceptionStatusTone(e.status)}>{e.statusLabel ?? '-'}</StatusBadge>,
    },
    { key: 'createdAt', label: '발생', cellClassName: 'tabular-nums', render: (e) => dateTime(e.createdAt) },
  ]

  const kpis =
    data &&
    ([
      {
        label: '1,200% 위반',
        value: data.capViolation,
        footer: '확정 위반 판정',
        tone: 'error',
        to: href('/cap-checks', { status: 'VIOLATION' }),
      },
      {
        label: '1,200% 주의',
        value: data.capWarning,
        footer: '사용률 경고 기준 이상',
        tone: 'warning',
        to: href('/cap-checks', { status: 'WARNING' }),
      },
      {
        label: '차익거래 검토대상',
        value: data.arbitrageCandidate,
        footer: '위반 확정 아님',
        tone: 'warning',
        to: href('/arbitrage-checks', { status: 'CANDIDATE' }),
      },
      {
        label: '대사 불일치',
        value: data.reconMismatch,
        footer: '허용오차 0원',
        tone: 'risk',
        to: href('/reconciliations', { onlyMismatch: 'true' }),
      },
      {
        label: '원장 불균형',
        value: data.journalImbalance,
        footer: '기표 전 0건 필수',
        tone: data.journalImbalance === 0 ? 'success' : 'error',
        to: href('/journals', { imbalanceOnly: 'true' }),
      },
      // OPEN 은 DB 상태값이 아니라 EXCP-W01 과 공유하는 화면 묶음 필터(NEW + IN_REVIEW)다.
      {
        label: '미처리 예외',
        value: data.openException,
        footer: '신규 + 검토중',
        tone: 'warning',
        to: href('/exceptions', { status: 'OPEN' }),
      },
    ] as const)

  return (
    <div className="page-content dashboard-page">
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
        <p role="status" className="empty-state">
          불러오는 중입니다.
        </p>
      ) : (
        <>
          <section className="kpi-grid" aria-label="주요 검증 지표">
            {kpis?.map((kpi) => (
              <Link
                key={kpi.label}
                to={kpi.to}
                className="kpi-card-link"
                aria-label={`${kpi.label} ${int(kpi.value ?? 0)}건`}
              >
                <KpiCard
                  label={kpi.label}
                  value={int(kpi.value ?? 0)}
                  unit="건"
                  footer={<span className="kpi-comparison">{kpi.footer}</span>}
                  tone={kpi.tone}
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
            </div>
            <aside className="dashboard-secondary-column" aria-label="마감 및 실행 현황">
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
                  data.recentRuns.map((run) => (
                    <RunRow key={run.validationRunId} run={run} to={href(`/validation-runs/${run.validationRunId}`)} />
                  ))
                ) : (
                  <div className="empty-state">{EMPTY_MESSAGE}</div>
                )}
              </section>
            </aside>
          </div>
        </>
      )}
    </div>
  )
}

function RunRow({ run, to }: { run: RecentRun; to: string }) {
  const step = run.currentStep ?? 0
  const progress = Math.max(0, Math.min(100, step * 10))
  return (
    <Link className="run-row" to={to}>
      <strong className="run-title">{`${run.validationMonth ?? ''} · ${run.runNo}회차`}</strong>
      <span className="run-meta">{`${step}/10 단계 · 실행 ${run.triggeredBy ?? '-'}`}</span>
      <StatusBadge tone={runStatusTone(run.status)}>{run.statusLabel ?? '-'}</StatusBadge>
      <span
        className="progress"
        role="progressbar"
        aria-label="검증 진행률"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress}
      >
        <span className="progress-bar" style={{ '--progress': `${progress}%` } as CSSProperties} />
      </span>
    </Link>
  )
}
