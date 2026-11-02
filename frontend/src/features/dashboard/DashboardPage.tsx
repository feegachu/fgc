import { openSettlementGuide } from '../assistant/settlement/entry'
import { useQuery } from '@tanstack/react-query'
import type { CSSProperties } from 'react'
import { Link, useOutletContext } from 'react-router'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { KpiCard } from '../../components/KpiCard'
import { StatusBadge } from '../../components/StatusBadge'
import { dateTime, errorText, int } from '../../lib/format'
import { monthText, screenMessage } from '../../lib/screenText'
import { useDrawn } from '../../lib/motion'
import { withMonth } from '../../stores/workspace'
import type { ShellContext } from '../../app/shell/AppShell'
import { exceptionSearchQueryOptions, OPEN_STATUS } from '../exception/api'
import type { ExceptionFilters } from '../exception/api'
import { dashboardSummaryQueryOptions } from './api'
import type { RecentException, RecentRun } from './api'
import { DashboardSkeleton } from './DashboardSkeleton'
import { exceptionStatusTone, runStatusTone, severityTone } from './tones'

const EMPTY_MESSAGE = '조건에 맞는 자료가 없습니다.'
/** 예외함을 처음 열 때와 같은 조건(미처리 전체)이라 '예외함에서 처리'로 넘어가도 건수가 그대로다. */
const OPEN_EXCEPTIONS: ExceptionFilters = {
  type: '',
  reasonCode: '',
  severity: '',
  status: OPEN_STATUS,
  assigneeFilter: '',
  validationMonth: '',
  contractNo: '',
  sort: '',
  page: 1,
}

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
        <StatusBadge optionCount={14} kind="category">
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
          preview={<Link to={href('/exceptions', { contractNo: e.contractNo ?? '' })}>{screenMessage(e.title)}</Link>}
          full={screenMessage(e.title)}
        />
      ),
    },
    {
      key: 'status',
      label: '상태',
      align: 'center',
      render: (e) => (
        <StatusBadge optionCount={4} tone={exceptionStatusTone(e.status)} tablePresentation="text">
          {e.statusLabel ?? '-'}
        </StatusBadge>
      ),
    },
    { key: 'createdAt', label: '발생', cellClassName: 'tabular-nums', render: (e) => dateTime(e.createdAt) },
  ]

  // 미처리 예외는 아래 '처리할 예외' 제목 숫자로 보여 준다(공통 원칙 03). 숫자 색은 확정 위반과 원장 불균형만 위험색이다.
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
        tone: 'neutral',
        to: href('/cap-checks', { status: 'WARNING' }),
      },
      {
        id: 'arbitrage',
        label: '차익거래 검토대상',
        value: data.arbitrageCandidate,
        footer: '위반 확정 아님',
        tone: 'neutral',
        to: href('/arbitrage-checks', { status: 'CANDIDATE' }),
      },
      {
        id: 'recon',
        label: '대사 불일치',
        value: data.reconMismatch,
        footer: '허용오차 0원',
        tone: 'neutral',
        to: href('/reconciliations', { onlyMismatch: 'true' }),
      },
      {
        id: 'journal',
        label: '원장 불균형',
        value: data.journalImbalance,
        footer: '기표 전 0건 필수',
        tone: 'error',
        to: href('/journals', { imbalanceOnly: 'true' }),
      },
    ] as const)

  return (
    <div className="dashboard-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">업무 대시보드</h1>
        </div>
        <button
          type="button"
          className="button button-secondary"
          data-guide-target="dashboard.guide"
          onClick={openSettlementGuide}
        >
          전체 업무 가이드
        </button>
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
            {kpis?.map((kpi) => (
              <Link
                key={kpi.label}
                to={kpi.to}
                className="kpi-card-link"
                data-kpi={kpi.id}
                data-zero={(kpi.value ?? 0) === 0}
                data-attention={kpi.tone === 'error' && (kpi.value ?? 0) > 0}
                aria-label={`${kpi.label} ${int(kpi.value ?? 0)}건`}
              >
                <KpiCard
                  label={kpi.label}
                  value={kpi.value ?? 0}
                  unit="건"
                  footer={<span className="kpi-comparison">{kpi.footer}</span>}
                  tone={kpi.tone}
                  zero={(kpi.value ?? 0) === 0}
                />
              </Link>
            ))}
          </section>
          <div className="dashboard-main-grid">
            <section className="surface" aria-labelledby="priority-title">
              <header className="surface-header">
                <h2 id="priority-title" className="surface-title">
                  최근 예외
                </h2>
                <Link className="run-list-link" to={href('/exceptions')}>
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
              <OpenExceptions total={data?.openException ?? 0} href={href} />
              <section className="surface run-list-card" aria-labelledby="run-list-title">
                <div className="run-list-header">
                  <h2 id="run-list-title" className="run-list-title">
                    최근 통합검증 실행
                  </h2>
                  <Link className="run-list-link" to={href('/validation-runs')}>
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
    </div>
  )
}

/** 처리할 예외: 미처리 건수를 제목 숫자로, 유형별 건수를 예외함으로 가는 목록으로 보여 준다. */
function OpenExceptions({
  total,
  href,
}: {
  total: number
  href: (path: string, params?: Record<string, string>) => string
}) {
  const open = useQuery(exceptionSearchQueryOptions(OPEN_EXCEPTIONS))
  const types = [...(open.data?.summary ?? [])]
    .filter((item) => (item.count ?? 0) > 0)
    .sort((a, b) => (b.count ?? 0) - (a.count ?? 0))
  return (
    <section className="surface dashboard-open" aria-labelledby="open-title">
      <header className="surface-header">
        <h2 id="open-title" className="surface-title">
          처리할 예외 <span className="dashboard-open-total tabular-nums">{int(total)}건</span>
        </h2>
        <Link className="run-list-link" to={href('/exceptions', { status: OPEN_STATUS })}>
          예외함에서 처리
        </Link>
      </header>
      {types.length ? (
        <ul className="dashboard-open-list">
          {types.map((item) => (
            <li key={item.type}>
              <Link
                to={href('/exceptions', { type: item.type ?? '', status: OPEN_STATUS })}
                className={item.type === 'CAP_VIOLATION' ? 'is-danger' : undefined}
              >
                <span>{item.typeLabel ?? '-'}</span>
                <strong className="tabular-nums">{int(item.count ?? 0)}건</strong>
              </Link>
            </li>
          ))}
        </ul>
      ) : (
        <p className="dashboard-open-empty">{total ? '유형별 건수는 예외함에서 확인할 수 있습니다.' : '처리할 예외가 없습니다.'}</p>
      )}
    </section>
  )
}

const RUN_STEPS = 10

function RunRow({ run, to }: { run: RecentRun; to: string }) {
  const step = run.currentStep ?? 0
  const progress = Math.max(0, Math.min(100, step * 10))
  const drawn = useDrawn()
  return (
    <Link className="run-row" to={to}>
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
            className={`run-step${drawn && index < step ? ' is-done' : ''}`}
            style={{ '--i': index } as CSSProperties}
          />
        ))}
      </span>
    </Link>
  )
}
