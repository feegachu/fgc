import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useOutletContext } from 'react-router'
import { KpiCardContent } from '../../components/KpiCard'
import { errorText, int } from '../../lib/format'
import type { ShellContext } from '../../app/shell/AppShell'
import { useAuth } from '../auth/useAuth'
import { NEED_CHECK_STATUSES } from '../contract/api'
import { agentCommissionQuery, agentContractsQuery, agentInsurersQuery, agentProductsQuery } from './api'
import { Donut, ProductBars, StatusBar, Trend } from './charts'
import {
  aggregate,
  buildRows,
  categoryCounts,
  deltaPercent,
  insurerSlices,
  monthStats,
  shiftMonth,
  statusCounts,
  trendSeries,
} from './model'
import type { InsurerKind, Period, Row } from './model'
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
const needContractsHref = (month: string) => {
  const [year, mon] = month.split('-').map(Number)
  const last = String(new Date(year, mon, 0).getDate()).padStart(2, '0')
  const query = new URLSearchParams({
    capResultStatus: NEED_CHECK_STATUSES,
    contractDateFrom: `${month}-01`,
    contractDateTo: `${month}-${last}`,
  })
  return `/contracts?${query.toString()}`
}

function AgentHomeContent({ month }: { month: string }) {
  const { user } = useAuth()
  const contracts = useQuery(agentContractsQuery())
  const insurers = useQuery(agentInsurersQuery())
  const insurerIds = (insurers.data ?? []).flatMap((i) => (i.insurerId == null ? [] : [i.insurerId]))
  const products = useQuery(agentProductsQuery(insurerIds, `${month}-01`))
  const commission = useQuery(agentCommissionQuery(month))
  const previousCommission = useQuery(agentCommissionQuery(shiftMonth(month, -1)))
  const [period, setPeriod] = useState<Period>(12)
  const [kind, setKind] = useState<InsurerKind>('all')
  const [insurer, setInsurer] = useState<string | null>(null)
  const [chatOpen, setChatOpen] = useState(false)
  const closeChat = useCallback(() => setChatOpen(false), [])

  // 보험사·상품 기준정보보다 먼저 그리면 생명·손해 구분과 보장 유형을 못 맞춰 그래프가 잠깐 틀리게 보인다.
  const productsReady = insurerIds.length === 0 || products.data != null
  const rows = useMemo(
    () =>
      contracts.data && insurers.data && productsReady
        ? buildRows(contracts.data, insurers.data, products.data ?? [])
        : null,
    [contracts.data, insurers.data, products.data, productsReady],
  )
  const error = (contracts.error ?? insurers.error ?? products.error) as Error | null

  return (
    <div className="ah">
      <h1 className="visually-hidden">포트폴리오</h1>
      <ScreenHelp screenId="AGNT-W01" />
      {import.meta.env.VITE_ASSISTANT_AI === 'true' && (
        <details className="surface" style={{ padding: 12, marginBottom: 12 }}>
          <summary data-guide-feature="agent.guide-menu.open" style={{ cursor: 'pointer' }}>업무 안내</summary>
          <nav aria-label="본인 업무 튜토리얼" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
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
            onClick={() => void Promise.all([contracts.refetch(), insurers.refetch(), products.refetch()])}
          >
            다시 시도
          </button>
        </div>
      ) : !rows ? (
        <div className="empty-state" aria-busy="true">
          불러오는 중입니다.
        </div>
      ) : (
        <>
          <Snapshot
            month={month}
            rows={rows}
            name={user?.userName ?? ''}
            launcher={<ChatbotLauncher open={chatOpen} onOpen={() => setChatOpen(true)} onClose={closeChat} />}
            commission={commission}
            previousCommission={previousCommission.data}
          />
          <Charts
            month={month}
            rows={rows}
            insurers={insurers.data ?? []}
            period={period}
            kind={kind}
            insurer={insurer}
            onPeriod={setPeriod}
            onKind={(next) => {
              setKind(next)
              if (insurer && next !== 'all') {
                const match = (insurers.data ?? []).find((i) => i.insurerName === insurer)
                if (match && match.insurerType !== next) setInsurer(null)
              }
            }}
            onInsurer={(name) => setInsurer((current) => (current === name ? null : name))}
          />
        </>
      )}
    </div>
  )
}

