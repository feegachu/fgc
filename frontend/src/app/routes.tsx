import { lazy, Suspense } from 'react'
import type { ComponentType } from 'react'
import { BasePage } from '../features/base/BasePage'
import { PoliciesPage } from '../features/policies/PoliciesPage'
import type { RouteObject } from 'react-router'
import { AppShell } from './shell/AppShell'
import { ApplicationRoot } from './ApplicationRoot'
import { screens } from './screens'
import { TransitionPage } from './TransitionPage'
import { NotFoundPage } from './NotFoundPage'
import { LoginPage } from '../features/auth/LoginPage'
import { ProtectedRoute } from './ProtectedRoute'
import { RequirePermission } from './RequirePermission'
// React로 전환을 마친 화면. 화면별로 청크를 나눠 다른 업무 화면의 JS·CSS를 먼저 받지 않게 한다.
// 여기 없는 경로는 기존 Thymeleaf 화면으로 안내하는 TransitionPage를 그린다.
const pages: Record<string, ComponentType> = {
  '/base': BasePage,
  '/policies': PoliciesPage,
  '/audit-logs': lazy(() => import('../features/audit/AuditLogPage')),
}
export const screenRoutes: RouteObject[] = screens.map((screen) => {
  const Page = pages[screen.path]
  return {
    path: screen.path,
    element: (
      <RequirePermission permission={screen.permission}>
        {Page ? (
          <Suspense fallback={null}>
            <Page />
          </Suspense>
        ) : (
          <TransitionPage screen={screen} />
        )}
      </RequirePermission>
    ),
    handle: { screenId: screen.id, title: screen.title, parentMenu: screen.menu, permission: screen.permission },
  }
})
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
