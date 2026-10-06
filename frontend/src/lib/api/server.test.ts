/// <reference types="node" />
import { describe, expect, it } from 'vitest'
import { createApiClient } from './client'

const origin = process.env.FGC_INTEGRATION_URL
// The Java opt-in test starts a real HTTP server and an isolated Testcontainers database.
// Java signs an expired access for the same live session; refresh returns a normal token.
describe.skipIf(!origin)('실제 JWT 서버 연동', () => {
  function environment() {
    let cookie = ''
    let token: string | null = null
    const calls: string[] = []
    const redirects: string[] = []
    const fetcher: typeof fetch = async (path, init) => {
      calls.push(String(path))
      const headers = new Headers(init?.headers)
      headers.set('Origin', origin!)
      if (cookie) headers.set('Cookie', cookie)
      const response = await fetch(`${origin}${path}`, { ...init, headers })
      const setCookie = response.headers.get('Set-Cookie')
      if (setCookie) cookie = setCookie.split(';')[0]
      return response
    }
    const client = createApiClient({
      fetch: fetcher, base: '/app/',
      location: { pathname: '/app/contracts', search: '?page=2', hash: '', assign: (url) => { redirects.push(String(url)) } },
      getToken: () => token, setToken: (value) => { token = value },
    })
    return { client, calls, redirects, getToken: () => token, setToken: (value: string) => { token = value }, setCookie: (value: string) => { cookie = value }, raw: fetcher }
  }
  it('실제 access 만료 → 동시 3요청 refresh 1회 → 원 요청 성공', async () => {
    const env = environment()
    env.setToken(process.env.FGC_EXPIRED_ACCESS!)
    env.setCookie(`fgc_refresh=${process.env.FGC_EXPIRED_REFRESH!}`)
    const results = await Promise.all([1, 2, 3].map(() => env.client.request('/api/v1/contracts')))
    expect(results.every((result) => result.error === null)).toBe(true)
    expect(env.calls.filter((path) => path === '/api/v1/auth/refresh')).toHaveLength(1)
    expect(env.getToken()).toBeTruthy()
    expect(env.redirects).toHaveLength(0)
  })
  it('실제 refresh 쿠키 없음 → 토큰 삭제와 원 경로 보존', async () => {
    const env = environment()
    await env.client.login('settle01', 'fgc1234!')
    env.setCookie('')
    env.setToken('invalid-access')
    await expect(env.client.request('/api/v1/contracts')).rejects.toMatchObject({ status: 401, code: 'FGC-AUTH-002' })
    expect(env.getToken()).toBeNull()
    expect(env.redirects).toEqual(['/app/login?redirect=%2Fcontracts%3Fpage%3D2'])
  })
  it('실제 중복 로그인 업무 오류는 refresh 0회와 duplicate 이유를 보존한다', async () => {
    const older = environment()
    const newer = environment()
    await older.client.login('settle01', 'fgc1234!')
    await newer.client.login('settle01', 'fgc1234!')
    await expect(older.client.request('/api/v1/contracts')).rejects.toMatchObject({ status: 401, code: 'FGC-AUTH-004' })
    expect(older.calls.filter((path) => path === '/api/v1/auth/refresh')).toHaveLength(0)
    expect(older.getToken()).toBeNull()
    expect(older.redirects).toEqual(['/app/login?reason=duplicate&redirect=%2Fcontracts%3Fpage%3D2'])
  })
})
