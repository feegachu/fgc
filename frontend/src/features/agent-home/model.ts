import type { AgentHome, HomeParams } from './api'

export type Period = 6 | 12 | 'all'
export type InsurerKind = 'all' | 'LIFE' | 'NON_LIFE'

// 그래프 색은 개편 전(#739)의 채도 낮은 팔레트다(#839). 다섯째는 분류 밖(기타)용 회색이다.
export const CATEGORY_COLORS = ['#9a82b5', '#a493b8', '#86a89c', '#c9ae8f', '#94a3b8']
export const INSURER_COLORS = ['#7f9fba', '#84ada3', '#caae7e', '#c492a0', '#a0b388', '#c99d88', '#8bb0bc', '#bbb589']

/** 계약 상태 4종의 순서와 색. 청약, 부활 등은 가까운 상태로 묶는다. */
export const STATUS_DEFS = [
  { key: 'ACTIVE', label: '정상', color: '#78a98a' },
  { key: 'UNPAID', label: '미납', color: '#d1ad74' },
  { key: 'LAPSED', label: '실효', color: '#c98a8a' },
  { key: 'END', label: '해지/종료', color: '#b8c0cc' },
] as const
/** 서버 상태 코드를 목업의 4종으로 묶는다. 상태 이력이 없어 서버가 UNKNOWN 으로 센 계약은 끝(해지·종료)에 섞지 않고 따로 둔다. */
export const STATUS_GROUP: Record<string, (typeof STATUS_DEFS)[number]['key']> = {
  APPLIED: 'ACTIVE',
  ACTIVE: 'ACTIVE',
  REVIVED: 'ACTIVE',
  UNPAID: 'UNPAID',
  LAPSED: 'LAPSED',
  CANCELLED: 'END',
  TERMINATED: 'END',
  MATURED: 'END',
}

export const periodParam = (period: Period): HomeParams['period'] => (period === 'all' ? 'ALL' : period === 6 ? '6' : '12')
export const kindParam = (kind: InsurerKind): HomeParams['insurerType'] => (kind === 'all' ? 'ALL' : kind)

export function shiftMonth(month: string, delta: number) {
  const [year, mon] = month.split('-').map(Number)
  const index = year * 12 + (mon - 1) + delta
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

/** 서울 기준 오늘(yyyy-MM-dd). */
export const seoulToday = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' })

/** 선택한 월의 기준일: 월말, 아직 끝나지 않은 달이면 오늘. */
export function asOfForMonth(month: string, today = seoulToday()) {
  const [year, mon] = month.split('-').map(Number)
  const last = `${month}-${String(new Date(year, mon, 0).getDate()).padStart(2, '0')}`
  return last > today ? today : last
}

/** 비율을 소수점 첫째 자리까지 절사한다(화면정의서 공통규칙 3). 먼저 1e-6에서 맞춰 28.999… 같은 곱셈 오차를 없앤다. */
export const oneDecimal = (value: number) => Math.trunc(Math.round(value * 1e6) / 1e5) / 10

/** 전월 대비 증감률(%, 소수점 한 자리). 전월 값이 0이면 비교할 수 없어 0으로 둔다. */
export const deltaPercent = (now: number, before: number) => (before ? oneDecimal(((now - before) / before) * 100) : 0)

export interface MonthStats {
  count: number
  premium: number
}
/** 서버 월별 집계에서 한 달 값을 읽는다. 범위 밖이거나 계약이 없는 달은 0이다. */
export function monthStats(home: AgentHome | undefined, month: string): MonthStats {
  const found = home?.portfolio?.monthly?.find((m) => m.month === month)
  return { count: found?.newContractCount ?? 0, premium: found?.monthlyEquivalentFirstPremiumSum ?? 0 }
}

export interface Slice {
  name: string
  value: number
  premium: number
  color: string
  insurerId: number
}
export function insurerSlices(home: AgentHome | undefined): Slice[] {
  return (home?.portfolio?.insurers ?? []).map((insurer, index) => ({
    name: insurer.name ?? '-',
    value: insurer.contractCount ?? 0,
    premium: insurer.monthlyEquivalentFirstPremiumSum ?? 0,
    color: INSURER_COLORS[index % INSURER_COLORS.length],
    insurerId: insurer.insurerId ?? 0,
  }))
}

export function trendSeries(home: AgentHome | undefined) {
  return (home?.portfolio?.monthly ?? []).map((m) => ({
    month: m.month ?? '',
    count: m.newContractCount ?? 0,
    premium: m.monthlyEquivalentFirstPremiumSum ?? 0,
  }))
}

export function categoryCounts(home: AgentHome | undefined) {
  const list = home?.portfolio?.categories ?? []
  const total = home?.portfolio?.contractCount || 1
  return list.map((c, i) => ({
    name: c.label ?? c.code ?? '-',
    count: c.count ?? 0,
    pct: oneDecimal(((c.count ?? 0) / total) * 100),
    color: CATEGORY_COLORS[i % CATEGORY_COLORS.length],
  }))
}

/** 상태 분포: 정상·미납·실효·해지종료 4종에 서버 UNKNOWN(상태 이력 없음)을 더해 분모를 보존한다. */
export function statusCounts(home: AgentHome | undefined) {
  const list = home?.portfolio?.statuses ?? []
  const total = home?.portfolio?.contractCount || 1
  const sums = new Map<string, number>()
  for (const s of list) {
    const key = s.code === 'UNKNOWN' ? 'UNKNOWN' : (STATUS_GROUP[s.code ?? ''] ?? 'END')
    sums.set(key, (sums.get(key) ?? 0) + (s.count ?? 0))
  }
  const defs = [...STATUS_DEFS, { key: 'UNKNOWN', label: '상태 미확인', color: '#d9dde3' }]
  return defs
    .map((def) => ({ ...def, count: sums.get(def.key) ?? 0, pct: oneDecimal(((sums.get(def.key) ?? 0) / total) * 100) }))
    .filter((def) => def.key !== 'UNKNOWN' || def.count > 0)
}
