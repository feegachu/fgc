import { afterEach, describe, expect, it, vi } from 'vitest'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import { getAllBaseOptions, useProductOptions } from '../base/api'
import {
  contractSchedulesQuery,
  criteriaFromParams,
  listCsvPath,
  regenerateContractSchedules,
  updateContract,
  validDate,
} from './api'
import type { ContractUpdateRequest } from './api'
import { arbitrageStatusLabels, capStatusLabels, responseLabel } from './labels'

const envelope = <T>(data: T) => ({ data, error: null, requestId: 'contract-api-test' })
afterEach(() => vi.restoreAllMocks())

describe('contract URL and labels', () => {
  it('keeps a comma-separated cap status list only when every code is known', () => {
    const status = (value: string) => criteriaFromParams(new URLSearchParams({ capResultStatus: value })).capResultStatus
    expect(status('WARNING,VIOLATION,REVIEW_REQUIRED')).toBe('WARNING,VIOLATION,REVIEW_REQUIRED')
    expect(status('WARNING,FAKE')).toBe('')
  })

  it('preserves supported filters and rejects unsafe IDs, enums, dates, and pages', () => {
    expect(
      criteriaFromParams(
        new URLSearchParams({
          contractNo: ' C & 1 ',
          insurerId: '2',
          productOfferingId: '-1',
          agentId: '9007199254740992',
          orgId: '3',
          currentStatus: 'ACTIVE',
          capResultStatus: 'FAKE',
          contractDateFrom: '2026-02-30',
          contractDateTo: '2026-10-12',
          sort: 'password,asc',
          page: 'Infinity',
        }),
      ),
    ).toEqual({
      contractNo: 'C & 1',
      insurerId: '2',
      productOfferingId: '',
      agentId: '',
      orgId: '3',
      currentStatus: 'ACTIVE',
      capResultStatus: '',
      contractDateFrom: '',
      contractDateTo: '2026-10-12',
      sort: '',
      page: 1,
    })
    expect(validDate('2024-02-29')).toBe(true)
    expect(validDate('2026-02-29')).toBe(false)
  })

  it('#604 허용된 정렬만 목록·CSV 요청에 그대로 싣는다', () => {
    const criteria = criteriaFromParams(new URLSearchParams({ sort: 'premium,desc', contractNo: 'C-1' }))
    expect(criteria.sort).toBe('premium,desc')
    expect(new URL(listCsvPath(criteria), 'http://localhost').searchParams.get('sort')).toBe('premium,desc')
    expect(criteriaFromParams(new URLSearchParams({ sort: 'status,asc' })).sort).toBe('')
  })

  it('exports all matching rows while keeping all nine applied filters', () => {
    const criteria = criteriaFromParams(
      new URLSearchParams({
        contractNo: 'C & 1',
        insurerId: '2',
        productOfferingId: '7',
        agentId: '4',
        orgId: '3',
        currentStatus: 'ACTIVE',
        capResultStatus: 'WARNING',
        contractDateFrom: '2026-01-01',
        contractDateTo: '2026-10-12',
        page: '3',
      }),
    )
    const query = new URL(listCsvPath(criteria), 'http://localhost').searchParams
    expect(query.get('contractNo')).toBe('C & 1')
    expect(query.get('orgId')).toBe('3')
    expect(query.size).toBe(9)
    expect(query.has('page')).toBe(false)
    expect(query.has('size')).toBe(false)
  })

  it('prioritizes server labels and keeps CAP/arbitrage review meanings separate', () => {
    expect(responseLabel(capStatusLabels, 'REVIEW_REQUIRED', '서버 표시명')).toBe('서버 표시명')
    expect(responseLabel(capStatusLabels, 'REVIEW_REQUIRED')).toBe('검토필요')
    expect(responseLabel(arbitrageStatusLabels, 'REVIEW_REQUIRED')).toBe('자료부족')
    expect(responseLabel(capStatusLabels, 'FUTURE_STATUS')).toBe('FUTURE_STATUS')
  })
})

