import { Navigate, useSearchParams } from 'react-router'
import { useAuth } from '../features/auth/useAuth'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { validMonth, withMonth } from '../stores/workspace'

/** 설계사 홈(#659) 연동 전에는 권한이 적용된 본인 계약 목록으로 진입한다. */
export function HomePage() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const month = params.get('month')
  const home = validMonth(month) ? withMonth('/contracts', month) : '/contracts'
  return user?.roleCode === 'AGENT' ? <Navigate to={home} replace /> : <DashboardPage />
}
