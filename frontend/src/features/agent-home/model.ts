import type { components } from '../../lib/api/schema'

type Schema = components['schemas']
export type AgentContract = Schema['ContractView']
export type Insurer = Schema['InsurerResponse']
export type Product = Schema['ProductResponse']

export type Period = 6 | 12 | 'all'
export type InsurerKind = 'all' | 'LIFE' | 'NON_LIFE'

/** 상품군 코드 → 목업의 보장 유형 이름. */
export const CATEGORY_LABEL: Record<string, string> = {
  HEALTH_PROTECTION: '건강보장',
  TERM_PROTECTION: '정기보장',
  LONG_TERM_NONLIFE: '장기손해',
  SAVINGS: '연금·저축',
}
export const CATEGORIES = ['건강보장', '정기보장', '장기손해', '연금·저축'] as const
export const CATEGORY_COLORS = ['#9a82b5', '#a493b8', '#86a89c', '#c9ae8f']
export const INSURER_COLORS = ['#7f9fba', '#84ada3', '#caae7e', '#c492a0', '#a0b388', '#c99d88', '#8bb0bc', '#bbb589']

/** 목업의 상태 4종 순서와 색. 청약·부활 등은 가까운 상태로 묶는다. */
export const STATUS_DEFS = [
  { key: 'ACTIVE', label: '정상', color: '#78a98a' },
  { key: 'UNPAID', label: '미납', color: '#d1ad74' },
  { key: 'LAPSED', label: '실효', color: '#c98a8a' },
  { key: 'END', label: '해지·종료', color: '#b8c0cc' },
] as const
const STATUS_GROUP: Record<string, (typeof STATUS_DEFS)[number]['key']> = {
  APPLIED: 'ACTIVE',
  ACTIVE: 'ACTIVE',
  REVIVED: 'ACTIVE',
  UNPAID: 'UNPAID',
  LAPSED: 'LAPSED',
  CANCELLED: 'END',
  TERMINATED: 'END',
  MATURED: 'END',
}

export interface Row {
  id: string
  contractId: number
  month: string
  date: string
  insurerName: string
  insurerKind: 'LIFE' | 'NON_LIFE' | 'UNKNOWN'
  product: string
  category: string
  status: (typeof STATUS_DEFS)[number]['key']
  premium: number
  /** 1,200% 한도 경고·위반 */
  near: boolean
  /** 검증 자료가 부족해 확인이 필요한 계약 */
  review: boolean
  capLabel: string
}

export function buildRows(contracts: AgentContract[], insurers: Insurer[], products: Product[]): Row[] {
  const kind = new Map(insurers.map((i) => [i.insurerName, i.insurerType]))
  const group = new Map(products.map((p) => [p.productName, p.productGroupCode]))
  return contracts.map((c) => ({
    id: c.contractNo ?? String(c.contractId),
    contractId: c.contractId ?? 0,
    month: c.contractDate?.slice(0, 7) ?? '',
    date: c.contractDate ?? '',
    insurerName: c.insurerName ?? '-',
    insurerKind: kind.get(c.insurerName) ?? 'UNKNOWN',
    product: c.productName ?? '-',
    category: CATEGORY_LABEL[group.get(c.productName) ?? ''] ?? '기타',
    status: STATUS_GROUP[c.contractStatus ?? ''] ?? 'END',
    premium: c.monthlyEquivalentFirstPremium ?? 0,
    near: c.capResultStatus === 'WARNING' || c.capResultStatus === 'VIOLATION',
    review: c.capResultStatus === 'REVIEW_REQUIRED',
    capLabel: c.capResultStatusLabel ?? '',
  }))
}

export function shiftMonth(month: string, delta: number) {
  const [year, mon] = month.split('-').map(Number)
  const index = year * 12 + (mon - 1) + delta
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

/** 기준월 포함 최근 n개월. 전체는 계약이 있는 가장 이른 달부터 기준월까지. */
export function periodMonths(rows: Row[], month: string, period: Period) {
  let count = period === 'all' ? 0 : period
  if (period === 'all') {
    const first = rows.map((r) => r.month).filter((m) => m && m <= month).sort()[0] ?? month
    const [fy, fm] = first.split('-').map(Number)
    const [ty, tm] = month.split('-').map(Number)
    count = Math.max(1, (ty - fy) * 12 + (tm - fm) + 1)
  }
  return Array.from({ length: count }, (_, i) => shiftMonth(month, i - count + 1))
}

export interface MonthStats {
  count: number
  premium: number
  near: number
  review: number
}
export function monthStats(rows: Row[], month: string): MonthStats {
  const list = rows.filter((r) => r.month === month)
  return {
    count: list.length,
    premium: list.reduce((sum, r) => sum + r.premium, 0),
    near: list.filter((r) => r.near).length,
    review: list.filter((r) => r.review).length,
  }
}

/** 비율을 소수점 첫째 자리까지 절사한다(화면정의서 공통규칙 3). 먼저 1e-6에서 맞춰 28.999… 같은 곱셈 오차를 없앤다. */
export const oneDecimal = (value: number) => Math.trunc(Math.round(value * 1e6) / 1e5) / 10

/** 전월 대비 증감률(%, 소수점 한 자리). 전월 값이 0이면 비교할 수 없어 0으로 둔다. */
export const deltaPercent = (now: number, before: number) => (before ? oneDecimal(((now - before) / before) * 100) : 0)

export interface Aggregate {
  base: Row[]
  focus: Row[]
  months: string[]
}
export function aggregate(rows: Row[], month: string, period: Period, kind: InsurerKind, insurer: string | null): Aggregate {
  const months = periodMonths(rows, month, period)
  const set = new Set(months)
  const base = rows.filter((r) => set.has(r.month) && (kind === 'all' || r.insurerKind === kind))
  return { base, focus: insurer ? base.filter((r) => r.insurerName === insurer) : base, months }
}

export interface Slice {
  name: string
  value: number
  premium: number
  color: string
}
export function insurerSlices(base: Row[], insurers: Insurer[], kind: InsurerKind): Slice[] {
  const pool = insurers.filter((i) => kind === 'all' || i.insurerType === kind)
  const names = pool.length ? pool.map((i) => i.insurerName ?? '') : [...new Set(base.map((r) => r.insurerName))]
  return names.map((name, index) => {
    const list = base.filter((r) => r.insurerName === name)
    return { name, value: list.length, premium: list.reduce((s, r) => s + r.premium, 0), color: INSURER_COLORS[index % INSURER_COLORS.length] }
  })
}

export function trendSeries(focus: Row[], months: string[]) {
  return months.map((month) => {
    const list = focus.filter((r) => r.month === month)
    return { month, count: list.length, premium: list.reduce((s, r) => s + r.premium, 0) }
  })
}

export function categoryCounts(focus: Row[]) {
  const total = focus.length || 1
  return CATEGORIES.map((name, i) => {
    const count = focus.filter((r) => r.category === name).length
    return { name, count, pct: oneDecimal((count / total) * 100), color: CATEGORY_COLORS[i] }
  })
}

export function statusCounts(focus: Row[]) {
  const total = focus.length || 1
  return STATUS_DEFS.map((def) => {
    const count = focus.filter((r) => r.status === def.key).length
    return { ...def, count, pct: oneDecimal((count / total) * 100) }
  })
}

/** 확인 필요 계약: 한도 경고·위반이 먼저, 그다음 검토 필요. */
export const needRows = (rows: Row[], month: string) =>
  rows
    .filter((r) => r.month === month && (r.near || r.review))
    .sort((a, b) => Number(b.near) - Number(a.near) || b.date.localeCompare(a.date))
