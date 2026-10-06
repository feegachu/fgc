import { expect, it, vi } from 'vitest'
import { queryClient, shouldRetry } from './queryClient'
import { ApiError } from '../lib/api/errors'
import { subscribeApiErrors } from '../lib/api/errorNotifications'

it('4xx와 취소는 재시도하지 않고 5xx·네트워크 오류는 두 번까지 재시도한다', () => {
  for (const status of [400, 401, 403, 404, 409, 429]) {
    expect(shouldRetry(0, new ApiError(null, null, status))).toBe(false)
  }
  expect(shouldRetry(0, new ApiError(null, null, 500))).toBe(true)
  expect(shouldRetry(1, new TypeError('Failed to fetch'))).toBe(true)
  expect(shouldRetry(2, new TypeError('Failed to fetch'))).toBe(false)
  expect(shouldRetry(0, new DOMException('Aborted', 'AbortError'))).toBe(false)
  expect(queryClient.getDefaultOptions().mutations?.retry).toBe(false)
})
it('Query 오류는 Toast 연결점으로 보내고 meta로 끌 수 있다', async () => {
  const listener = vi.fn()
  const unsubscribe = subscribeApiErrors(listener)
  const error = new ApiError({ message: '실패', code: 'FGC-CONT-001' }, 'request', 400)
  await expect(queryClient.fetchQuery({ queryKey: ['error'], queryFn: () => Promise.reject(error) })).rejects.toBe(error)
  expect(listener).toHaveBeenCalledOnce()
  await expect(queryClient.fetchQuery({ queryKey: ['silent-error'], queryFn: () => Promise.reject(error), meta: { errorToast: false } })).rejects.toBe(error)
  expect(listener).toHaveBeenCalledOnce()
  unsubscribe()
  queryClient.clear()
})
