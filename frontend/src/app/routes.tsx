import { BasePage } from '../features/base/BasePage'
import { PoliciesPage } from '../features/policies/PoliciesPage'
import type { RouteObject } from 'react-router'
import { AppShell } from './shell/AppShell'
import { ApplicationRoot } from './ApplicationRoot'
import { screens } from './screens'
import { TransitionPage } from './TransitionPage'
import { NotFoundPage } from './NotFoundPage'
import { LoginPage } from '../features/auth/LoginPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { ProtectedRoute } from './ProtectedRoute'
import { RequirePermission } from './RequirePermission'
export const screenRoutes: RouteObject[] = screens.map((screen) => ({
  path: screen.path,
  element: (
    <RequirePermission permission={screen.permission}>
      {screen.id === 'BASE-W01' ? (
        <BasePage />
      ) : screen.id === 'POL-W01' ? (
        <PoliciesPage />
      ) : screen.id === 'DASH-W01' ? (
        <DashboardPage />
      ) : (
        <TransitionPage screen={screen} />
      )}
    </RequirePermission>
  ),
  handle: { screenId: screen.id, title: screen.title, parentMenu: screen.menu, permission: screen.permission },
}))
export const routes: RouteObject[] = [
  {
    element: <ApplicationRoot />,
    children: [
      { path: '/login', element: <LoginPage />, handle: { screenId: 'AUTH-W01', title: '로그인' } },
      {
        element: <ProtectedRoute />,
        children: [{ element: <AppShell />, children: [...screenRoutes, { path: '*', element: <NotFoundPage /> }] }],
      },
    ],
  },
]
