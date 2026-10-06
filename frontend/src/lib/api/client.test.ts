import { describe, expect, it, vi } from 'vitest'
import { createApiClient, ApiError } from './client'
import { loginUrl, safeRedirect } from './redirect'

const ok = (data: unknown = { id: 1 }) => Response.json({ data, error: null, requestId: 'req-1' })
const fail = (status = 401, code = 'FGC-AUTH-002') => Response.json({ data: null, error: { code, message: '서버 문구', field: 'name', params: { value: 1 }, detail: 'detail' }, requestId: 'error-1' }, { status })
function setup(fetcher: typeof fetch) {
  let token: string | null = 'old'
  const location = { pathname: '/app/contracts', search: '?status=ACTIVE', hash: '#detail', assign: vi.fn() }
  const client = createApiClient({ fetch: fetcher, location, base: '/app/', getToken: () => token, setToken: (value) => { token = value } })
  return { client, location, getToken: () => token, setToken: (value: string | null) => { token = value } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}
describe('API 계약', () => {
  it('봉투·페이지를 유지하고 Bearer, JSON, 멱등성 키, signal을 보낸다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(ok({ content: [], page: 1 }))
    const { client } = setup(fetcher)
    const controller = new AbortController()
    expect(await client.request('/api/v1/contracts', { method: 'POST', body: { name: 'x' }, idempotencyKey: 'key', signal: controller.signal })).toEqual({ data: { content: [], page: 1 }, error: null, requestId: 'req-1' })
    const [, options] = fetcher.mock.calls[0]
    const headers = new Headers(options?.headers)
    expect(headers.get('Authorization')).toBe('Bearer old')
    expect(headers.get('Idempotency-Key')).toBe('key')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(options?.body).toBe('{"name":"x"}')
    expect(options?.signal).toBe(controller.signal)
  })
  it('204는 null, requestId 헤더를 반환한다', async () => {
    const { client } = setup(vi.fn().mockResolvedValue(new Response(null, { status: 204, headers: { 'X-Request-Id': 'empty' } })))
    expect(await client.request('/api/v1/contracts')).toEqual({ data: null, error: null, requestId: 'empty' })
  })
  it.each([400, 403, 409, 500])('%i 오류 필드를 보존하고 refresh하지 않는다', async (status) => {
    const fetcher = vi.fn().mockResolvedValue(fail(status, 'FGC-CONT-001'))
    const { client } = setup(fetcher)
    await expect(client.request('/api/v1/contracts')).rejects.toMatchObject({ name: 'ApiError', code: 'FGC-CONT-001', message: '서버 문구', field: 'name', params: { value: 1 }, detail: 'detail', requestId: 'error-1', status })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('비JSON 502에서 헤더 requestId를 보존한다', async () => {
    const { client } = setup(vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502, headers: { 'X-Request-Id': 'proxy' } })))
    await expect(client.request('/api/v1/contracts')).rejects.toMatchObject({ code: 'FGC-COMMON-500', requestId: 'proxy', status: 502 })
  })
  it('성공 상태라도 잘못된 봉투는 오류다', async () => {
    const { client } = setup(vi.fn().mockResolvedValue(Response.json({ foo: 1 })))
    await expect(client.request('/api/v1/contracts')).rejects.toBeInstanceOf(ApiError)
  })
  it('FormData에 JSON Content-Type을 설정하지 않는다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(ok())
    const { client } = setup(fetcher)
    const body = new FormData()
    await client.request('/api/v1/contracts', { method: 'POST', body })
    expect(new Headers(fetcher.mock.calls[0][1]?.headers).has('Content-Type')).toBe(false)
    expect(fetcher.mock.calls[0][1]?.body).toBe(body)
  })
  it.each(['https://example.com/api/v1/contracts', '//example.com/api/v1/contracts', '/api/v1/../external'])('API 경로 밖으로 토큰을 보내지 않는다: %s', async (path) => {
    const fetcher = vi.fn()
    await expect(setup(fetcher).client.request(path)).rejects.toBeInstanceOf(TypeError)
    expect(fetcher).not.toHaveBeenCalled()
  })
})
describe('401과 세션 복구', () => {
  it('동시 요청 3개는 refresh 한 번을 공유하고 각각 재시도한다', async () => {
    const refreshResult = deferred<Response>()
    const fetcher = vi.fn<typeof fetch>(async (path, options) => {
      if (path === '/api/v1/auth/refresh') return refreshResult.promise
      return new Headers(options?.headers).get('Authorization') === 'Bearer old' ? fail() : ok()
    })
    const { client, getToken } = setup(fetcher)
    const results = Promise.all([1, 2, 3].map(() => client.request('/api/v1/contracts')))
    await vi.waitFor(() => expect(fetcher.mock.calls.filter(([p]) => p === '/api/v1/auth/refresh')).toHaveLength(1))
    refreshResult.resolve(ok({ accessToken: 'new', tokenType: 'Bearer', expiresIn: 1800 }))
    expect(await results).toHaveLength(3)
    expect(getToken()).toBe('new')
    expect(fetcher).toHaveBeenCalledTimes(7)
    const refreshOptions = fetcher.mock.calls.find(([p]) => p === '/api/v1/auth/refresh')![1]
    expect(new Headers(refreshOptions?.headers).get('X-FGC-Client')).toBe('web')
    expect(new Headers(refreshOptions?.headers).has('Authorization')).toBe(false)
    expect(refreshOptions?.credentials).toBe('same-origin')
  })
  it('refresh 이후 늦게 도착한 옛 토큰의 401은 추가 refresh 없이 재시도한다', async () => {
    const late = deferred<Response>()
    const fetcher = vi.fn<typeof fetch>(async (path, options) => {
      if (path === '/api/v1/auth/refresh') return ok({ accessToken: 'new' })
      if (new Headers(options?.headers).get('Authorization') === 'Bearer new') return ok()
      return path === '/api/v1/late' ? late.promise : fail()
    })
    const { client } = setup(fetcher)
    const delayed = client.request('/api/v1/late')
    await client.request('/api/v1/contracts')
    late.resolve(fail())
    await delayed
    expect(fetcher.mock.calls.filter(([p]) => p === '/api/v1/auth/refresh')).toHaveLength(1)
  })
  it('본문 없는 401도 refresh한다', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 })).mockResolvedValueOnce(ok({ accessToken: 'new' })).mockResolvedValueOnce(ok())
    await expect(setup(fetcher).client.request('/api/v1/contracts')).resolves.toMatchObject({ data: { id: 1 } })
    expect(fetcher).toHaveBeenCalledTimes(3)
  })
  it('refresh 실패는 토큰을 지우고 basename 없는 원 경로로 이동한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(fail())
    const { client, getToken, location } = setup(fetcher)
    await expect(client.request('/api/v1/contracts')).rejects.toBeInstanceOf(ApiError)
    expect(getToken()).toBeNull()
    expect(location.assign).toHaveBeenCalledWith('/app/login?redirect=%2Fcontracts%3Fstatus%3DACTIVE%23detail')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('중복 로그인은 refresh 없이 종료한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(fail(401, 'FGC-AUTH-004'))
    const { client, getToken, location } = setup(fetcher)
    await expect(client.request('/api/v1/contracts')).rejects.toMatchObject({ code: 'FGC-AUTH-004' })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(getToken()).toBeNull()
    expect(location.assign).toHaveBeenCalledWith('/app/login?reason=duplicate&redirect=%2Fcontracts%3Fstatus%3DACTIVE%23detail')
  })
  it('재시도도 401이면 두 번째 refresh를 하지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(fail()).mockResolvedValueOnce(ok({ accessToken: 'new' })).mockResolvedValueOnce(fail())
    const { client, getToken } = setup(fetcher)
    await expect(client.request('/api/v1/contracts')).rejects.toBeInstanceOf(ApiError)
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(getToken()).toBeNull()
  })
  it('진행 중 refresh가 중복 로그인 차단을 되돌리지 못한다', async () => {
    const refreshing = deferred<Response>()
    const fetcher = vi.fn<typeof fetch>(async (path) => path === '/api/v1/auth/refresh' ? refreshing.promise : path === '/api/v1/duplicate' ? fail(401, 'FGC-AUTH-004') : fail())
    const { client, getToken } = setup(fetcher)
    const pending = client.request('/api/v1/contracts').catch((e) => e)
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
    await expect(client.request('/api/v1/duplicate')).rejects.toBeInstanceOf(ApiError)
    refreshing.resolve(ok({ accessToken: 'new' }))
    expect(await pending).toBeInstanceOf(ApiError)
    expect(getToken()).toBeNull()
  })
  it('앱 시작 복구를 중복 호출해도 한 번만 실행한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(ok({ accessToken: 'restored' }))
    const { client, setToken, getToken } = setup(fetcher)
    setToken(null)
    await Promise.all([client.restoreSession(), client.restoreSession()])
    await client.restoreSession()
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(getToken()).toBe('restored')
  })
  it('로그인 실패는 refresh나 이동 없이 폼에 오류를 반환한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(fail(401, 'FGC-AUTH-001'))
    const { client, location } = setup(fetcher)
    await expect(client.login('demo', 'wrong')).rejects.toMatchObject({ code: 'FGC-AUTH-001' })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(location.assign).not.toHaveBeenCalled()
  })
  it('로그인 화면의 쿠키 복원이 실패해도 로그인 URL로 다시 이동하지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValue(fail())
    const { client, location, setToken, getToken } = setup(fetcher)
    setToken(null)
    await expect(client.restoreSession({ redirectOnFailure: false })).rejects.toBeInstanceOf(ApiError)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(getToken()).toBeNull()
    expect(location.assign).not.toHaveBeenCalled()
  })
  it('로그인 화면의 유효한 쿠키는 토큰으로 복원한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(ok({ accessToken: 'restored' }))
    const { client, location, setToken, getToken } = setup(fetcher)
    setToken(null)
    await client.restoreSession({ redirectOnFailure: false })
    expect(getToken()).toBe('restored')
    expect(location.assign).not.toHaveBeenCalled()
  })
  it('/auth/me도 Bearer와 web 헤더를 사용하고 만료 시 갱신한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(fail()).mockResolvedValueOnce(ok({ accessToken: 'new' })).mockResolvedValueOnce(ok({ loginId: 'demo' }))
    const { client } = setup(fetcher)
    await client.request('/api/v1/auth/me')
    expect(new Headers(fetcher.mock.calls[2][1]?.headers).get('Authorization')).toBe('Bearer new')
    expect(new Headers(fetcher.mock.calls[2][1]?.headers).get('X-FGC-Client')).toBe('web')
  })
})
it('base=/ 전환과 안전한 redirect 형식을 지킨다', () => {
  expect(loginUrl({ pathname: '/contracts', search: '?page=2', hash: '' }, '/')).toBe('/login?redirect=%2Fcontracts%3Fpage%3D2')
  expect(safeRedirect('//evil.test')).toBe('/')
  expect(safeRedirect('/\\evil.test')).toBe('/')
  expect(safeRedirect('https://evil.test')).toBe('/')
  expect(safeRedirect('/contracts?page=2')).toBe('/contracts?page=2')
})
it('refresh 대기 중 취소된 원 요청을 재전송하지 않는다', async () => {
  const result = deferred<Response>()
  const fetcher = vi.fn<typeof fetch>(async (path) => path === '/api/v1/auth/refresh' ? result.promise : fail())
  const { client } = setup(fetcher)
  const controller = new AbortController()
  const pending = client.request('/api/v1/contracts', { signal: controller.signal }).catch((error) => error)
  await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
  controller.abort()
  result.resolve(ok({ accessToken: 'new' }))
  expect(await pending).toMatchObject({ name: 'AbortError' })
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('진행 중 refresh는 logout 이후 토큰을 되살리지 않는다', async () => {
  const result = deferred<Response>()
  const fetcher = vi.fn<typeof fetch>(async (path) => path === '/api/v1/auth/refresh' ? result.promise : path === '/api/v1/auth/logout' ? ok(null) : fail())
  const { client, getToken } = setup(fetcher)
  const pending = client.request('/api/v1/contracts').catch((error) => error)
  await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
  await client.logout()
  result.resolve(ok({ accessToken: 'new' }))
  expect(await pending).toBeInstanceOf(ApiError)
  expect(getToken()).toBeNull()
})
it('새 로그인 이후 도착한 이전 요청의 duplicate 응답이 새 토큰을 지우지 않는다', async () => {
  const result = deferred<Response>()
  const fetcher = vi.fn<typeof fetch>(async (path) => path === '/api/v1/auth/login' ? ok({ accessToken: 'signed-in' }) : result.promise)
  const { client, getToken, location } = setup(fetcher)
  const pending = client.request('/api/v1/contracts').catch((error) => error)
  await client.login('demo', 'password')
  result.resolve(fail(401, 'FGC-AUTH-004'))
  expect(await pending).toBeInstanceOf(ApiError)
  expect(getToken()).toBe('signed-in')
  expect(location.assign).not.toHaveBeenCalled()
})
it('CSV도 refresh 후 같은 Accept와 새 Bearer로 다시 요청한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(fail()).mockResolvedValueOnce(ok({ accessToken: 'new' })).mockResolvedValueOnce(new Response('id\n1', { headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename=x.csv' } }))
  const { client } = setup(fetcher)
  expect((await client.requestBlob('/api/v1/contracts/export')).disposition).toContain('x.csv')
  const headers = new Headers(fetcher.mock.calls[2][1]?.headers)
  expect(headers.get('Accept')).toBe('text/csv')
  expect(headers.get('Authorization')).toBe('Bearer new')
})
it('로그아웃 뒤 도착한 이전 사용자의 성공 응답을 화면에 전달하지 않는다', async () => {
  const result = deferred<Response>()
  const fetcher = vi.fn<typeof fetch>(async (path) => path === '/api/v1/auth/logout' ? ok(null) : result.promise)
  const { client } = setup(fetcher)
  const pending = client.request('/api/v1/contracts').catch((error) => error)
  await client.logout()
  result.resolve(ok({ private: 'previous-user-data' }))
  expect(await pending).toMatchObject({ name: 'ApiError', code: 'FGC-AUTH-002' })
})
