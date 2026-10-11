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
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { ExceptionPage } from '../features/exception/ExceptionPage'
import { ProtectedRoute } from './ProtectedRoute'
import { RequirePermission } from './RequirePermission'
// React로 전환을 마친 화면. lazy()로 등록한 화면만 별도 청크로 나뉘어 그 경로에 들어갈 때 받는다
// (현재 /audit-logs, /schedules, /schedules/:id). 직접 import한 화면은 초기 번들에 들어간다.
// 여기 없는 경로는 기존 Thymeleaf 화면으로 안내하는 TransitionPage를 그린다.
const pages: Record<string, ComponentType> = {
  '/': DashboardPage,
  '/base': BasePage,
  '/policies': PoliciesPage,
  '/exceptions': ExceptionPage,
  '/audit-logs': lazy(() => import('../features/audit/AuditLogPage')),
  '/schedules': lazy(() => import('../features/schedule/ScheduleListPage')),
  '/schedules/:id': lazy(() => import('../features/schedule/ScheduleDetailPage')),
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
