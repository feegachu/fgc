import { useQuery } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
type Schema = components['schemas']
export type Organization = Schema['OrganizationResponse']
export type Insurer = Schema['InsurerResponse']
export type Product = Schema['ProductResponse']
export type Agent = Schema['AgentResponse']
export type CommissionItem = Schema['CommissionItemResponse']
/** 탭별 정렬 key. 서버의 허용 목록과 같고, 유형·상태·직급 같은 코드·표시 전용 컬럼은 순서 기준이 없어 제외한다. */
export const BASE_SORT_KEYS = {
  organization: ['organizationCode', 'organizationName', 'parentName', 'effectiveFrom', 'effectiveTo'],
  insurer: ['insurerCode', 'insurerName'],
  product: [
    'insurerProductCode',
    'productName',
    'standardProductCode',
    'offeringVersion',
    'salesStartDate',
    'basicDocumentDate',
  ],
  agent: [
    'agentCode',
    'agentName',
    'organizationName',
    'appointmentDate',
    'terminationDate',
    'latestRegistrationDate',
  ],
} as const
export interface BaseCriteria {
  asOf?: string
  keyword?: string
  insurerId?: string
  organizationId?: string
  includeTerminated?: boolean
  includeExpired?: boolean
  includeHistorical?: boolean
  /** 서버(각 목록의 SORT_KEYS)와 같은 정렬 key. 비어 있으면 서버 기본(코드 오름차순) */
  sort?: string
  page?: number
  size?: number
}
type Pages = {
  organizations: Schema['PageResponseOrganizationResponse']
  insurers: Schema['PageResponseInsurerResponse']
  products: Schema['PageResponseProductResponse']
  agents: Schema['PageResponseAgentResponse']
}
export function queryString(criteria: object) {
  const params = new URLSearchParams()
  Object.entries(criteria).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  return params.toString()
}
export async function getBasePage<K extends keyof Pages>(
  kind: K,
  criteria: BaseCriteria,
  signal?: AbortSignal,
): Promise<Pages[K]> {
  return (await apiClient.request<Pages[K]>(`/api/v1/base/${kind}?${queryString(criteria)}`, { signal })).data
}
function useBasePage<K extends keyof Pages>(kind: K, criteria: BaseCriteria, enabled = true) {
  return useQuery({
    queryKey: ['base', kind, criteria],
    queryFn: ({ signal }) => getBasePage(kind, criteria, signal),
    enabled,
    meta: { errorToast: false },
  })
}
export function useOrganizations(criteria: BaseCriteria, enabled = true) {
  return useBasePage('organizations', criteria, enabled && Boolean(criteria.asOf))
}
export function useInsurers(criteria: BaseCriteria = {}, enabled = true) {
  return useBasePage('insurers', criteria, enabled)
}
export function useProducts(criteria: BaseCriteria, enabled = true) {
  return useBasePage('products', criteria, enabled && Boolean(criteria.asOf && criteria.insurerId))
}
export function useAgents(criteria: BaseCriteria, enabled = true) {
  return useBasePage('agents', criteria, enabled && Boolean(criteria.asOf))
}
export function useCommissionItems(asOf: string, enabled = true) {
  return useQuery({
    queryKey: ['base', 'commission-items', asOf],
    queryFn: async ({ signal }) =>
      (await apiClient.request<CommissionItem[]>(`/api/v1/base/commission-items?${queryString({ asOf })}`, { signal }))
        .data,
    enabled: enabled && Boolean(asOf),
    meta: { errorToast: false },
  })
}
// Dropdowns must include every page; preserve activeYn so input screens can disable inactive options.
export async function getAllBaseOptions<K extends keyof Pages>(kind: K, criteria: BaseCriteria, signal?: AbortSignal) {
  const rows: NonNullable<Pages[K]['content']> = []
  let page = 1
  let totalPages: number
  do {
    signal?.throwIfAborted()
    const result = await getBasePage(kind, { ...criteria, page, size: 100 }, signal)
    rows.push(...(result.content ?? []))
    totalPages = result.totalPages ?? 1
    page++
  } while (page <= totalPages)
  return rows
}
export function useInsurerOptions(enabled = true) {
  return useQuery({
    queryKey: ['base', 'insurers', 'options'],
    queryFn: ({ signal }) => getAllBaseOptions('insurers', {}, signal),
    enabled,
    meta: { errorToast: false },
  })
}
export function useOrganizationOptions(asOf: string, enabled = true, includeHistorical = false) {
  return useQuery({
    queryKey: ['base', 'organizations', 'options', asOf, includeHistorical],
    queryFn: ({ signal }) =>
      getAllBaseOptions('organizations', { asOf, includeHistorical: includeHistorical || undefined }, signal),
    enabled: enabled && Boolean(asOf),
    meta: { errorToast: false },
  })
}
export function useProductOptions(
  asOf: string,
  insurerId: string,
  enabled = true,
  includeExpired = false,
  includeHistorical = false,
) {
  return useQuery({
    queryKey: ['base', 'products', 'options', asOf, insurerId, includeExpired, includeHistorical],
    queryFn: ({ signal }) =>
      getAllBaseOptions(
        'products',
        {
          asOf,
          insurerId,
          includeExpired: includeExpired || undefined,
          includeHistorical: includeHistorical || undefined,
        },
        signal,
      ),
    enabled: enabled && Boolean(asOf && insurerId),
    meta: { errorToast: false },
  })
}
export function useAgentOptions(
  asOf: string,
  organizationId = '',
  enabled = true,
  includeTerminated = false,
  includeHistorical = false,
) {
  return useQuery({
    queryKey: ['base', 'agents', 'options', asOf, organizationId, includeTerminated, includeHistorical],
    queryFn: ({ signal }) =>
      getAllBaseOptions(
        'agents',
        {
          asOf,
          organizationId,
          includeTerminated: includeTerminated || undefined,
          includeHistorical: includeHistorical || undefined,
        },
        signal,
      ),
    enabled: enabled && Boolean(asOf),
    // Historical list options may span many pages. Reuse them on navigation/focus;
    // input options still refresh immediately when the contract date changes.
    staleTime: includeTerminated || includeHistorical ? 5 * 60 * 1000 : 0,
    meta: { errorToast: false },
  })
}
