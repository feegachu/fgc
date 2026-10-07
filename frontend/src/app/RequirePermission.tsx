import type { ReactNode } from 'react'
import type { Permission } from '../features/auth/types'
import { useAuth } from '../features/auth/useAuth'
import { ForbiddenPage } from './ForbiddenPage'

export function RequirePermission({ permission, children }: { permission?: Permission; children: ReactNode }) {
  const { user } = useAuth()
  return permission && user?.[permission] !== true ? <ForbiddenPage /> : children
}
