import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { apiClient } from '../../lib/api/client'
import { errorText } from '../../lib/format'
import { loginUrl } from '../../lib/api/redirect'
import { useWorkspaceStore, validMonth, withMonth } from '../../stores/workspace'
import { useSidebarStore } from '../../stores/sidebar'
import { toast } from '../../stores/toasts'
import { MonthSelector } from '../../components/MonthSelector'
import { Button } from '../../components/Button'
import { queryClient } from '../queryClient'
import { canOpen, screenFor } from '../screens'
import { Sidebar } from './Sidebar'
import { roleLabels } from './labels'
import type { ShellUser } from './Sidebar'
import { WorkspaceTabs } from './WorkspaceTabs'
import type { MeResponse } from '../../lib/api/client'
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
  const [loggingOut, setLoggingOut] = useState(false)
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
  async function logout() {
    setLoggingOut(true)
    queryClient.clear()
    try {
      await apiClient.logout()
      useWorkspaceStore.getState().clear()
      window.location.assign(loginUrl(window.location, import.meta.env.BASE_URL))
    } catch (error) {
      toast(errorText(error instanceof Error ? error : null), 'error')
      setLoggingOut(false)
    }
  }
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
  const me = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => (await apiClient.request<MeResponse>('/api/v1/auth/me')).data,
    staleTime: Infinity,
    // 실패는 아래 셸 오류 화면이 보여 준다. 전역 Toast 까지 띄우면 같은 오류가 두 번 나온다.
    meta: { errorToast: false },
  })
  if (me.isPending)
    return (
      <p className="shell-error" role="status">
        업무 화면을 준비하고 있습니다.
      </p>
    )
  if (me.isError)
    return (
      <div className="shell-error">
        <p role="alert">{errorText(me.error)}</p>
        <Button onClick={() => void me.refetch()}>다시 시도</Button>
      </div>
    )
  const data = me.data
  const defaultMonth = data.demoMonth
  if (!data.loginId || !data.userName || !data.roleCode || !validMonth(defaultMonth))
    return (
      <p className="shell-error" role="alert">
        사용자 정보와 서버 기준 정산월을 확인할 수 없습니다.
      </p>
    )
  return (
    <AppShellView
      user={{
        loginId: data.loginId,
        userName: data.userName,
        roleCode: data.roleCode,
        canProcess: data.canProcess === true,
        canViewAuditLog: data.canViewAuditLog === true,
      }}
      defaultMonth={defaultMonth}
    />
  )
}
