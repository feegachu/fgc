import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../../lib/api/client'

/** FUN-079 상품 비교 API 응답. 서버가 순위·금액·근거를 확정하고 화면은 표시만 한다. */
export interface CompareCategory {
  code: string
  label: string
  insurerCount: number
}
export interface CompareEvidence {
  label: string
  rule: string
  value: number | null
  source: string
  policyVersion: string | null
  asOf: string
}
export interface CompareItem {
  rank: number
  offeringId: number
  insurer: string
  product: string
  expectedReceipt: number
  cappedReceipt: number
  carryover: number
  clawbackExposure: number
  conservativeNet: number
  clawbackRatePct: number | null
  capImpactPct: number | null
  associationGrade: string | null
  evidence: CompareEvidence[]
}
export interface CompareExcluded {
  offeringId: number
  insurer: string
  product: string
  reasonCodes: string[]
}
export interface CompareRanking {
  rankResultId: string | null
  /** READY | RULE_NOT_CONFIGURED(승인된 계산 기준 미설정) | INSUFFICIENT_DATA(자료 부족) */
  status: 'READY' | 'RULE_NOT_CONFIGURED' | 'INSUFFICIENT_DATA'
  categoryCode: string
  categoryLabel: string
  asOf: string
  ruleVersion: string | null
  policyVersion: string | null
  basePremium: number | null
  clawbackMonth: number
  warnings: string[]
  capSummary: { usagePct: number | null; status: string | null; contractCount: number }
  items: CompareItem[]
  excludedItems: CompareExcluded[]
}
export interface CompareExplain {
  rankResultId: string
  summary: string | null
  summarySource: 'RULE' | 'LLM'
  summaryError: string | null
}

const BASE = '/api/v1/ai/compare-chat'

export const compareCategoriesQuery = () =>
  queryOptions({
    queryKey: ['compare-chat', 'categories'],
    queryFn: async ({ signal }) => (await apiClient.request<CompareCategory[]>(`${BASE}/categories`, { signal })).data ?? [],
    staleTime: 60_000,
    meta: { errorToast: false },
  })

export async function rankCompare(categoryCode: string) {
  const { data } = await apiClient.request<CompareRanking>(`${BASE}/rank`, { method: 'POST', body: { categoryCode } })
  if (!data) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}

/** 요약은 순위와 따로 받는다. 실패해도 순위 화면은 그대로 두고 요약 말풍선에만 실패를 표시한다. */
export const compareExplainQuery = (rankResultId: string | null) =>
  queryOptions({
    queryKey: ['compare-chat', 'explain', rankResultId],
    queryFn: async ({ signal }) =>
      (await apiClient.request<CompareExplain>(`${BASE}/explain`, { method: 'POST', body: { rankResultId }, signal })).data,
    enabled: Boolean(rankResultId),
    retry: false,
    staleTime: Infinity,
    meta: { errorToast: false },
  })
