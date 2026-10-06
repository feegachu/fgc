// Shared with #404: redirect is a router path, excluding Vite's basename.
function isRouterPath(value: string | null | undefined): value is string {
  return Boolean(value?.startsWith('/') && !value.startsWith('//') && !value.includes('\\') &&
    ![...value].some((character) => character.charCodeAt(0) <= 32 || character.charCodeAt(0) === 127))
}
export function safeRedirect(value: string | null | undefined): string {
  if (!isRouterPath(value)) return '/'
  try {
    // 점 세그먼트와 인코딩된 로그인 경로도 자기 자신으로 복귀하지 않도록 정규화한다.
    const url = new URL(value, 'https://redirect.local')
    const path = decodeURIComponent(url.pathname).replace(/\/+$/, '').toLowerCase()
    if (url.origin !== 'https://redirect.local' || path === '/login') return '/'
    return value
  } catch {
    return '/'
  }
}
export function routerPath(location: Pick<Location, 'pathname' | 'search' | 'hash'>, base: string): string {
  const prefix = base.replace(/\/$/, '')
  const path = prefix && (location.pathname === prefix || location.pathname.startsWith(`${prefix}/`))
    ? location.pathname.slice(prefix.length) || '/' : location.pathname
  const value = path + location.search + location.hash
  // 현재 위치 판별에는 /login도 유효하다. 복귀 대상으로 쓸 때만 safeRedirect로 제외한다.
  return isRouterPath(value) ? value : '/'
}
export function loginUrl(location: Pick<Location, 'pathname' | 'search' | 'hash'>, base: string, duplicate = false): string {
  return `${base.replace(/\/?$/, '/')}login?${duplicate ? 'reason=duplicate&' : ''}redirect=${encodeURIComponent(routerPath(location, base))}`
}
