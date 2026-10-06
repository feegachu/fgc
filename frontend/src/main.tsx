import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import { queryClient } from './app/queryClient'
import { router } from './app/router'
import './index.css'
import './styles/variables.css'
import './styles/reset.css'
import './styles/layout.css'
import './styles/components.css'
import './styles/utilities.css'
import './styles/react.css'
import { apiClient } from './lib/api/client'

// Restore before mounting queries; #404 owns the login page and route guards.
const ready = apiClient.isLoginPage() || await apiClient.restoreSession().then(() => true, () => false)

if (ready) createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
