import { queryOptions } from '@tanstack/react-query'
import { apiClient, ApiError } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { errorText } from '../../lib/format'

type Schemas = components['schemas']
export type Payment = Schemas['CommissionPaymentResponse']
export type Precheck = Schemas['TransactionPrecheckResponse']
export type Blocker = Schemas['Blocker']
export type CapPreview = Schemas['CapPreviewItem']
export type Contract = Schemas['ContractView']
export type Agent = Schemas['AgentResponse']
export type CommissionItem = Schemas['CommissionItemResponse']
export type AttributionRequest = Schemas['CommissionPaymentAttributionRequest']
export type PaymentRequest = Schemas['CommissionPaymentCreateRequest']

export function formError(error: unknown, fallback: string) {
  if (error instanceof ApiError && (error.status >= 500 || error.code === 'FGC-COMMON-500')) {
    return `요청을 처리하지 못했습니다.${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
  }
  return errorText(error instanceof Error ? error : undefined, fallback)
}

// Springdoc describes nullable Java fields as optional. Preserve the legacy JSON nulls
// for non-contract newcomer support and optional draft fields without changing generated types.
type NullableOptional<T> = { [K in keyof T]: undefined extends T[K] ? T[K] | null : T[K] }
export type AttributionPayload = NullableOptional<AttributionRequest>
export type PaymentPayload = Omit<NullableOptional<PaymentRequest>, 'attributions'> & {
  attributions: AttributionPayload[]
}

async function data<T>(path: string, options: Parameters<typeof apiClient.request>[1] = {}) {
  const response = await apiClient.request<T>(path, options)
  if (response.data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return response.data
}
const path = (id: string, suffix = '') => `/api/v1/transactions/${encodeURIComponent(id)}${suffix}`
export const draftQuery = (id: string | null) =>
  queryOptions({
    meta: { errorToast: false },
    queryKey: ['transactions', 'detail', id],
    queryFn: ({ signal }) => data<Payment>(path(id!), { signal }),
    enabled: Boolean(id),
  })
export const agentsQuery = (month: string) =>
  queryOptions({
    meta: { errorToast: false },
    queryKey: ['transactions', 'agents', month],
    queryFn: async ({ signal }) => {
      const result = await data<Schemas['PageResponseAgentResponse']>(
        `/api/v1/base/agents?asOf=${encodeURIComponent(month + '-01')}&page=1&size=100`,
        { signal },
      )
      return (result.content ?? []).filter((agent) => agent.activeYn !== false && agent.agentStatus === 'ACTIVE')
    },
    enabled: Boolean(month),
  })
export const contractsQuery = (agentId = '') =>
  queryOptions({
    meta: { errorToast: false },
    queryKey: ['transactions', 'contracts', agentId],
    queryFn: async ({ signal }) => {
      const result = await data<Schemas['PageResponseContractView']>(
        `/api/v1/contracts?page=1&size=100${agentId ? `&agentId=${encodeURIComponent(agentId)}` : ''}`,
        { signal },
      )
      return result.content ?? []
    },
  })
export const contractQuery = (id: number | undefined) =>
  queryOptions({
    meta: { errorToast: false },
    queryKey: ['transactions', 'contract', id],
    queryFn: ({ signal }) => data<Schemas['ContractDetailResponse']>('/api/v1/contracts/' + id, { signal }),
    enabled: id != null && id > 0,
  })
export const itemsQuery = (month: string) =>
  queryOptions({
    meta: { errorToast: false },
    queryKey: ['transactions', 'items', month],
    queryFn: ({ signal }) =>
      data<CommissionItem[]>(`/api/v1/base/commission-items?asOf=${encodeURIComponent(month + '-01')}`, { signal }),
    enabled: Boolean(month),
  })
export const saveDraft = (id: string | null, body: PaymentPayload) =>
  data<Payment>(id ? path(id) : '/api/v1/transactions', { method: id ? 'PUT' : 'POST', body })
export const precheckPayment = (id: string) => data<Precheck>(path(id, '/precheck'), { method: 'POST' })
export const confirmPayment = (id: string) =>
  data<Payment>(path(id, '/confirm'), { method: 'POST', idempotencyKey: `TRAN-CONFIRM-${id}` })
