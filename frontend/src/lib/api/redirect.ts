// Shared with #404: redirect is a router path, excluding Vite's basename.
export function safeRedirect(value: string | null | undefined): string {
  return value?.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\')
    ? value : '/'
}
export function routerPath(location: Pick<Location, 'pathname' | 'search' | 'hash'>, base: string): string {
  const prefix = base.replace(/\/$/, '')
  const path = prefix && (location.pathname === prefix || location.pathname.startsWith(`${prefix}/`))
    ? location.pathname.slice(prefix.length) || '/' : location.pathname
  return safeRedirect(path + location.search + location.hash)
}
export function loginUrl(location: Pick<Location, 'pathname' | 'search' | 'hash'>, base: string, duplicate = false): string {
  return `${base.replace(/\/?$/, '/')}login?${duplicate ? 'reason=duplicate&' : ''}redirect=${encodeURIComponent(routerPath(location, base))}`
}
