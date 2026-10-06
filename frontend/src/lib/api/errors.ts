export interface ErrorBody {
  code?: string
  message?: string
  field?: string | null
  params?: Record<string, unknown>
  detail?: unknown
}
export interface ApiEnvelope<T> {
  data: T
  error: ErrorBody | null
  requestId: string | null
}
export class ApiError extends Error {
  readonly code: string
  readonly field: string | null
  readonly params: Record<string, unknown>
  readonly detail: unknown
  readonly requestId: string | null
  readonly status: number

  constructor(error: ErrorBody | null, requestId: string | null, status: number) {
    super(error?.message || '요청을 처리하지 못했습니다.')
    this.name = 'ApiError'
    this.code = error?.code || 'FGC-COMMON-500'
    this.field = error?.field || null
    this.params = error?.params || {}
    this.detail = error?.detail ?? null
    this.requestId = requestId
    this.status = status
  }
}
export async function readEnvelope<T>(response: Response): Promise<ApiEnvelope<T>> {
  const requestId = response.headers.get('X-Request-Id')
  if (response.status === 204 && response.ok) {
    return { data: null as T, error: null, requestId }
  }
  let body: ApiEnvelope<T>
  try {
    body = await response.json()
  } catch {
    throw new ApiError(null, requestId, response.status)
  }
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new ApiError(null, requestId, response.status)
  }
  if (!response.ok || body.error) {
    throw new ApiError(body.error, body.requestId || requestId, response.status)
  }
  if (!('data' in body) || !('error' in body) || !('requestId' in body)) {
    throw new ApiError(null, requestId, response.status)
  }
  return { ...body, requestId: body.requestId || requestId }
}
