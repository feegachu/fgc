import { useQuery } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { queryString } from '../base/api'
import { normalizePolicyDetail } from './writeApi'
import type { PolicyDetailResponse } from './writeApi'
export type Policy = components['schemas']['PolicyVersionResponse']
export type PolicyDetail = components['schemas']['PolicyDetailResponse']
export function usePolicies(asOf: string) {
  return useQuery({
    queryKey: ['policies', { asOf }],
    queryFn: async ({ signal }) =>
      (await apiClient.request<Policy[]>(`/api/v1/policies?${queryString({ asOf })}`, { signal })).data,
    enabled: Boolean(asOf),
    meta: { errorToast: false },
  })
}
export function usePolicyDetail(id: number | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['policies', 'detail', id],
    queryFn: async ({ signal }) =>
      normalizePolicyDetail((await apiClient.request<PolicyDetailResponse>(`/api/v1/policies/${id}`, { signal })).data),
    enabled: enabled && id !== undefined,
    meta: { errorToast: false },
  })
}
