import { useEffect, useEffectEvent } from 'react'
import { apiClient } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useLogout } from './useLogout'

const IDLE_LIMIT_MS = 30 * 60_000
// Access(30분)가 끝나기 전에 회전해 입력만 하고 저장 요청이 없는 사용자도 끊기지 않게 한다. 서버 미사용 한도는 60분이다.
const REFRESH_AFTER_MS = 25 * 60_000
const CHECK_EVERY_MS = 30_000
// 연장이 네트워크 오류로 실패하면 Access가 끝나기 전에 한 번 더 해 본다.
const RETRY_AFTER_MS = 60_000
// 숨은 탭의 타이머가 1분에 한 번까지 늦춰져도 넘지 않는 값
const CLOSED_AFTER_MS = 3 * 60_000
const ACTIVITY_KEY = 'fgc.lastActivityAt' // clearSessionData가 로그아웃과 로그인 때 함께 지운다
const OPEN_KEY = 'fgc.openAt' // 열린 화면이 남기는 표시. 같은 이유로 fgc.로 시작한다
const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart'] as const

function readTime(key: string) {
  try {
    return Number(localStorage.getItem(key)) || null
  } catch {
    return null
  }
}
const readActivity = () => readTime(ACTIVITY_KEY)

function markOpen() {
  try {
    localStorage.setItem(OPEN_KEY, String(Date.now()))
  } catch {
    // 저장소를 못 써도 업무는 계속한다.
  }
}

/** 탭끼리 공유하고 브라우저를 다시 열어도 남도록 localStorage에 둔다. 못 쓰면 서버 미사용 한도가 막는다. */
export function sessionIdleExpired(now = Date.now()) {
  const last = readActivity()
  return last !== null && now - last >= IDLE_LIMIT_MS
}

/**
 * 화면을 모두 닫고 3분이 지났는지. 탭 복원 설정이나 창을 닫아도 남는 브라우저 실행으로
 * 세션 쿠키가 살아 있어도 다시 열 때 로그인을 끝내기 위해 본다.
 */
export function sessionClosed(now = Date.now()) {
  const open = readTime(OPEN_KEY)
  return open !== null && now - open >= CLOSED_AFTER_MS
}

/** 이 브라우저에 되살릴 로그인이 남아 있을 수 있는지. 로그아웃하면 기록도 함께 지워진다. */
export const hasSessionActivity = () => readActivity() !== null

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
        // 인증이 끝났으면 클라이언트가 로그인 화면으로 보낸다. 그 밖의 실패는 1분 뒤 입력에서 다시 한다.
        void apiClient.extendSession().catch(() => {
          tokenAt = Date.now() - REFRESH_AFTER_MS + RETRY_AFTER_MS
        })
      }
    }
    markOpen()
    touch()
    ACTIVITY_EVENTS.forEach((type) => window.addEventListener(type, touch, { passive: true }))
    const timer = window.setInterval(() => {
      if (sessionIdleExpired()) onIdle()
      else markOpen()
    }, CHECK_EVERY_MS)
    return () => {
      stopWatchingToken()
      window.clearInterval(timer)
      ACTIVITY_EVENTS.forEach((type) => window.removeEventListener(type, touch))
    }
  }, [enabled])
}
