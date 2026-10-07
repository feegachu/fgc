import { useQuery } from '@tanstack/react-query'
import { useAuthStore } from '../../stores/auth'
import { authQueryOptions } from './api'
import type { Permission } from './types'

// 토큰은 기존 메모리 store, 사용자·권한은 공통 Query 캐시 한 곳에서 관리한다.
export function useAuth() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const loggingOut = useAuthStore((state) => state.loggingOut)
  const query = useQuery({ ...authQueryOptions(), enabled: Boolean(accessToken) && !loggingOut })
  return { ...query, accessToken, loggingOut, user: accessToken && !loggingOut ? query.data : undefined }
}

export function usePermission(permission: Permission) {
  const { user } = useAuth()
  return user?.[permission] === true
}
