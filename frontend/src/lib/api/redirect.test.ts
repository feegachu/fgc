import { describe, expect, it, vi } from 'vitest'
import { createApiClient } from './client'
import { loginUrl, routerPath, safeRedirect } from './redirect'

describe('로그인 경로와 복귀 경로는 다르게 검증한다', () => {
  it.each(['/app/', '/'])('base=%s에서 로그인 페이지를 식별하고 자기 자신으로 복귀하지 않는다', (base) => {
    const location = { pathname: `${base}login`, search: '?error', hash: '', assign: vi.fn() }
    const client = createApiClient({ fetch: vi.fn(), location, base, getToken: () => null, setToken: vi.fn() })
    expect(client.isLoginPage()).toBe(true)
    expect(routerPath(location, base)).toBe('/login?error')
    expect(safeRedirect(routerPath(location, base))).toBe('/')
  })
  it.each([null, '', 'https://evil.example', '//evil.example', '/\\evil.example', '/\nevil.example', '/login', '/login?redirect=/contracts', '/login#form', '/login/', '/LOGIN', '/%6cogin', '/a/../login', '/%zz'])('복귀 대상으로 %s를 거부한다', (path) => {
    expect(safeRedirect(path)).toBe('/')
  })
  it('query와 hash를 한 번 인코딩하며 이미 인코딩한 쿼리 값은 보존한다', () => {
    const location = { pathname: '/app/contracts', search: '?name=%ED%95%9C%EA%B8%80', hash: '#detail' }
    const url = new URL(loginUrl(location, '/app/'), 'https://fgc.test')
    expect(safeRedirect(url.searchParams.get('redirect'))).toBe('/contracts?name=%ED%95%9C%EA%B8%80#detail')
  })
})
