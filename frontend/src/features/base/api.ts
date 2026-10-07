import { useQuery } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
type Schema = components['schemas']
export type Organization = Schema['OrganizationResponse']
export type Insurer = Schema['InsurerResponse']
export type Product = Schema['ProductResponse']
export type Agent = Schema['AgentResponse']
export type CommissionItem = Schema['CommissionItemResponse']
export interface BaseCriteria {
  asOf?: string
  keyword?: string
  insurerId?: string
  organizationId?: string
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
export async function getAllBaseOptions<K extends 'organizations' | 'insurers'>(
  kind: K,
  criteria: BaseCriteria,
  signal?: AbortSignal,
) {
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
export function useOrganizationOptions(asOf: string, enabled = true) {
  return useQuery({
    queryKey: ['base', 'organizations', 'options', asOf],
    queryFn: ({ signal }) => getAllBaseOptions('organizations', { asOf }, signal),
    enabled: enabled && Boolean(asOf),
    meta: { errorToast: false },
  })
}
