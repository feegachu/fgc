import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { canAnimate, useCountUp, useDrawn } from './motion'

function stubMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce') }))
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('모션 유틸', () => {
  it('matchMedia 가 없거나 동작 줄이기가 켜져 있으면 모션을 쓰지 않고 최종값을 바로 준다', () => {
    expect(canAnimate()).toBe(false)
    expect(renderHook(() => useCountUp(1234)).result.current).toBe(1234)
    expect(renderHook(() => useDrawn()).result.current).toBe(true)
    stubMotion(true)
    expect(canAnimate()).toBe(false)
    expect(renderHook(() => useCountUp(77)).result.current).toBe(77)
  })

  describe('모션을 쓸 수 있을 때', () => {
    beforeEach(() => {
      stubMotion(false)
      vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] })
    })

    it('0 에서 시작해 목표값까지 올라가고 끝에는 정확히 목표값이다', () => {
      const { result } = renderHook(() => useCountUp(100, 500))
      expect(result.current).toBe(0)
      act(() => {
        vi.advanceTimersByTime(250)
      })
      expect(result.current).toBeGreaterThan(0)
      expect(result.current).toBeLessThan(100)
      act(() => {
        vi.advanceTimersByTime(600)
      })
      expect(result.current).toBe(100)
    })

    it('값이 바뀌면 현재 값에서 이어서 새 목표로 간다', () => {
      const { result, rerender } = renderHook(({ target }) => useCountUp(target, 400), { initialProps: { target: 10 } })
      act(() => {
        vi.advanceTimersByTime(500)
      })
      expect(result.current).toBe(10)
      rerender({ target: 50 })
      act(() => {
        vi.advanceTimersByTime(500)
      })
      expect(result.current).toBe(50)
    })

    it('useDrawn 은 첫 프레임 뒤에 true 가 된다', () => {
      const { result } = renderHook(() => useDrawn())
      expect(result.current).toBe(false)
      act(() => {
        vi.advanceTimersByTime(50)
      })
      expect(result.current).toBe(true)
    })
  })
})

