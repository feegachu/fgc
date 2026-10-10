import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { queryClient } from './app/queryClient'
import { router } from './app/router'
import './index.css'
import './styles/variables.css'
import './styles/reset.css'
import './styles/layout.css'
import './styles/components.css'
import './styles/utilities.css'
import './styles/react.css'
import './features/auth/login.css'
import './features/exception/exception.css'
import './features/dashboard/dashboard.css'
import './features/reference/reference.css'
import './features/error/error.css'
import { apiClient } from './lib/api/client'

// 로그인 URL을 새로 열어도 유효한 쿠키가 있으면 기존 인증을 복원한다.
// 로그아웃·중복 로그인 안내에서는 복원하지 않으며, 비회원의 로그인 화면은 리다이렉트하지 않는다.
const loginPage = apiClient.isLoginPage()
const search = new URLSearchParams(window.location.search)
const skipRestore = loginPage && (search.has('logout') || search.has('reason'))
const ready = skipRestore || (await apiClient.restoreSession({ redirectOnFailure: !loginPage }).then(
    () => true,
    () => loginPage,
  ))

if (ready)
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  )
