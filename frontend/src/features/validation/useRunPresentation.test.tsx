import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useRunPresentation } from './useRunPresentation'

afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals() })
it('waits for line arrival before buffering, then resets all stages together for replay', () => {
  vi.useFakeTimers()
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  const hook = renderHook(({ generation }) => useRunPresentation('COMPLETED', 8, generation, true, false), { initialProps: { generation: 0 } })
  hook.rerender({ generation: 1 })
  expect(hook.result.current.completed).toBe(0)
  act(() => { vi.advanceTimersByTime(1) })
  expect(hook.result.current.buffering).toBe(true)
  act(() => { vi.advanceTimersByTime(960) })
  expect(hook.result.current.completed).toBe(1)
  expect(hook.result.current.buffering).toBe(false)
  act(() => { vi.advanceTimersByTime(999) })
  expect(hook.result.current.buffering).toBe(false)
  act(() => { vi.advanceTimersByTime(1) })
  expect(hook.result.current.buffering).toBe(true)
  act(() => { vi.advanceTimersByTime(960) })
  expect(hook.result.current.completed).toBe(2)
  hook.rerender({ generation: 2 })
  expect(hook.result.current.completed).toBe(0)
  expect(hook.result.current.state(2)).toBe('pending')
})
it('does not turn server-unfinished work into completed work on a timer', () => {
  vi.useFakeTimers()
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  const hook = renderHook(() => useRunPresentation('RUNNING', 0, 0, true, false))
  act(() => { vi.advanceTimersByTime(30000) })
  expect(hook.result.current.completed).toBe(0)
  expect(hook.result.current.buffering).toBe(true)
})
