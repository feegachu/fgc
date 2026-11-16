import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { ContractStatusContext } from './ContractStatusContext'

afterEach(() => vi.restoreAllMocks())
const history = [{ eventId: 1, eventSeq: 1, newStatus: 'ACTIVE', newStatusLabel: '정상', reason: '신규 계약' }]
function setup() {
  const request = vi.spyOn(apiClient, 'request')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  request.mockResolvedValue({ data: history, error: null, requestId: 'status-test' })
  return { request, client, show: () => render(<QueryClientProvider client={client}><ContractStatusContext contractId={3} /></QueryClientProvider>) }
}

it('최초 조회 실패 후 재시도할 수 있다', async () => {
  const { request, show } = setup()
  request.mockRejectedValueOnce(new Error('이력 조회 실패'))
  show()
  fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
  expect(await screen.findByText('신규 계약')).toBeVisible()
})

it('재조회 실패 시 기존 이력을 보존하고 재시도 후 복구하며 403이면 숨긴다', async () => {
  const { request, client, show } = setup()
  show()
  expect(await screen.findByText('신규 계약')).toBeVisible()
  request.mockRejectedValueOnce(new Error('최신 조회 실패'))
  await client.refetchQueries({ queryKey: ['contracts', 'detail', '3', 'status-events'] })
  expect(await screen.findByText(/이전 조회 결과를 표시합니다/)).toBeVisible()
  expect(screen.getByText('신규 계약')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await waitFor(() => expect(screen.queryByText(/이전 조회 결과를 표시합니다/)).not.toBeInTheDocument())
  request.mockRejectedValue(new ApiError({ code: 'FGC-AUTH-003', message: '권한 없음' }, 'status-denied', 403))
  await client.refetchQueries({ queryKey: ['contracts', 'detail', '3', 'status-events'] })
  expect(await screen.findByText(/권한 없음/)).toBeVisible()
  expect(screen.queryByText('신규 계약')).not.toBeInTheDocument()
})
