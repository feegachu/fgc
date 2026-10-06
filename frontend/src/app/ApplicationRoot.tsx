import { Outlet } from 'react-router'
import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ToastRegion } from '../components/Toast'
import { useAuthStore } from '../stores/auth'
import { clearSessionData } from '../features/auth/session'
export function ApplicationRoot() {
  const client = useQueryClient()
  useEffect(() => useAuthStore.subscribe((next, previous) => {
    if (previous.accessToken && !next.accessToken) clearSessionData(client)
  }), [client])
  return (
    <>
      <Outlet />
      <ToastRegion />
    </>
  )
}
