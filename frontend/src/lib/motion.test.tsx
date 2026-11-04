import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { canAnimate, useDrawn } from './motion'

function stubMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce') }))
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('모션 유틸', () => {
  it('matchMedia 가 없거나 동작 줄이기가 켜져 있으면 모션을 쓰지 않는다', () => {
    expect(canAnimate()).toBe(false)
    expect(renderHook(() => useDrawn()).result.current).toBe(true)
    stubMotion(true)
    expect(canAnimate()).toBe(false)
  })

  describe('모션을 쓸 수 있을 때', () => {
    beforeEach(() => {
      stubMotion(false)
      vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] })
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

