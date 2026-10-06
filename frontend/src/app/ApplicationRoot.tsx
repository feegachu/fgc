import { Outlet } from 'react-router'
import { ToastRegion } from '../components/Toast'
export function ApplicationRoot() {
  return (
    <>
      <Outlet />
      <ToastRegion />
    </>
  )
}
