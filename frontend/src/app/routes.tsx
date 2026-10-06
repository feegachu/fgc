import type { RouteObject } from 'react-router'
import { AppShell } from './shell/AppShell'
import { ApplicationRoot } from './ApplicationRoot'
import { screens } from './screens'
import { TransitionPage, LoginPlaceholder } from './TransitionPage'
import { NotFoundPage } from './NotFoundPage'
export const screenRoutes: RouteObject[] = screens.map((screen) => ({
  path: screen.path,
  element: <TransitionPage screen={screen} />,
  handle: { screenId: screen.id, title: screen.title, parentMenu: screen.menu, permission: screen.permission },
}))
export const routes: RouteObject[] = [
  {
    element: <ApplicationRoot />,
    children: [
      { path: '/login', element: <LoginPlaceholder />, handle: { screenId: 'AUTH-W01', title: '로그인' } },
      { element: <AppShell />, children: [...screenRoutes, { path: '*', element: <NotFoundPage /> }] },
    ],
  },
]
