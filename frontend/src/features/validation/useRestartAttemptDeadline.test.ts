import { act, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { RESTART_ATTEMPT_WAIT_MS, useRestartAttemptDeadline } from './useRestartAttemptDeadline'

afterEach(() => vi.useRealTimers())
it('expires only the accepted request and cancels old timers on a new request or unmount', () => {
  vi.useFakeTimers()
  const { result, rerender, unmount } = renderHook(
    ({ enabled, generation }) => useRestartAttemptDeadline(enabled, generation),
    { initialProps: { enabled: false, generation: 0 } },
  )
  act(() => vi.advanceTimersByTime(RESTART_ATTEMPT_WAIT_MS))
  expect(result.current).toBe(false)
  rerender({ enabled: true, generation: 1 })
  act(() => vi.advanceTimersByTime(RESTART_ATTEMPT_WAIT_MS - 1))
  expect(result.current).toBe(false)
  act(() => vi.advanceTimersByTime(1))
  expect(result.current).toBe(true)
  rerender({ enabled: true, generation: 2 })
  expect(result.current).toBe(false)
  act(() => vi.advanceTimersByTime(RESTART_ATTEMPT_WAIT_MS / 2))
  rerender({ enabled: true, generation: 3 })
  act(() => vi.advanceTimersByTime(RESTART_ATTEMPT_WAIT_MS / 2))
  expect(result.current).toBe(false)
  unmount()
  expect(vi.getTimerCount()).toBe(0)
})
