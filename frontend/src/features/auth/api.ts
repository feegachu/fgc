import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import { validMonth } from '../../stores/workspace'
import type { MeResponse } from '../../lib/api/client'
import type { AuthUser } from './types'

export const authQueryOptions = () => queryOptions({
  queryKey: ['auth', 'me'],
  queryFn: async ({ signal }): Promise<AuthUser> => {
    const { data } = await apiClient.request<MeResponse>('/api/v1/auth/me', { signal })
    if (!data?.loginId || !data.userName || !data.roleCode || !validMonth(data.demoMonth)) {
      throw new Error('사용자 정보와 서버 기준 정산월을 확인할 수 없습니다.')
    }
    return {
      loginId: data.loginId,
      userName: data.userName,
      roleCode: data.roleCode,
      demoMonth: data.demoMonth,
      canProcess: data.canProcess === true,
      canViewAuditLog: data.canViewAuditLog === true,
      canHandleException: data.canHandleException === true,
      canReverseJournal: data.canReverseJournal === true,
      canFinalizeValidation: data.canFinalizeValidation === true,
    }
  },
  staleTime: Infinity,
  meta: { errorToast: false },
})
