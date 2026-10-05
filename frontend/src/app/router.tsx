import { createBrowserRouter, type RouteObject } from 'react-router'
import { HomePage } from './HomePage'
import { NotFoundPage } from './NotFoundPage'

// 화면 라우트는 인터페이스정의서 §5-2 "2차 React 경로" 열을 따라 화면 이슈에서 추가한다.
export const routes: RouteObject[] = [
  { path: '/', element: <HomePage /> },
  { path: '*', element: <NotFoundPage /> },
]

// basename 은 vite.config.ts 의 base('/app/')와 같은 값이다.
export const router = createBrowserRouter(routes, { basename: import.meta.env.BASE_URL })
