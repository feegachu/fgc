import { act, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useGuideStep } from './useGuideSequence'

afterEach(() => vi.useRealTimers())

it('keeps Next blocked after a failed business result and advances only after confirmed completion', async () => {
  vi.useFakeTimers()
  const target = document.createElement('button')
  target.dataset.guideCompleted = 'false'
  const next = vi.fn()
  const step = { requireAction: true, advanceOnEvent: 'saved' }
  const { result } = renderHook(() => useGuideStep(step, target, next, true))
  expect(result.current).toBe(true)
  act(() => { target.dispatchEvent(new Event('saved')); vi.advanceTimersByTime(400) })
  expect(next).not.toHaveBeenCalled()
  await act(async () => { target.dataset.guideCompleted = 'true' })
  expect(result.current).toBe(false)
  act(() => { target.dispatchEvent(new Event('saved')); vi.advanceTimersByTime(400) })
  expect(next).toHaveBeenCalledTimes(1)
  await act(async () => { target.dataset.guideCompleted = 'false' })
  expect(result.current).toBe(true)
})
