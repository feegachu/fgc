import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useOutletContext } from 'react-router'
import { KpiCardContent } from '../../components/KpiCard'
import { errorText, int } from '../../lib/format'
import type { ShellContext } from '../../app/shell/AppShell'
import { useAuth } from '../auth/useAuth'
import { NEED_CHECK_STATUSES } from '../contract/api'
import { agentCapCountQuery, agentHomeQuery } from './api'
import type { AgentHome } from './api'
import { Donut, ProductBars, StatusBar, Trend } from './charts'
import {
  asOfForMonth,
  categoryCounts,
  deltaPercent,
  insurerSlices,
  kindParam,
  monthStats,
  periodParam,
  shiftMonth,
  statusCounts,
  trendSeries,
} from './model'
import type { InsurerKind, Period } from './model'
import { PriorityContracts } from './PriorityContracts'
import { ChatbotModal } from './chatbot/ChatbotModal'
import './agent-home.css'
import workflowGuides from '../assistant/workflow-guides.v1.json'
import { useWorkflowGuide } from '../assistant/workflow-state'
import { guideFeatureId } from '../assistant/feature-ids'

const PERIODS: { value: Period; label: string }[] = [
  { value: 6, label: '6개월' },
  { value: 12, label: '12개월' },
  { value: 'all', label: '전체' },
]
const KINDS: { value: InsurerKind; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: 'LIFE', label: '생명' },
  { value: 'NON_LIFE', label: '손해' },
]
type CountQuery = { data?: number; isError: boolean; refetch?: () => unknown }
type FocusState = 'ready' | 'loading' | 'error'
const periodLabel = (p: Period) => (p === 'all' ? '전체 기간' : `최근 ${p}개월`)
const kindLabel = (k: InsurerKind) => (k === 'all' ? '전체 보험사' : k === 'LIFE' ? '생명보험' : '손해보험')

export function AgentHomePage() {
  const { month } = useOutletContext<ShellContext>()
  if (!month) return null
  return <AgentHomeContent month={month} />
}

