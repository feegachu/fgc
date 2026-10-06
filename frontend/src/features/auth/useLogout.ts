import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { apiClient } from '../../lib/api/client'
import { clearSessionData } from './session'
import { useAuthStore } from '../../stores/auth'

export function useLogout() {
  const client = useQueryClient()
  const navigate = useNavigate()
  const loggingOut = useAuthStore((state) => state.loggingOut)
  async function logout() {
    if (useAuthStore.getState().loggingOut) return
    useAuthStore.getState().setLoggingOut(true)
    clearSessionData(client)
    let failed = false
    try {
      await apiClient.logout()
    } catch {
      failed = true
    } finally {
      // 서버 통신이 실패해도 보호 화면과 이전 사용자의 캐시로 돌아가지 않는다.
      clearSessionData(client)
      await navigate(failed ? '/login?logout=failed' : '/login?logout', { replace: true, flushSync: true })
      useAuthStore.getState().setLoggingOut(false)
    }
  }
  return { logout, loggingOut }
}
