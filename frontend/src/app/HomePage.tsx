import { Navigate, useSearchParams } from 'react-router'
import { useAuth } from '../features/auth/useAuth'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { validMonth, withMonth } from '../stores/workspace'

/** 설계사는 첫 화면으로 포트폴리오(AGNT-W01)에 들어간다. */
export function HomePage() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const month = params.get('month')
  const home = validMonth(month) ? withMonth('/agent-home', month) : '/agent-home'
  return user?.roleCode === 'AGENT' ? <Navigate to={home} replace /> : <DashboardPage />
}
