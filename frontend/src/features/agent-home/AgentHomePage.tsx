import { useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { Link, useOutletContext } from 'react-router'
import { AnimatedNumber } from '../../components/AnimatedNumber'
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
        <button
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

function Delta({ value }: { value: number }) {
  return (
    <span
      className={`ah-delta ${value >= 0 ? 'up' : 'down'}`}
    >{`${value >= 0 ? '▲' : '▼'} ${Math.abs(value).toFixed(1)}%`}</span>
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
      {error ? (
        <div role="alert" className="empty-state">
          <p>{errorText(error, '포트폴리오를 불러오지 못했습니다.')}</p>
          <button
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
      <ChatbotLauncher
        open={chatOpen}
        onOpen={() => setChatOpen(true)}
        onClose={closeChat}
      />
    </div>
  )
}

function Snapshot({
  month,
  rows,
  name,
  commission,
  previousCommission,
}: {
  month: string
  rows: Row[]
  name: string
  commission: { data?: number; isError: boolean; refetch: () => unknown }
  previousCommission?: number
}) {
  const now = monthStats(rows, month)
  const before = monthStats(rows, shiftMonth(month, -1))
  const countDelta = now.count - before.count
  const need = now.near + now.review
  return (
    <section className="ah-snapshot" aria-label="이번 달 판매 현황">
      <article className="ah-hero" aria-label="설계사 프로필">
        <span className="ah-avatar">{name.slice(0, 1) || '설'}</span>
        <div className="ah-profile">
          <p className="ah-profile-role">안녕하세요</p>
          <p className="ah-profile-name">{name ? `${name}님` : "설계사님"}</p>
        </div>
      </article>
      <h2 className="ah-heading">이번달 현황</h2>
      <div className="ah-kpis" role="region" aria-label="이번 달 현황">
        <article className="ah-kpi" aria-label="신규 계약 건수">
          <span className="ah-kpi-label">신규 계약 건수</span>
          <div className="ah-kpi-num">
            <AnimatedNumber value={now.count} />
            <span className="ah-kpi-unit">건</span>
          </div>
          <div className="ah-kpi-foot">
            <span
              className={`ah-delta ${countDelta >= 0 ? 'up' : 'down'}`}
            >{`${countDelta >= 0 ? '▲' : '▼'} ${Math.abs(countDelta)}`}</span>
            <span>전월 {before.count}건</span>
          </div>
        </article>
        <article className="ah-kpi" aria-label="월 환산 초회보험료">
          <span className="ah-kpi-label">월 환산 초회보험료</span>
          <div className="ah-kpi-num">
            <AnimatedNumber value={now.premium} format={int} />
            <span className="ah-kpi-unit">원</span>
          </div>
          <div className="ah-kpi-foot">
            <Delta value={deltaPercent(now.premium, before.premium)} />
            <span>전월 대비</span>
          </div>
        </article>
        {commission.isError ? (
          <article className="ah-kpi" aria-label="수령 예정 수수료">
            <span className="ah-kpi-label">수령 예정 수수료</span>
            <div className="ah-kpi-num">확인 불가</div>
            <div className="ah-kpi-foot">
              <button type="button" className="ah-retry" onClick={() => void commission.refetch()}>
                다시 시도
              </button>
            </div>
          </article>
        ) : (
          <Link className="ah-kpi ah-kpi-link" to={`/agent/statements?month=${month}`} aria-label="수령 예정 수수료">
            <span className="ah-kpi-label">수령 예정 수수료</span>
            <div className="ah-kpi-num">
              {commission.data == null ? '…' : <AnimatedNumber value={commission.data} format={int} />}
              <span className="ah-kpi-unit">원</span>
            </div>
            <div className="ah-kpi-foot">
              {commission.data != null && previousCommission != null ? (
                <Delta value={deltaPercent(commission.data, previousCommission)} />
              ) : (
                <span>-</span>
              )}
              <span>전월 대비</span>
            </div>
          </Link>
        )}
        <Link className="ah-kpi ah-kpi-link ah-kpi-alert" to={needContractsHref(month)} aria-label="확인 필요 계약">
          <span className="ah-kpi-label">확인 필요 계약</span>
          <div className="ah-kpi-num">
            <AnimatedNumber value={need} />
            <span className="ah-kpi-unit">건</span>
          </div>
          <div className="ah-badges">
            <span className="ah-badge warn">
              한도 근접 <b>{now.near}</b>
            </span>
            <span className="ah-badge danger">
              검토 필요 <b>{now.review}</b>
            </span>
          </div>
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
    <section className="ah-panel" aria-label="설계사 판매 관리 그래프">
      <div className="ah-panel-head">
        <div>
          <h2 className="ah-panel-title">설계사 판매 관리</h2>
          <p className="ah-panel-sub">{`${label} · ${kindLabel(kind)}`}</p>
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
            <button type="button" aria-label="보험사 필터 해제" onClick={() => onInsurer(insurer)}>
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
            월별 신규 계약 추이 <span className="ah-sub">막대·선 건수 · 보험료는 마우스를 올려 확인</span>
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

/** 상품 추천 AI(FUN-079). 런처를 누르면 채팅형 모달이 열리고, 닫으면 포커스가 런처로 돌아온다. */
function ChatbotLauncher({ open, onOpen, onClose }: { open: boolean; onOpen: () => void; onClose: () => void }) {
  const launcher = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const trigger = launcher.current
    return () => trigger?.focus({ preventScroll: true })
  }, [open])
  const base = import.meta.env.BASE_URL
  return (
    <>
      <button
        ref={launcher}
        type="button"
        className="ah-launcher"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label="FGC 보험 상품 비교분석 추천 AI 열기"
        onClick={onOpen}
      >
        <img
          src={`${base}images/brand/agent-mascot.png`}
          alt=""
          width={128}
          height={128}
          style={{ '--i': 0 } as CSSProperties}
        />
        <span className="ah-launcher-label">상품 추천 AI</span>
      </button>
      {open && <ChatbotModal onClose={onClose} />}
    </>
  )
}
