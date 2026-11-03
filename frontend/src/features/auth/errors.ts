import { internalErrorText } from '../../lib/format'
import { ApiError } from '../../lib/api/client'

export function authErrorText(error: unknown): string {
  if (error instanceof ApiError && error.code === 'FGC-COMMON-500') {
    return internalErrorText(error.requestId)
  }
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.'
}
