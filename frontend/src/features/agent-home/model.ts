import type { AgentHome, HomeParams } from './api'

export type Period = 6 | 12 | 'all'
export type InsurerKind = 'all' | 'LIFE' | 'NON_LIFE'

const CATEGORY_COLORS = new Map([
  ['HEALTH_PROTECTION', '#884ea3'],
  ['TERM_PROTECTION', '#d9abe4'],
  ['LONG_TERM_NONLIFE', '#b478d1'],
  ['SAVINGS', '#ca8ee8'],
])

// 시연 보험사의 색은 데이터베이스 ID와 조회 순서에 관계없이 이름으로 고정한다.
const INSURER_COLORS = new Map([
  ['미래가상생명', '#e77ecc'],
  ['한빛가상생명', '#c8718f'],
  ['새봄가상생명', '#ace88e'],
  ['온누리가상생명', '#8ebff8'],
  ['안전가상손해보험', '#62a7f5'],
  ['믿음가상손해보험', '#8dc76f'],
  ['다온가상손해보험', '#f7adc4'],
  ['푸른가상생명', '#488cd8'],
  ['하늘가상생명', '#6ea750'],
])
const INSURER_PALETTE = [...INSURER_COLORS.values()]

/** 계약 상태 4종의 순서와 색. 청약, 부활 등은 가까운 상태로 묶는다. */
export const STATUS_DEFS = [
  { key: 'ACTIVE', label: '정상', color: '#83de8c' },
  { key: 'UNPAID', label: '미납', color: '#e7c857' },
  { key: 'LAPSED', label: '실효', color: '#ec7bb3' },
  { key: 'END', label: '해지/종료', color: '#a4b4d2' },
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
  const insurers = home?.portfolio?.insurers ?? []
  const used = new Set(insurers.map((insurer) => INSURER_COLORS.get(insurer.name ?? '')))
  const available = INSURER_PALETTE.filter((color) => !used.has(color))
  const fallback = available.length > 0 ? available : INSURER_PALETTE
  let index = 0
  return insurers.map((insurer) => ({
    name: insurer.name ?? '-',
    value: insurer.contractCount ?? 0,
    premium: insurer.monthlyEquivalentFirstPremiumSum ?? 0,
    color: INSURER_COLORS.get(insurer.name ?? '') ?? fallback[index++ % fallback.length],
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
  return list.map((c) => ({
    name: c.label ?? c.code ?? '-',
    count: c.count ?? 0,
    pct: oneDecimal(((c.count ?? 0) / total) * 100),
    color: CATEGORY_COLORS.get(c.code ?? '') ?? '#94a3b8',
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
