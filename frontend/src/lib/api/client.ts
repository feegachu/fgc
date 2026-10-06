import { useAuthStore } from '../../stores/auth'
import { ApiError, readEnvelope } from './errors'
import { loginUrl, routerPath } from './redirect'
import type { ApiEnvelope } from './errors'
import type { components } from './schema'

export { ApiError } from './errors'
export type { ApiEnvelope } from './errors'
export interface RequestOptions {
  method?: string
  headers?: HeadersInit
  body?: unknown
  signal?: AbortSignal
  idempotencyKey?: string
}
// springdoc marks response properties optional; successful token responses require these fields.
export type TokenResponse = Required<components['schemas']['TokenResponse']>
export type LoginRequest = components['schemas']['LoginRequest']
export type MeResponse = components['schemas']['MeResponse']
interface ClientEnvironment {
  fetch: typeof fetch
  location: Pick<Location, 'pathname' | 'search' | 'hash' | 'assign'>
  base: string
  getToken: () => string | null
  setToken: (token: string | null) => void
}

export function createApiClient(env: ClientEnvironment) {
  let refreshPromise: Promise<void> | null = null
  let startupPromise: Promise<void> | null = null
  let generation = 0
  let redirected = false

  function expire(duplicate = false) {
    generation++
    env.setToken(null)
    if (!redirected) {
      redirected = true
      env.location.assign(loginUrl(env.location, env.base, duplicate))
    }
  }
  async function send(path: string, options: RequestOptions, accept: string, token: string | null) {
    // Only same-origin API paths can receive credentials. Never forward a token to an arbitrary URL.
    if (!path.startsWith('/api/v1/') || /[\\#]/.test(path) || path.split('?')[0].split('/').includes('..')) {
      throw new TypeError('API 경로는 /api/v1/로 시작해야 합니다.')
    }
    const headers = new Headers(options.headers)
    headers.set('Accept', accept)
    headers.delete('Authorization')
    const cookieAuth = /^\/api\/v1\/auth\/(login|refresh|logout)(?:\?|$)/.test(path)
    if (path.startsWith('/api/v1/auth/')) headers.set('X-FGC-Client', 'web')
    if (token && !cookieAuth) headers.set('Authorization', `Bearer ${token}`)
    if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey)
    const form = options.body instanceof FormData
    if (options.body !== undefined && !form) headers.set('Content-Type', 'application/json')
    return env.fetch(path, {
      method: options.method ?? 'GET', headers,
      body: options.body === undefined ? undefined : form ? options.body as FormData : JSON.stringify(options.body),
      credentials: 'same-origin', signal: options.signal,
    })
  }
  async function refresh() {
    if (refreshPromise) return refreshPromise
    const currentGeneration = generation
    const pending = (async () => {
      try {
        const response = await send('/api/v1/auth/refresh', { method: 'POST' }, 'application/json', null)
        const { data } = await readEnvelope<TokenResponse>(response)
        if (!data?.accessToken) throw new ApiError(null, response.headers.get('X-Request-Id'), response.status)
        // Logout or a duplicate-login response must not be undone by an older refresh.
        if (generation !== currentGeneration) throw new ApiError({ code: 'FGC-AUTH-002' }, null, 401)
        env.setToken(data.accessToken)
      } catch (error) {
        if (generation === currentGeneration) expire(error instanceof ApiError && error.code === 'FGC-AUTH-004')
        throw error
      }
    })()
    refreshPromise = pending
    try { await pending } finally { if (refreshPromise === pending) refreshPromise = null }
  }
  async function responseFor(path: string, options: RequestOptions, accept: string): Promise<Response> {
    const currentGeneration = generation
    const token = env.getToken()
    const response = await send(path, options, accept, token)
    if (generation !== currentGeneration) throw new ApiError({ code: 'FGC-AUTH-002' }, response.headers.get('X-Request-Id'), 401)
    if (response.status !== 401) return response
    let error: unknown
    try { await readEnvelope(response) } catch (caught) { error = caught }
    if (generation !== currentGeneration) throw error
    if (error instanceof ApiError && error.code === 'FGC-AUTH-004') {
      expire(true)
      throw error
    }
    // Login/refresh/logout report their own errors; /auth/me is a normal protected request.
    if (/^\/api\/v1\/auth\/(login|refresh|logout)(?:\?|$)/.test(path)) throw error
    if (generation !== currentGeneration || redirected) throw error
    // A late 401 for an older token should use the token already refreshed by another request.
    if (!env.getToken() || env.getToken() === token) await refresh()
    options.signal?.throwIfAborted()
    if (generation !== currentGeneration) throw error
    const retry = await send(path, options, accept, env.getToken())
    if (generation !== currentGeneration) throw new ApiError({ code: 'FGC-AUTH-002' }, retry.headers.get('X-Request-Id'), 401)
    if (retry.status === 401) {
      let retryError: unknown
      try { await readEnvelope(retry) } catch (caught) { retryError = caught }
      expire(retryError instanceof ApiError && retryError.code === 'FGC-AUTH-004')
      throw retryError
    }
    return retry
  }
  async function request<T>(path: string, options: RequestOptions = {}): Promise<ApiEnvelope<T>> {
    return readEnvelope<T>(await responseFor(path, options, 'application/json'))
  }
  async function requestBlob(path: string, options: RequestOptions = {}) {
    const response = await responseFor(path, options, 'text/csv')
    if (!response.ok || /json/i.test(response.headers.get('Content-Type') ?? '')) {
      const envelope = await readEnvelope(response)
      throw new ApiError(null, envelope.requestId, response.status)
    }
    return { blob: await response.blob(), disposition: response.headers.get('Content-Disposition') }
  }
  function restoreSession(): Promise<void> {
    // StrictMode and callers share one startup attempt; an existing login needn't be rotated.
    startupPromise ??= env.getToken() ? Promise.resolve() : refresh()
    return startupPromise
  }
  async function login(loginId: string, password: string) {
    generation++
    env.setToken(null)
    const currentGeneration = generation
    const body: LoginRequest = { loginId, password }
    const envelope = await request<TokenResponse>('/api/v1/auth/login', { method: 'POST', body })
    if (generation !== currentGeneration) throw new ApiError({ code: 'FGC-AUTH-002' }, null, 401)
    if (!envelope.data?.accessToken) throw new ApiError(null, envelope.requestId, 200)
    env.setToken(envelope.data.accessToken)
    redirected = false
    return envelope
  }
  async function logout() {
    generation++
    env.setToken(null)
    return request<null>('/api/v1/auth/logout', { method: 'POST' })
  }
  // Don't redirect the login page back to itself on a missing refresh cookie.
  function isLoginPage() { return routerPath(env.location, env.base).split(/[?#]/)[0] === '/login' }
  return { request, requestBlob, restoreSession, login, logout, isLoginPage }
}
export const apiClient = createApiClient({
  fetch: (...args) => globalThis.fetch(...args),
  location: window.location, base: import.meta.env.BASE_URL,
  getToken: () => useAuthStore.getState().accessToken,
  setToken: (token) => useAuthStore.getState().setAccessToken(token),
})
