import { useEffect } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { useWorkspaceStore, validMonth, withMonth } from '../../stores/workspace'
import { useSidebarStore } from '../../stores/sidebar'
import { MonthSelector } from '../../components/MonthSelector'
import { canOpen, screenFor } from '../screens'
import { Sidebar } from './Sidebar'
import { roleLabels } from './labels'
import type { ShellUser } from './Sidebar'
import { WorkspaceTabs } from './WorkspaceTabs'
import { useAuth } from '../../features/auth/useAuth'
import { useLogout } from '../../features/auth/useLogout'
import { AuthStatus } from '../../features/auth/AuthStatus'
export interface ShellContext {
  user: ShellUser
  month: string | null
}
export function AppShellView({ user, defaultMonth }: { user: ShellUser; defaultMonth: string }) {
  const location = useLocation()
  const navigate = useNavigate()
  const current = screenFor(location.pathname)
  const month = useWorkspaceStore((state) => state.month)
  const tabs = useWorkspaceStore((state) => state.tabs)
  const collapsed = useSidebarStore((state) => state.collapsed)
  const { loggingOut, logout } = useLogout()
  useEffect(() => {
    const workspace = useWorkspaceStore.getState()
    const requestedMonth = new URLSearchParams(location.search).get('month')
    workspace.initialize(user.loginId, defaultMonth, requestedMonth, {
      canProcess: user.canProcess,
      canViewAuditLog: user.canViewAuditLog,
    })
    if (validMonth(requestedMonth) && requestedMonth !== useWorkspaceStore.getState().month)
      workspace.applyMonth(requestedMonth)
    const activeMonth = useWorkspaceStore.getState().month!
    const href = location.pathname + location.search + location.hash
    const normalized = withMonth(href, activeMonth, requestedMonth !== activeMonth)
    if (href !== normalized) {
      void navigate(normalized, { replace: true })
      return
    }
    if (current && canOpen(current, { canProcess: user.canProcess, canViewAuditLog: user.canViewAuditLog }))
      workspace.openTab({ id: current.id, title: current.title, icon: current.icon, href: normalized })
    document.title = `${current?.title ?? '페이지를 찾을 수 없습니다'} (${current?.id ?? 'ERR-404'}) · FGC`
  }, [
    location.pathname,
    location.search,
    location.hash,
    current,
    user.loginId,
    user.canProcess,
    user.canViewAuditLog,
    defaultMonth,
    navigate,
  ])
  return (
    <div className={`app-shell is-sidebar-ready ${collapsed ? 'is-sidebar-collapsed' : ''}`}>
      <a className="skip-link" href="#main-content">
        본문으로 건너뛰기
      </a>
      <div className="app-shell-layout">
        <Sidebar
          user={user}
          current={current}
          hrefFor={(path) => (month ? withMonth(path, month) : path)}
          onLogout={() => void logout()}
          loggingOut={loggingOut}
        />
        <header className="app-header app-header-rail">
          <WorkspaceTabs activeId={current?.id} />
          <div className="app-header-actions">
            <span className="shell-user-name">
              {user.userName} · {roleLabels[user.roleCode] ?? user.roleCode}
            </span>
            <div className="app-header-field">
              <span className="app-header-field-label">기준 정산월</span>
              {month && (
                <MonthSelector
                  value={month}
                  openTabCount={tabs.length}
                  onApply={(next) => {
                    useWorkspaceStore.getState().applyMonth(next)
                    return navigate(withMonth(location.pathname + location.search + location.hash, next))
                  }}
                />
              )}
            </div>
          </div>
        </header>
        <main id="main-content" className="app-main" tabIndex={-1}>
          <div className="page-content">
            <Outlet context={{ user, month } satisfies ShellContext} />
          </div>
        </main>
        <footer className="app-footer">FGC v2.0 · 수수료 정산·검증 Workspace</footer>
      </div>
    </div>
  )
}
export function AppShell() {
  const auth = useAuth()
  if (!auth.user) return <AuthStatus error={auth.error} retry={() => { void auth.refetch() }} />
  return <AppShellView user={auth.user} defaultMonth={auth.user.demoMonth} />
}