describe('contract requests', () => {
  it('[FGC-FUN-018] separates history/input product caches and keeps the history flag across every option page', async () => {
    const request = vi.spyOn(apiClient, 'request').mockImplementation(async (path) => {
      const params = new URL(path, 'http://localhost').searchParams
      if (params.get('includeExpired') === 'true') {
        return envelope({ content: [{ productOfferingId: params.get('page') === '1' ? 11 : 12 }], totalPages: 2 })
      }
      return envelope({ content: [{ productOfferingId: 11 }], totalPages: 1 })
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)
    const { result, unmount } = renderHook(
      () => ({
        history: useProductOptions('2026-10-13', '2', true, true),
        input: useProductOptions('2026-10-13', '2'),
      }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.history.isSuccess && result.current.input.isSuccess).toBe(true))
    expect(result.current.history.data).toEqual([{ productOfferingId: 11 }, { productOfferingId: 12 }])
    expect(result.current.input.data).toEqual([{ productOfferingId: 11 }])
    const historyPages = request.mock.calls
      .map(([path]) => new URL(path, 'http://localhost').searchParams)
      .filter((params) => params.get('includeExpired') === 'true')
      .map((params) => params.get('page'))
    expect(historyPages).toEqual(['1', '2'])
    unmount()
    client.clear()
  })

  it('keeps optimistic concurrency timestamps intact and rejects malformed success bodies', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope({ contractId: 21 }))
    const body: ContractUpdateRequest = {
      insurerId: 1,
      productOfferingId: 1,
      contractNo: 'C-1',
      contractDate: '2026-10-12',
      contractStatus: 'ACTIVE',
      agentId: 2,
      organizationId: 3,
      paymentCycleCode: 'MONTHLY',
      premiumPerCycleAmount: 100,
      firstPremiumAmount: 100,
      monthlyEquivalentFirstPremium: 100,
      paymentTermMonths: 120,
      expectedUpdatedAt: '2026-10-12T00:00:00.123456Z',
    }
    await updateContract('21', body)
    expect(request).toHaveBeenCalledWith('/api/v1/contracts/21', { method: 'PUT', body })
    request.mockResolvedValue(envelope({}))
    await expect(updateContract('21', body)).rejects.toThrow('수정된 계약을 확인할 수 없습니다.')
  })

  it('sends schedule regeneration as a POST with an encoded reason and isolates stage caches', async () => {
    const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope([101]))
    await regenerateContractSchedules('21', '담당자 수정 & 재생성')
    const [path, options] = request.mock.calls[0]
    expect(new URL(path, 'http://localhost').searchParams.get('reason')).toBe('담당자 수정 & 재생성')
    expect(options?.method).toBe('POST')
    expect(contractSchedulesQuery('21', 'GA_TO_FC').queryKey).not.toEqual(
      contractSchedulesQuery('21', 'INSURER_TO_GA').queryKey,
    )
  })

  it.each(['products', 'agents'] as const)('loads every %s page and retains inactive options', async (kind) => {
    const request = vi
      .spyOn(apiClient, 'request')
      .mockResolvedValueOnce(envelope({ content: [{ activeYn: true }], totalPages: 2 }))
      .mockResolvedValueOnce(envelope({ content: [{ activeYn: false }], totalPages: 2 }))
    const signal = new AbortController().signal
    expect(await getAllBaseOptions(kind, { asOf: '2026-10-12', insurerId: '1' }, signal)).toEqual([
      { activeYn: true },
      { activeYn: false },
    ])
    expect(request).toHaveBeenCalledTimes(2)
    expect(new URL(request.mock.calls[1][0], 'http://localhost').searchParams.get('page')).toBe('2')
    expect(request.mock.calls[1][1]?.signal).toBe(signal)
  })

  it('cancels remaining option pages after the form changes date or insurer', async () => {
    const controller = new AbortController()
    const request = vi.spyOn(apiClient, 'request').mockImplementationOnce(async () => {
      controller.abort()
      return envelope({ content: [], totalPages: 2 })
    })
    await expect(getAllBaseOptions('products', {}, controller.signal)).rejects.toHaveProperty('name', 'AbortError')
    expect(request).toHaveBeenCalledTimes(1)
  })
})
