import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { sessionIdleExpired, useIdleLogout } from './useIdleLogout'

const logout = vi.fn()
vi.mock('./useLogout', () => ({ useLogout: () => ({ logout, loggingOut: false }) }))
const MINUTE = 60_000

describe('미사용 자동 로그아웃', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-18T10:00:00+09:00'))
    localStorage.clear()
    logout.mockClear()
    useAuthStore.setState({ accessToken: 'token', loggingOut: false })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('30분 동안 입력이 없으면 안내와 함께 로그아웃한다', () => {
    renderHook(() => useIdleLogout(true))
    act(() => void vi.advanceTimersByTime(29 * MINUTE))
    expect(logout).not.toHaveBeenCalled()
    act(() => void vi.advanceTimersByTime(MINUTE))
    expect(logout).toHaveBeenCalledWith('/login?reason=idle')
  })

  it('입력이 있으면 시간을 다시 센다', () => {
    renderHook(() => useIdleLogout(true))
    act(() => void vi.advanceTimersByTime(20 * MINUTE))
    act(() => void window.dispatchEvent(new Event('keydown')))
    act(() => void vi.advanceTimersByTime(20 * MINUTE))
    expect(logout).not.toHaveBeenCalled()
  })

  it('입력을 계속하면 토큰이 끝나기 전에 한 번만 연장한다', () => {
    const extend = vi.spyOn(apiClient, 'extendSession').mockResolvedValue()
    renderHook(() => useIdleLogout(true))
    for (let minute = 1; minute <= 26; minute++) {
      act(() => void vi.advanceTimersByTime(MINUTE))
      act(() => void window.dispatchEvent(new Event('pointerdown')))
    }
    expect(extend).toHaveBeenCalledTimes(1)
    expect(logout).not.toHaveBeenCalled()
  })

  it('로그인 전에는 동작하지 않는다', () => {
    renderHook(() => useIdleLogout(false))
    act(() => void vi.advanceTimersByTime(31 * MINUTE))
    expect(logout).not.toHaveBeenCalled()
    expect(localStorage.getItem('fgc.lastActivityAt')).toBeNull()
  })

  it('브라우저를 다시 열 때 마지막 사용 시각으로 판단한다', () => {
    expect(sessionIdleExpired()).toBe(false)
    localStorage.setItem('fgc.lastActivityAt', String(Date.now() - 29 * MINUTE))
    expect(sessionIdleExpired()).toBe(false)
    localStorage.setItem('fgc.lastActivityAt', String(Date.now() - 30 * MINUTE))
    expect(sessionIdleExpired()).toBe(true)
  })
})
