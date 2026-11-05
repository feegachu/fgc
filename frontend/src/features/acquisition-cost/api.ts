import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { formatSort, parseSort } from '../../lib/tableSort'
import { usePolicyRequest } from '../policies/usePolicyRequest'
type Schemas = components['schemas']
export type AcquisitionCheck = Schemas['AcquisitionCostCheckResponse']
export type AcquisitionItem = Schemas['AcquisitionCostItem']
export type AcquisitionRequest = Required<Schemas['AcquisitionCostCheckRequest']>
export const ACQUISITION_SORT_KEYS = ['checkedAt', 'contractNo'] as const

export interface Criteria {
  resultStatus: string
  paymentStage: string
  insurerId: string
  contractNo: string
  /** 비어 있으면 서버 기본(최근 검증 순) */
  sort: string
  page: number
}
export function criteriaFromParams(params: URLSearchParams): Criteria {
  const raw = Number(params.get('page') ?? 1)
  return {
    resultStatus: params.get('resultStatus') ?? '',
    paymentStage: params.get('paymentStage') ?? '',
    insurerId: params.get('insurerId') ?? '',
    contractNo: params.get('contractNo')?.trim() ?? '',
    sort: formatSort(parseSort(params.get('sort'), ACQUISITION_SORT_KEYS)),
    page: Number.isSafeInteger(raw) && raw > 0 ? raw : 1,
  }
}
async function search(criteria: Record<string, string | number>, signal?: AbortSignal) {
  const query = new URLSearchParams()
  Object.entries(criteria).forEach(([key, value]) => {
    if (value !== '') query.set(key, String(value))
  })
  query.set('size', '20')
  const { data } = await apiClient.request<Schemas['PageResponseAcquisitionCostCheckResponse']>(
    `/api/v1/acquisition-cost-checks?${query}`,
    { signal },
  )
  if (!Array.isArray(data?.content)) throw new Error('검증 결과 응답을 확인할 수 없습니다. 다시 조회해 주세요.')
  return data
}
export const acquisitionListQuery = (criteria: Criteria) =>
  queryOptions({
    queryKey: ['acquisition-cost', 'list', criteria],
    queryFn: ({ signal }) => search({ ...criteria }, signal),
    meta: { errorToast: false },
  })
/** 계약 상세의 지급단계별 최근 결과. 조회만으로 검증을 만들지 않는다. */
export const contractAcquisitionQuery = (contractId: string, paymentStage: AcquisitionRequest['paymentStage']) =>
  queryOptions({
    queryKey: ['acquisition-cost', 'contract', contractId, paymentStage],
    queryFn: ({ signal }) => search({ contractId, paymentStage }, signal),
    meta: { errorToast: false },
  })
export function useRequestAcquisitionCheck() {
  const client = useQueryClient()
  const keyFor = usePolicyRequest()
  // 응답을 못 받은 재시도는 같은 키로 보내 결과가 두 번 쌓이지 않게 한다. 성공한 뒤의 요청은 새 검증이다.
  const completed = useRef(0)
  return useMutation({
    mutationKey: ['acquisition-cost', 'request'],
    mutationFn: async (body: AcquisitionRequest) => {
      const { data } = await apiClient.request<AcquisitionCheck>('/api/v1/acquisition-cost-checks', {
        method: 'POST',
        body,
        idempotencyKey: keyFor({ ...body, completed: completed.current }),
      })
      if (!data?.acquisitionCostCheckId) throw new Error('검증 결과 응답을 확인할 수 없습니다. 같은 요청으로 다시 시도해 주세요.')
      return data
    },
    meta: { errorToast: false },
    onSuccess: async () => {
      completed.current += 1
      await client.invalidateQueries({ queryKey: ['acquisition-cost'] })
    },
  })
}
