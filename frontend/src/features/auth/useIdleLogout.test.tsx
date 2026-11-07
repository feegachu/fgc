import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { sessionClosed, sessionIdleExpired, useIdleLogout } from './useIdleLogout'

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

  it('30분이 지난 뒤 입력이 판정보다 먼저 와도 로그아웃한다', () => {
    renderHook(() => useIdleLogout(true))
    vi.setSystemTime(Date.now() + 31 * MINUTE) // 절전 중에는 타이머가 돌지 않는다
    act(() => void window.dispatchEvent(new Event('pointermove')))
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

  it('열린 화면은 30초마다 표시를 남기고, 모두 닫고 3분이 지나면 닫힌 것으로 본다', () => {
    expect(sessionClosed()).toBe(false)
    const { unmount } = renderHook(() => useIdleLogout(true))
    expect(localStorage.getItem('fgc.openAt')).toBe(String(Date.now()))
    act(() => void vi.advanceTimersByTime(30_000))
    expect(localStorage.getItem('fgc.openAt')).toBe(String(Date.now()))
    unmount()
    act(() => void vi.advanceTimersByTime(2 * MINUTE + 59_000))
    expect(sessionClosed()).toBe(false)
    act(() => void vi.advanceTimersByTime(1_000))
    expect(sessionClosed()).toBe(true)
  })

  it('연장이 네트워크 오류로 실패하면 1분 뒤 입력에서 다시 한다', async () => {
    const extend = vi.spyOn(apiClient, 'extendSession').mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue()
    renderHook(() => useIdleLogout(true))
    act(() => void vi.advanceTimersByTime(25 * MINUTE))
    act(() => void window.dispatchEvent(new Event('keydown')))
    await act(async () => {})
    expect(extend).toHaveBeenCalledTimes(1)
    act(() => void vi.advanceTimersByTime(30_000))
    act(() => void window.dispatchEvent(new Event('keydown')))
    expect(extend).toHaveBeenCalledTimes(1)
    act(() => void vi.advanceTimersByTime(30_000))
    act(() => void window.dispatchEvent(new Event('keydown')))
    expect(extend).toHaveBeenCalledTimes(2)
  })

  it('브라우저를 다시 열 때 마지막 사용 시각으로 판단한다', () => {
    expect(sessionIdleExpired()).toBe(false)
    localStorage.setItem('fgc.lastActivityAt', String(Date.now() - 29 * MINUTE))
    expect(sessionIdleExpired()).toBe(false)
    localStorage.setItem('fgc.lastActivityAt', String(Date.now() - 30 * MINUTE))
    expect(sessionIdleExpired()).toBe(true)
  })
})
