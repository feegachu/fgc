import { Navigate } from 'react-router'
import { useAuth } from '../features/auth/useAuth'
import { DashboardPage } from '../features/dashboard/DashboardPage'

/** 설계사 홈(#659) 연동 전에는 권한이 적용된 본인 계약 목록으로 진입한다. */
export function HomePage() {
  const { user } = useAuth()
  return user?.roleCode === 'AGENT' ? <Navigate to="/contracts" replace /> : <DashboardPage />
}
