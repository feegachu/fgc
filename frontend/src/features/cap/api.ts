import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { validMonth } from '../../stores/workspace'
import { STAGES, STATUSES } from './labels'

type Schemas = components['schemas']
export type CapItem = Schemas['CapCheckItemResponse']
export type CapBasis = Schemas['CapCheckBasisResponse']
export type CapSearch = Schemas['CapCheckSearchResponse']
export type CapStage = Schemas['CapStageSummaryResponse']
export type CapAgent = Schemas['CapAgentSummaryResponse']
export const FILTER_KEYS = {
  month: '',
  stage: '',
  status: '',
  insurerId: '',
  orgId: '',
  contractNo: '',
  page: '',
  size: '',
  capCheckId: '',
}
export interface CapCriteria {
  month: string
  stage: string
  status: string
  insurerId: string
  orgId: string
  contractNo: string
  page: number
  size: number
}
const positive = (value: string | null) => {
  const parsed = Number(value)
  return value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(parsed) ? parsed : 1
}
const referenceId = (value: string | null) =>
  value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? value : ''
const code = (labels: Record<string, string>, value: string | null) =>
  value && Object.hasOwn(labels, value) ? value : ''

/** URL > 공통 workspace 기준월. 현재 PC 날짜로 기본월을 만들지 않는다. */
export function criteriaFromParams(params: URLSearchParams, workspaceMonth: string | null): CapCriteria {
  const month = params.get('month')
  const size = Number(params.get('size'))
  return {
    month: validMonth(month) ? month : (workspaceMonth ?? ''),
    stage: code(STAGES, params.get('stage')),
    status: code(STATUSES, params.get('status')),
    insurerId: referenceId(params.get('insurerId')),
    orgId: referenceId(params.get('orgId')),
    contractNo: params.get('contractNo')?.trim() ?? '',
    page: positive(params.get('page')),
    size: [10, 20, 50].includes(size) ? size : 20,
  }
}
async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const { data } = await apiClient.request<T>(path, { signal })
  if (data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}
export const capListQuery = (criteria: CapCriteria) =>
  queryOptions({
    queryKey: ['cap-checks', 'list', criteria],
    queryFn: ({ signal }) => {
      const query = new URLSearchParams()
      Object.entries(criteria).forEach(([key, value]) => {
        if (value !== '') query.set(key, String(value))
      })
      return get<CapSearch>(`/api/v1/cap-checks?${query}`, signal)
    },
    enabled: validMonth(criteria.month),
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })
export const capDetailQuery = (id: string | number | null | undefined) => {
  const detailId = id == null || id === '' ? null : String(id)
  return queryOptions({
    queryKey: ['cap-checks', 'detail', detailId],
    queryFn: async ({ signal }) => {
      if (detailId === null) throw new Error('계산근거 ID가 없습니다.')
      const data = await get<CapBasis>(`/api/v1/cap-checks/${encodeURIComponent(detailId)}/details`, signal)
      if (!data.capCheck) throw new Error('응답 데이터를 확인할 수 없습니다.')
      return data
    },
    enabled: detailId !== null,
    meta: { errorToast: false },
  })
}
export const capInsurersQuery = () =>
  queryOptions({
    queryKey: ['cap-checks', 'insurers'],
    queryFn: ({ signal }) =>
      get<Schemas['PageResponseInsurerResponse']>('/api/v1/base/insurers?page=1&size=100', signal),
    meta: { errorToast: false },
  })
export const capOrganizationsQuery = (month: string) =>
  queryOptions({
    queryKey: ['cap-checks', 'organizations', month],
    queryFn: ({ signal }) =>
      get<Schemas['PageResponseOrganizationResponse']>(
        `/api/v1/base/organizations?asOf=${encodeURIComponent(`${month}-01`)}&page=1&size=100`,
        signal,
      ),
    enabled: validMonth(month),
    meta: { errorToast: false },
  })