function Snapshot({
  month,
  rows,
  name,
  launcher,
  commission,
  previousCommission,
}: {
  month: string
  rows: Row[]
  name: string
  launcher: ReactNode
  commission: { data?: number; isError: boolean; refetch: () => unknown }
  previousCommission?: number
}) {
  const now = monthStats(rows, month)
  const before = monthStats(rows, shiftMonth(month, -1))
  const countDelta = now.count - before.count
  const need = now.near + now.review
  return (
    <section data-guide-feature="features.agent-home.agent-home-page.section" data-guide-target="agent.overview" className="ah-snapshot" aria-label="이번 달 판매 현황">
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
        <article className="ah-kpi kpi-card kpi-card-info" aria-label="월 환산 초회보험료">
          <KpiCardContent
            label="월 환산 초회보험료"
            value={int(now.premium)}
            zero={now.premium === 0}
            unit="원"
            footer={<Delta now={now.premium} before={before.premium} />}
          />
        </article>
        {commission.isError ? (
          <article className="ah-kpi kpi-card kpi-card-info" aria-label="수령 예정 수수료">
            <KpiCardContent
              label="수령 예정 수수료"
              value="확인 불가"
              footer={
                <button data-guide-feature="features.agent-home.agent-home-page.button-3" type="button" className="ah-retry" onClick={() => void commission.refetch()}>
                  다시 시도
                </button>
              }
            />
          </article>
        ) : (
          <Link data-guide-feature="features.agent-home.agent-home-page.commission-link" className="ah-kpi ah-kpi-link kpi-card kpi-card-info" to="/transactions" aria-label="수령 예정 수수료">
            <KpiCardContent
              label="수령 예정 수수료"
              value={commission.data == null ? '…' : int(commission.data)}
              zero={commission.data === 0}
              unit="원"
              footer={
                commission.data == null ? <span>-</span> : <Delta now={commission.data} before={previousCommission} />
              }
            />
          </Link>
        )}
        <Link
          data-guide-feature="features.agent-home.agent-home-page.need-contracts-link"
          className="ah-kpi ah-kpi-link ah-kpi-alert kpi-card kpi-card-warning"
          to={needContractsHref(month)}
          aria-label="확인 필요 계약"
        >
          <KpiCardContent
            label="확인 필요 계약"
            value={int(need)}
            zero={need === 0}
            unit="건"
            footer={
              <div className="ah-badges">
                <span className="ah-badge warn">
                  한도 근접 <b>{now.near}</b>
                </span>
                <span className="ah-badge danger">
                  검토 필요 <b>{now.review}</b>
                </span>
              </div>
            }
          />
        </Link>
      </div>
    </section>
  )
}

function Charts({
  month,
  rows,
  insurers,
  period,
  kind,
  insurer,
  onPeriod,
  onKind,
  onInsurer,
}: {
  month: string
  rows: Row[]
  insurers: { insurerName?: string; insurerType?: 'LIFE' | 'NON_LIFE' }[]
  period: Period
  kind: InsurerKind
  insurer: string | null
  onPeriod: (value: Period) => void
  onKind: (value: InsurerKind) => void
  onInsurer: (name: string) => void
}) {
  const { base, focus, months } = aggregate(rows, month, period, kind, insurer)
  const slices = insurerSlices(base, insurers, kind)
  const label = periodLabel(period)
  const rate = statusCounts(focus)
  return (
    <section data-guide-feature="features.agent-home.agent-home-page.section-2" className="ah-panel" aria-label="설계사 판매 관리 그래프">
      <div className="ah-panel-head">
        <div>
          <h2 className="ah-panel-title">설계사 판매 관리</h2>
          <p className="ah-panel-sub">{`${label}, ${kindLabel(kind)}`}</p>
        </div>
        <div className="ah-controls">
          <Segmented label="기간" options={PERIODS} value={period} onChange={onPeriod} />
          <Segmented label="보험사 구분" options={KINDS} value={kind} onChange={onKind} />
        </div>
      </div>
      <div className="ah-filter-row">
        {insurer && (
          <div className="ah-chip">
            <span className="ah-chip-dot" />
            {insurer} 필터 적용 중
            <button data-guide-feature="features.agent-home.agent-home-page.button-5" type="button" aria-label="보험사 필터 해제" onClick={() => onInsurer(insurer)}>
              ✕
            </button>
          </div>
        )}
        <p className="ah-note">합계 {focus.length}건</p>
      </div>
      <div className="ah-chart-grid">
        <div className="ah-box ah-donut-box">
          <h3>
            보험사별 계약 건수 <span className="ah-sub">{label}</span>
          </h3>
          <div className="ah-donut-flex">
            <Donut
              slices={slices}
              total={base.length}
              centerTotal={focus.length}
              hint={insurer ?? label}
              selected={insurer}
              onSelect={onInsurer}
            />
          </div>
        </div>
        <div className="ah-box ah-trend-box">
          <h3>
            월별 신규 계약 추이 <span className="ah-sub">막대 건수, 선 월환산 보험료(오른쪽 축)</span>
          </h3>
          <Trend points={trendSeries(focus, months)} />
        </div>
        <div className="ah-box ah-product-box">
          <h3>
            보장 유형별 비중 <span className="ah-sub">건수 기준</span>
          </h3>
          <ProductBars items={categoryCounts(focus)} />
        </div>
        <div className="ah-box ah-status-box">
          <h3>
            계약 상태 분포 <span className="ah-sub">정상률 {rate[0].pct.toFixed(1)}%</span>
          </h3>
          <StatusBar items={rate} />
        </div>
      </div>
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
