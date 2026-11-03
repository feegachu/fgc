import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'

type Schema = components['schemas']
export type AgentHome = Schema['AgentHomeResponse']
export type PriorityContract = Schema['AgentHomePriorityContract']
export type ContractCheckRequest = Schema['ContractCheckRequest']
export type ContractCheckResponse = Schema['ContractCheckResponse']

export interface HomeParams {
  asOf: string
  period: '6' | '12' | 'ALL'
  insurerType: 'ALL' | 'LIFE' | 'NON_LIFE'
  insurerId?: number | null
}

/** IF-API-74. 서버가 로그인한 설계사의 자료만 같은 기준일·모집단으로 집계한다(FUN-085·086). 화면은 설계사 ID를 보내지 않는다. */
export const agentHomeQuery = ({ asOf, period, insurerType, insurerId }: HomeParams) =>
  queryOptions({
    queryKey: ['agent-home', 'home', asOf, period, insurerType, insurerId ?? null],
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({ asOf, period, insurerType })
      if (insurerId != null) query.set('insurerId', String(insurerId))
      const { data } = await apiClient.request<AgentHome>(`/api/v1/agent/me/home?${query.toString()}`, { signal })
      if (!data) throw new Error('응답 데이터를 확인할 수 없습니다.')
      return data
    },
    // 필터를 바꾸는 동안 이전 그래프를 유지해 화면이 '불러오는 중'으로 깜빡이지 않게 한다.
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })

/** 해당 월 1일~기준일 계약 중 지정한 한도 판정 상태의 건수(다른 지표와 같은 기준일). 목록 API의 totalElements 만 읽는다. */
export const agentCapCountQuery = (month: string, asOf: string, capResultStatus: string) =>
  queryOptions({
    queryKey: ['agent-home', 'cap-count', month, asOf, capResultStatus],
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({
        capResultStatus,
        contractDateFrom: `${month}-01`,
        contractDateTo: asOf,
        page: '1',
        size: '1',
      })
      const { data } = await apiClient.request<{ totalElements?: number }>(`/api/v1/contracts?${query.toString()}`, { signal })
      if (data?.totalElements == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
      return data.totalElements
    },
    meta: { errorToast: false },
  })

/** IF-API-74A. 멱등키는 확인 폼을 열 때 한 번 만들고, 같은 제출의 재시도에 그대로 쓴다. */
export async function recordContractCheck(body: ContractCheckRequest, idempotencyKey: string) {
  const { data } = await apiClient.request<ContractCheckResponse>('/api/v1/agent/me/contract-checks', {
    method: 'POST',
    body,
    idempotencyKey,
  })
  if (!data) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}
