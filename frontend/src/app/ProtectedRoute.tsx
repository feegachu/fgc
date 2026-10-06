import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from '../features/auth/useAuth'
import { AuthStatus } from '../features/auth/AuthStatus'

export function ProtectedRoute() {
  const location = useLocation()
  const auth = useAuth()
  if (auth.loggingOut) return <AuthStatus />
  if (!auth.accessToken) {
    const redirect = encodeURIComponent(location.pathname + location.search + location.hash)
    return <Navigate to={`/login?redirect=${redirect}`} replace />
  }
  if (!auth.user) return <AuthStatus error={auth.error} retry={() => { void auth.refetch() }} />
  return <Outlet />
}
