import type { ReactNode } from 'react'
import type { Permission } from '../features/auth/types'
import { useAuth } from '../features/auth/useAuth'
import { ForbiddenPage } from '../features/error/ErrorPages'
import { canOpen } from './screens'
import type { ScreenDefinition } from './screens'

export function RequirePermission({ permission, screen, children }: { permission?: Permission; screen?: ScreenDefinition; children: ReactNode }) {
  const { user } = useAuth()
  return (permission && user?.[permission] !== true) || (screen && user && !canOpen(screen, user)) ? <ForbiddenPage /> : children
}
