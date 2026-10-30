import { useEffect } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { useWorkspaceStore, validMonth, withMonth } from '../../stores/workspace'
import { useSidebarStore } from '../../stores/sidebar'
import { useErrorTitleStore } from '../../stores/errorTitle'
import { MonthSelector } from '../../components/MonthSelector'
import { canOpen, screenFor } from '../screens'
import { Sidebar } from './Sidebar'
import type { ShellUser } from './Sidebar'
import { WorkspaceTabs } from './WorkspaceTabs'
import { useAuth } from '../../features/auth/useAuth'
import { useLogout } from '../../features/auth/useLogout'
import { AuthStatus } from '../../features/auth/AuthStatus'
import { AgentTopbar } from './AgentTopbar'
import { DeveloperSettings } from './DeveloperSettings'
import { resolveComponentDesign, sidebarAppearance, useDesignSettings } from '../../stores/designSettings'
import './component-designs.css'
import './agent-theme.css'
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
  const errorTitle = useErrorTitleStore((state) => state.title)
  const preferences = useDesignSettings((state) => state.preferences)
  const appearance = sidebarAppearance(preferences, user.roleCode === 'AGENT')
  const { loggingOut, logout } = useLogout()
  const allowed = current && canOpen(current, user)
  useEffect(() => {
    const workspace = useWorkspaceStore.getState()
    const requestedMonth = new URLSearchParams(location.search).get('month')
    workspace.initialize(user.loginId, defaultMonth, requestedMonth, {
      canProcess: user.canProcess,
      canViewAuditLog: user.canViewAuditLog,
      isAgent: user.roleCode === 'AGENT',
    })
    // 포트폴리오 전환과 이 셸의 월 URL 정규화가 서로 덮어쓰지 않게 한다.
    if (user.roleCode === 'AGENT' && location.pathname === '/') return
    if (validMonth(requestedMonth) && requestedMonth !== useWorkspaceStore.getState().month)
      workspace.applyMonth(requestedMonth)
    const activeMonth = useWorkspaceStore.getState().month!
    const href = location.pathname + location.search + location.hash
    // Adding the server default month must not discard a page from an existing deep link.
    const normalized = withMonth(href, activeMonth, requestedMonth !== null && requestedMonth !== activeMonth)
    if (href !== normalized) {
      void navigate(normalized, { replace: true })
      return
    }
    if (allowed) workspace.openTab({ id: current.id, title: current.title, icon: current.icon, href: normalized })
  }, [
    location.pathname,
    location.search,
    location.hash,
    current,
    allowed,
    user.loginId,
    user.canProcess,
    user.canViewAuditLog,
    user.roleCode,
    defaultMonth,
    navigate,
  ])
  // 1차 화면 제목(layout/default의 "제목 (화면ID) · FGC")과 맞춘다. 에러 바운더리가 그린 오류 화면이 있으면 그 제목이 먼저다.
  useEffect(() => {
    document.title =
      errorTitle ??
      (!current
        ? '찾을 수 없음 (FGC-UI-ERR-404) · FGC'
        : allowed
          ? `${current.title} (${current.id}) · FGC`
          : '권한 없음 (FGC-UI-ERR-403) · FGC')
  }, [errorTitle, current, allowed])
  return (
    <div
      className={`app-shell is-sidebar-ready ${collapsed ? 'is-sidebar-collapsed' : ''} ${user.roleCode === 'AGENT' ? 'is-agent' : ''}`}
      data-sidebar-design={import.meta.env.DEV ? preferences.sidebarDesign : undefined}
      data-sidebar-dark-text={import.meta.env.DEV ? appearance.darkText : undefined}
      data-sidebar-custom-color={import.meta.env.DEV ? preferences.sidebarColor !== null : undefined}
      data-kpi-style={import.meta.env.DEV ? preferences.kpiStyle : undefined}
      data-component-design={import.meta.env.DEV ? preferences.componentDesign : undefined}
      data-button-design={import.meta.env.DEV ? resolveComponentDesign(preferences, 'buttonDesign') : undefined}
      data-field-design={import.meta.env.DEV ? resolveComponentDesign(preferences, 'fieldDesign') : undefined}
      data-table-design={import.meta.env.DEV ? resolveComponentDesign(preferences, 'tableDesign') : undefined}
      data-badge-design={import.meta.env.DEV ? resolveComponentDesign(preferences, 'badgeDesign') : undefined}
      style={import.meta.env.DEV ? appearance.style : undefined}
    >
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
          {user.roleCode === 'AGENT' && current?.id === 'AGNT-W01' ? <AgentTopbar /> : <WorkspaceTabs activeId={current?.id} />}
          <div className="app-header-actions">
            {import.meta.env.DEV && <DeveloperSettings sidebarColor={appearance.color} />}
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
  if (!auth.user)
    return (
      <AuthStatus
        error={auth.error}
        retry={() => {
          void auth.refetch()
        }}
      />
    )
  return <AppShellView user={auth.user} defaultMonth={auth.user.demoMonth} />
}
