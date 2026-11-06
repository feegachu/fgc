import { useEffect, useEffectEvent } from 'react'
import { apiClient } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useLogout } from './useLogout'

const IDLE_LIMIT_MS = 30 * 60_000
// Access(30분)가 끝나기 전에 회전해 입력만 하고 저장 요청이 없는 사용자도 끊기지 않게 한다. 서버 미사용 한도는 60분이다.
const REFRESH_AFTER_MS = 25 * 60_000
const CHECK_EVERY_MS = 30_000
const ACTIVITY_KEY = 'fgc.lastActivityAt' // clearSessionData가 로그아웃과 로그인 때 함께 지운다
const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart'] as const

function readActivity() {
  try {
    return Number(localStorage.getItem(ACTIVITY_KEY)) || null
  } catch {
    return null
  }
}

/** 탭끼리 공유하고 브라우저를 다시 열어도 남도록 localStorage에 둔다. 못 쓰면 서버 미사용 한도가 막는다. */
export function sessionIdleExpired(now = Date.now()) {
  const last = readActivity()
  return last !== null && now - last >= IDLE_LIMIT_MS
}

export function useIdleLogout(enabled: boolean) {
  const { logout } = useLogout()
  const onIdle = useEffectEvent(() => void logout('/login?reason=idle'))

  useEffect(() => {
    if (!enabled) return
    let tokenAt = Date.now()
    let writtenAt = 0
    const stopWatchingToken = useAuthStore.subscribe((next, previous) => {
      if (next.accessToken !== previous.accessToken) tokenAt = Date.now()
    })
    function touch() {
      const now = Date.now()
      // 절전에서 깨어난 직후의 입력이 30초 판정보다 먼저 와도 시간을 다시 세지 않는다.
      if (sessionIdleExpired(now)) {
        onIdle()
        return
      }
      if (now - writtenAt >= 1000) {
        writtenAt = now
        try {
          localStorage.setItem(ACTIVITY_KEY, String(now))
        } catch {
          // 저장소를 못 써도 업무는 계속한다.
        }
      }
      if (now - tokenAt >= REFRESH_AFTER_MS) {
        tokenAt = now
        // 실패하면 클라이언트가 로그인 화면으로 보낸다.
        void apiClient.extendSession().catch(() => undefined)
      }
    }
    touch()
    ACTIVITY_EVENTS.forEach((type) => window.addEventListener(type, touch, { passive: true }))
    const timer = window.setInterval(() => {
      if (sessionIdleExpired()) onIdle()
    }, CHECK_EVERY_MS)
    return () => {
      stopWatchingToken()
      window.clearInterval(timer)
      ACTIVITY_EVENTS.forEach((type) => window.removeEventListener(type, touch))
    }
  }, [enabled])
}
