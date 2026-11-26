import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'

type Schemas = components['schemas']
export type BankLink = Schemas['BankLinkResponse']
export type BankReconciliation = Schemas['BankReconciliationResponse']
export type BankReconciliationItem = Schemas['BankReconciliationItem']

/** IF-API-95 은행 연결 상태. */
export const bankLinkQuery = () =>
  queryOptions({
    queryKey: ['bank', 'link'],
    queryFn: async ({ signal }) => (await apiClient.request<BankLink>('/api/v1/bank/link', { signal })).data,
    meta: { errorToast: false },
  })

/** IF-API-99 그 정산월에 마지막으로 돌린 대사 결과. 돌린 적이 없으면 null 이다. */
export const bankReconciliationQuery = (month: string) =>
  queryOptions({
    queryKey: ['bank', 'reconciliation', month],
    queryFn: async ({ signal }) =>
      (await apiClient.request<BankReconciliation | null>(`/api/v1/bank-reconciliations?month=${month}`, { signal }))
        .data,
    meta: { errorToast: false },
  })

/** IF-API-96 은행 동의 화면 주소. */
export const requestBankAuthorizeUrl = async () =>
  (
    await apiClient.request<Schemas['BankAuthorizeUrlResponse']>('/api/v1/bank/link/authorize-url', {
      method: 'POST',
    })
  ).data

/** IF-API-97 은행이 돌려준 값으로 계좌 연결을 마친다. */
export const completeBankLink = async (body: Schemas['BankLinkRequest']) =>
  (await apiClient.request<BankLink>('/api/v1/bank/link', { method: 'POST', body })).data

/** IF-API-98 그 정산월의 확정 지급과 은행 출금을 맞춘다. */
export const runBankReconciliation = async (settlementMonth: string) =>
  (
    await apiClient.request<BankReconciliation>('/api/v1/bank-reconciliations', {
      method: 'POST',
      body: { settlementMonth },
    })
  ).data

/** 은행 동의 화면은 FGC 밖이라 화면 전체를 옮긴다. */
export const goToBank = (url: string) => window.location.assign(url)
