import { lazy, Suspense } from 'react'
import type { ComponentType } from 'react'
import { BasePage } from '../features/base/BasePage'
import { PoliciesPage } from '../features/policies/PoliciesPage'
import type { RouteObject } from 'react-router'
import { AppShell } from './shell/AppShell'
import { ApplicationRoot } from './ApplicationRoot'
import { screens } from './screens'
import { TransitionPage } from './TransitionPage'
import { NotFoundPage } from '../features/error/ErrorPages'
import { RouteErrorBoundary } from './ErrorBoundary'
import { LoginPage } from '../features/auth/LoginPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { ExceptionPage } from '../features/exception/ExceptionPage'
import { ProtectedRoute } from './ProtectedRoute'
import { RequirePermission } from './RequirePermission'
// React로 전환을 마친 화면. lazy()로 등록한 화면만 별도 청크로 나뉘어 그 경로에 들어갈 때 받는다
// (현재 /audit-logs). 직접 import한 화면은 초기 번들에 들어간다.
// 여기 없는 경로는 기존 Thymeleaf 화면으로 안내하는 TransitionPage를 그린다.
const pages: Record<string, ComponentType> = {
  '/': DashboardPage,
  '/base': BasePage,
  '/policies': PoliciesPage,
  '/exceptions': ExceptionPage,
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
    // 셸 밖(로그인·셸 자체)의 예외도 React Router 기본 화면(스택 노출) 대신 오류 화면으로 받는다.
    errorElement: <RouteErrorBoundary />,
    children: [
      { path: '/login', element: <LoginPage />, handle: { screenId: 'AUTH-W01', title: '로그인' } },
      {
        element: <ProtectedRoute />,
        children: [
          {
            element: <AppShell />,
            // 업무 화면 예외는 셸 안에서 받는다. 셸·탭·사이드바는 그대로 동작한다.
            children: [
              {
                errorElement: <RouteErrorBoundary />,
                children: [...screenRoutes, { path: '*', element: <NotFoundPage /> }],
              },
            ],
          },
        ],
      },
    ],
  },
]