function Segmented<T extends string | number>({
  label,
  options,
  value,
  onChange,
}: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  const rail = useRef<HTMLDivElement>(null)
  const [thumb, setThumb] = useState({ left: 2, width: 0 })
  useEffect(() => {
    const on = rail.current?.querySelector<HTMLElement>('button[aria-pressed="true"]')
    if (on) setThumb({ left: on.offsetLeft, width: on.offsetWidth })
  }, [value, options])
  return (
    <div className="ah-seg" role="group" aria-label={label} ref={rail}>
      <span className="ah-seg-thumb" style={{ left: thumb.left, width: thumb.width }} />
      {options.map((option) => (
        <button data-guide-feature="features.agent-home.agent-home-page.button"
          key={String(option.value)}
          type="button"
          aria-pressed={value === option.value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

/** 전월 대비 증감. 전월 실적이 0이면 비율을 낼 수 없으므로 '전월 실적 없음'이라고 쓴다(B2). */
function Delta({ now, before }: { now: number; before?: number }) {
  if (before == null) return <span>-</span>
  if (before === 0) return <span>전월 실적 없음</span>
  const value = deltaPercent(now, before)
  if (value === 0) return <span>전월 대비 변동 없음</span>
  return (
    <>
      <span className={`ah-delta ${value >= 0 ? 'up' : 'down'}`}>{`${value >= 0 ? '▲' : '▼'} ${Math.abs(value).toFixed(1)}%`}</span>
      <span>전월 대비</span>
    </>
  )
}

/** 카드의 건수와 같은 조건(이번 달 계약·한도 주의/위반/검토필요)으로 계약 목록을 연다. */
const needContractsHref = (month: string, asOf: string) => {
  const query = new URLSearchParams({
    capResultStatus: NEED_CHECK_STATUSES,
    contractDateFrom: `${month}-01`,
    contractDateTo: asOf,
  })
  return `/contracts?${query.toString()}`
}

function AgentHomeContent({ month }: { month: string }) {
  const { user } = useAuth()
  const asOf = asOfForMonth(month)
  const [period, setPeriod] = useState<Period>(12)
  const [kind, setKind] = useState<InsurerKind>('all')
  const [insurerId, setInsurerId] = useState<number | null>(null)
  const [chatOpen, setChatOpen] = useState(false)
  const closeChat = () => setChatOpen(false)

  // 지표·이번 달 수수료·확인할 계약은 필터와 무관한 기본 조회(12개월·전체 보험사)를 쓴다.
  const summary = useQuery(agentHomeQuery({ asOf, period: '12', insurerType: 'ALL' }))
  // 그래프는 선택한 기간·보험사 구분. 보험사를 고르면 그 보험사만 좁힌 조회를 하나 더 한다.
  const chart = useQuery(agentHomeQuery({ asOf, period: periodParam(period), insurerType: kindParam(kind) }))
  const focus = useQuery({
    ...agentHomeQuery({ asOf, period: periodParam(period), insurerType: kindParam(kind), insurerId }),
    enabled: insurerId != null,
  })
  const near = useQuery(agentCapCountQuery(month, asOf, 'WARNING,VIOLATION'))
  const review = useQuery(agentCapCountQuery(month, asOf, 'REVIEW_REQUIRED'))
  const error = (summary.error ?? chart.error) as Error | null
  const chartData = chart.data
  // 보험사를 고른 직후(이전 집계를 임시로 보여 주는 동안)나 조회 실패를 0건 차트로 그리지 않는다.
  const focusState: FocusState =
    insurerId == null ? 'ready' : focus.isError ? 'error' : focus.isPending || focus.isPlaceholderData ? 'loading' : 'ready'
  const focusData = insurerId != null ? (focusState === 'ready' ? focus.data : undefined) : chartData

  return (
    <div className="ah">
      <h1 className="visually-hidden">포트폴리오</h1>
      {import.meta.env.VITE_ASSISTANT_AI === 'true' && (
        <details className="surface" style={{ padding: 12, marginBottom: 12 }}>
          <summary data-guide-feature="agent.guide-menu.open" style={{ cursor: 'pointer' }}>업무 안내</summary>
          <nav aria-label="본인 업무 안내" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
            {workflowGuides.filter((guide) => guide.enabled && guide.roles.includes('AGENT')).map((guide) => (
              <button data-guide-feature={guideFeatureId('agent.guide-menu', guide.workflowId)} key={guide.workflowId} type="button" className="button button-secondary"
                onClick={(event) => {
                  const menu = event.currentTarget.closest('details')
                  if (menu) menu.open = false
                  useWorkflowGuide.getState().start(guide.workflowId)
                }}>{guide.title}</button>
            ))}
          </nav>
        </details>
      )}
      {error ? (
        <div role="alert" className="empty-state">
          <p>{errorText(error, '포트폴리오를 불러오지 못했습니다.')}</p>
          <button data-guide-feature="features.agent-home.agent-home-page.button-2"
            type="button"
            className="button button-secondary"
            onClick={() => void Promise.all([summary.refetch(), chart.refetch()])}
          >
            다시 시도
          </button>
        </div>
      ) : !summary.data || !chartData ? (
        <div className="empty-state" aria-busy="true">
          불러오는 중입니다.
        </div>
      ) : (
        <>
          <Snapshot
            month={month}
            home={summary.data}
            name={user?.userName ?? ''}
            launcher={<ChatbotLauncher open={chatOpen} onOpen={() => setChatOpen(true)} onClose={closeChat} />}
            asOf={asOf}
            near={near}
            review={review}
          />
          <PriorityContracts home={summary.data} />
          <Charts
            home={chartData}
            focusHome={focusData}
            focusState={focusState}
            onRetryFocus={() => void focus.refetch()}
            period={period}
            kind={kind}
            insurerId={insurerId}
            onPeriod={setPeriod}
            onKind={(next) => {
              setKind(next)
              setInsurerId(null)
            }}
            onInsurer={(id) => setInsurerId((current) => (current === id ? null : id))}
          />
        </>
      )}
    </div>
  )
}

function Snapshot({
  month,
  home,
  name,
  launcher,
  asOf,
  near,
  review,
}: {
  month: string
  home: AgentHome
  name: string
  launcher: ReactNode
  asOf: string
  near: CountQuery
  review: CountQuery
}) {
  const now = monthStats(home, month)
  const before = monthStats(home, shiftMonth(month, -1))
  const countDelta = now.count - before.count
  const commission = home.myCommission
  const expected = commission?.expected?.amount ?? 0
  const needReady = near.data != null && review.data != null
  const needFailed = near.isError || review.isError
  const need = (near.data ?? 0) + (review.data ?? 0)
  return (
    <section data-guide-feature="features.agent-home.agent-home-page.section" className="ah-snapshot" aria-label="이번 달 판매 현황">
      <article className="ah-hero" aria-label="설계사 프로필">
        <span className="ah-avatar" aria-hidden="true">
          {name.slice(0, 1) || '설'}
        </span>
        <div className="ah-profile">
          <p className="ah-profile-role">안녕하세요</p>
          <p className="ah-profile-name">{name ? `${name}님` : '설계사님'}</p>
        </div>
        {launcher}
      </article>
      <h2 className="ah-heading">이번달 현황</h2>
      <div className="ah-kpis kpi-group" role="region" aria-label="이번 달 현황">
        <article className="ah-kpi kpi-card kpi-card-info" aria-label="신규 계약 건수">
          <KpiCardContent
            label="신규 계약 건수"
            value={int(now.count)}
            zero={now.count === 0}
            unit="건"
            footer={
              <>
                {countDelta === 0 ? (
                  <span>변동 없음</span>
                ) : (
                  <span className={`ah-delta ${countDelta > 0 ? 'up' : 'down'}`}>
                    {`${countDelta > 0 ? '▲' : '▼'} ${Math.abs(countDelta)}`}
                  </span>
                )}
                <span>전월 {int(before.count)}건</span>
              </>
            }
          />
        </article>
        <article className="ah-kpi kpi-card kpi-card-info" aria-label="월납환산 초회보험료">
          <KpiCardContent
            label="월납환산 초회보험료"
            value={int(now.premium)}
            zero={now.premium === 0}
            unit="원"
            footer={<Delta now={now.premium} before={before.premium} />}
          />
        </article>
        <Link
          data-guide-feature="features.agent-home.agent-home-page.commission-link"
          className="ah-kpi ah-kpi-link kpi-card kpi-card-info"
          to="/transactions"
          aria-label="수령 예정 수수료"
        >
          <KpiCardContent
            label="수령 예정 수수료"
            value={int(expected)}
            zero={expected === 0}
            unit="원"
            footer={
              <span>{`${commission?.month ?? month} 기준 · 보류 ${int(commission?.held?.amount ?? 0)}원 · 환수 계산 ${int(commission?.clawback?.amount ?? 0)}원`}</span>
            }
          />
        </Link>
        {needFailed ? (
          <article className="ah-kpi ah-kpi-alert kpi-card kpi-card-warning" aria-label="확인 필요 계약">
            <KpiCardContent
              label="확인 필요 계약"
              value="확인 불가"
              footer={
                <button
                  data-guide-feature="features.agent-home.agent-home-page.button-3"
                  type="button"
                  className="ah-retry"
                  onClick={() => void Promise.all([near.refetch?.(), review.refetch?.()])}
                >
                  다시 시도
                </button>
              }
            />
          </article>
        ) : (
          <Link
            data-guide-feature="features.agent-home.agent-home-page.need-contracts-link"
            className="ah-kpi ah-kpi-link ah-kpi-alert kpi-card kpi-card-warning"
            to={needContractsHref(month, asOf)}
            aria-label="확인 필요 계약"
          >
            <KpiCardContent
              label="확인 필요 계약"
              value={needReady ? int(need) : '…'}
              zero={needReady && need === 0}
              unit="건"
              footer={
                <div className="ah-badges">
                  <span className="ah-badge warn">
                    한도 근접 <b>{near.data ?? '-'}</b>
                  </span>
                  <span className="ah-badge danger">
                    검토 필요 <b>{review.data ?? '-'}</b>
                  </span>
                </div>
              }
            />
          </Link>
        )}
      </div>
    </section>
  )
}

function Charts({
  home,
  focusHome,
  focusState,
  onRetryFocus,
  period,
  kind,
  insurerId,
  onPeriod,
  onKind,
  onInsurer,
}: {
  home: AgentHome
  focusHome: AgentHome | undefined
  focusState: FocusState
  onRetryFocus: () => void
  period: Period
  kind: InsurerKind
  insurerId: number | null
  onPeriod: (value: Period) => void
  onKind: (value: InsurerKind) => void
  onInsurer: (id: number) => void
}) {
  const slices = insurerSlices(home)
  const selected = slices.find((s) => s.insurerId === insurerId) ?? null
  const label = periodLabel(period)
  const rate = statusCounts(focusHome)
  // 경고·합계·분포는 모두 같은 모집단(선택한 보험사까지 좁힌 집계)에서 읽는다.
  const shown = focusHome ?? home
  const warnings = focusState === 'ready' ? (shown.basis?.warnings ?? []) : []
  const range = shown.range
  return (
    <section data-guide-feature="features.agent-home.agent-home-page.section-2" className="ah-panel" aria-label="설계사 판매 관리 그래프">
      <div className="ah-panel-head">
        <div>
          <h2 className="ah-panel-title">설계사 판매 관리</h2>
          <p className="ah-panel-sub">{`${label}, ${kindLabel(kind)}`}</p>
          <p className="ah-panel-sub">{`기준일 ${home.asOf} · 집계 기간 ${range?.from ?? '-'} ~ ${range?.to ?? '-'} · 분모 계약 ${home.portfolio?.contractCount ?? 0}건`}</p>
        </div>
        <div className="ah-controls">
          <Segmented label="기간" options={PERIODS} value={period} onChange={onPeriod} />
          <Segmented label="보험사 구분" options={KINDS} value={kind} onChange={onKind} />
        </div>
      </div>
      <div className="ah-filter-row">
        {selected && (
          <div className="ah-chip">
            <span className="ah-chip-dot" />
            {selected.name} 필터 적용 중
            <button
              data-guide-feature="features.agent-home.agent-home-page.button-5"
              type="button"
              aria-label="보험사 필터 해제"
              onClick={() => onInsurer(selected.insurerId)}
            >
              ✕
            </button>
          </div>
        )}
        {focusState === 'ready' && <p className="ah-note">합계 {focusHome?.portfolio?.contractCount ?? 0}건</p>}
      </div>
      {warnings.map((warning) => (
        <p key={warning} className="ah-note" role="note">
          {warning}
        </p>
      ))}
      {focusState === 'error' ? (
        <div role="alert" className="empty-state">
          <p>선택한 보험사의 집계를 불러오지 못했습니다.</p>
          <button
            data-guide-feature="features.agent-home.agent-home-page.button-4"
            type="button"
            className="button button-secondary"
            onClick={onRetryFocus}
          >
            다시 시도
          </button>
        </div>
      ) : focusState === 'loading' ? (
        <div className="empty-state" aria-busy="true">
          선택한 보험사 집계를 불러오는 중입니다.
        </div>
      ) : (
        <div className="ah-chart-grid">
        <div className="ah-box ah-donut-box">
          <h3>
            보험사별 계약 건수 <span className="ah-sub">{label}</span>
          </h3>
          <div className="ah-donut-flex">
            <Donut
              slices={slices}
              total={home.portfolio?.contractCount ?? 0}
              centerTotal={focusHome?.portfolio?.contractCount ?? 0}
              hint={selected?.name ?? label}
              selected={selected?.name ?? null}
              onSelect={(name) => {
                const slice = slices.find((s) => s.name === name)
                if (slice) onInsurer(slice.insurerId)
              }}
            />
          </div>
        </div>
        <div className="ah-box ah-trend-box">
          <h3>
            월별 신규 계약 추이 <span className="ah-sub">막대 건수, 선 월납환산 초회보험료(오른쪽 축)</span>
          </h3>
          <Trend points={trendSeries(focusHome)} />
        </div>
        <div className="ah-box ah-product-box">
          <h3>
            보장 유형별 비중 <span className="ah-sub">건수 기준</span>
          </h3>
          <ProductBars items={categoryCounts(focusHome)} />
        </div>
        <div className="ah-box ah-status-box">
          <h3>
            계약 상태 분포 <span className="ah-sub">정상률 {(rate[0]?.pct ?? 0).toFixed(1)}%</span>
          </h3>
          <StatusBar items={rate} />
        </div>
        </div>
      )}
    </section>
  )
}

/** 상품 추천 AI(FUN-079). 버튼을 누르면 채팅형 모달이 열리고, 닫으면 포커스가 버튼으로 돌아온다. */
function ChatbotLauncher({ open, onOpen, onClose }: { open: boolean; onOpen: () => void; onClose: () => void }) {
  const launcher = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const trigger = launcher.current
    return () => trigger?.focus({ preventScroll: true })
  }, [open])
  return (
    <>
      <button data-guide-feature="features.agent-home.agent-home-page.fgc-ai"
        ref={launcher}
        type="button"
        className="button button-primary ah-ai-button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={onOpen}
      >
        상품 추천 AI
      </button>
      {open && <ChatbotModal onClose={onClose} />}
    </>
  )
}
