import { SettlementGuide } from '../../features/assistant/settlement/SettlementGuide'
import { lazy, Suspense, useEffect, useState } from 'react'
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
import { DraftNavigationGuard } from './DraftNavigationGuard'
import { AgentTopbar } from './AgentTopbar'
import { HelpComparison } from '../../features/assistant/guide/HelpComparison'
import type { HelpVersion } from '../../features/assistant/guide/HelpComparison'
import { Modal } from '../../components/Modal'
import { Button } from '../../components/Button'
import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import './kpi-designs.css'
import './agent-theme.css'
import './app-theme.css'
const AssistantDemoMount = lazy(() => import('../../features/assistant/PicoAssistant').then((module) => ({ default: module.AssistantDemoMount })))
const WorkspaceGuide = lazy(() => import('../../features/assistant/WorkspaceGuide').then((module) => ({ default: module.WorkspaceGuide })))

export interface ShellContext {
  user: ShellUser
  month: string | null
  revised?: boolean
  helpVersion?: HelpVersion
}
/** 내부 사용자에게 확정 관리자 UI를 적용하고 AGENT 전용 셸의 화면 범위를 유지한다. */
export function AppShellView({ user, defaultMonth }: { user: ShellUser; defaultMonth: string }) {
  const location = useLocation()
  const navigate = useNavigate()
  const current = screenFor(location.pathname)
  const [picoLauncherHost, setPicoLauncherHost] = useState<HTMLDivElement | null>(null)
  const month = useWorkspaceStore((state) => state.month)
  const tabs = useWorkspaceStore((state) => state.tabs)
  const collapsed = useSidebarStore((state) => state.collapsed)
  const revised = user.roleCode !== 'AGENT'
  const [helpVersion, setHelpVersion] = useState<HelpVersion>('after')
  const [pendingVersion, setPendingVersion] = useState<HelpVersion | null>(null)
  const changeVersion = (version: HelpVersion) => {
    if (version === helpVersion) return
    if (Object.keys(useUnsavedWorkStore.getState().owners).length) setPendingVersion(version)
    else setHelpVersion(version)
  }
  const errorTitle = useErrorTitleStore((state) => state.title)
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
      errorTitle ?? (!current ? '찾을 수 없음, FGC' : allowed ? `${current.title} | FGC` : '권한 없음, FGC')
  }, [errorTitle, current, allowed])
  return (
    <div
      className={`app-shell app-theme is-sidebar-ready ${collapsed ? 'is-sidebar-collapsed' : ''} ${user.roleCode === 'AGENT' ? 'is-agent' : ''}`}
      data-kpi-style="summary"
      data-ui-revision={revised ? 'after' : undefined}
    >
      <a data-guide-feature="app.shell.app-shell.a" className="skip-link" href="#main-content">
        본문으로 건너뛰기
      </a>
      <div className="app-shell-layout">
        <Sidebar
          user={user}
          current={current}
          hrefFor={(path) => (month ? withMonth(path, month) : path)}
          onLogout={() => void logout()}
          loggingOut={loggingOut}
          helpComparison={revised && <HelpComparison version={helpVersion} onChange={changeVersion} label="화면 비교" />}
        />
        <header className="app-header app-header-rail">
          {user.roleCode === 'AGENT' && current?.id === 'AGNT-W01' ? (
            <AgentTopbar />
          ) : (
            <WorkspaceTabs activeId={current?.id} />
          )}
          <div className="app-header-actions">
            {current?.id === 'SCHE-W02' && <div ref={setPicoLauncherHost} />}
            <div className="app-header-field">
              <span className="app-header-field-label">기준 정산월</span>
              {month && (
                <MonthSelector
                  value={month}
                  openTabCount={tabs.length}
                  onApply={(next) => {
                    return navigate(withMonth(location.pathname + location.search + location.hash, next))
                  }}
                />
              )}
            </div>
          </div>
        </header>
        <main id="main-content" data-guide-page={current?.id} data-guide-pathname={location.pathname} className="app-main" tabIndex={-1}>
          <div className="page-content">
            <Outlet key={helpVersion} context={{ user, month, revised, helpVersion } satisfies ShellContext} />
          </div>
        </main>
        <SettlementGuide user={user} month={month} helpVersion={helpVersion} />
        {!location.pathname.startsWith('/agent-home') && (
          <footer className="app-footer">
            {revised ? (
              <>
                <p className="app-footer-company">
                  <span>FGC</span><span>팀 수수깡</span><span>사업자등록번호: 000-00-00000 (가상)</span>
                </p>
                <p>Copyright © {new Date().getFullYear()} FGC. All rights reserved.</p>
              </>
            ) : 'FGC 수수료 정산 검증 시스템 v2.0'}
          </footer>
        )}
      </div>
      {(import.meta.env.VITE_ASSISTANT_AI === 'true' || import.meta.env.VITE_ASSISTANT_DEMO === 'true') && (
        <Suspense fallback={null}>
          <AssistantDemoMount user={user} loggingOut={loggingOut} screenId={current?.id} screenContext={location.pathname + location.search} launcherHost={current?.id === 'SCHE-W02' ? picoLauncherHost : null} />
          {!loggingOut && import.meta.env.VITE_ASSISTANT_AI === 'true' && <WorkspaceGuide user={user} />}
        </Suspense>
      )}
      <DraftNavigationGuard />
      <Modal open={pendingVersion != null} title="화면을 전환할까요?" onClose={() => setPendingVersion(null)}
        footer={<><Button variant="secondary" onClick={() => setPendingVersion(null)}>계속 작성</Button><Button onClick={() => {
          if (pendingVersion) setHelpVersion(pendingVersion)
          setPendingVersion(null)
        }}>전환</Button></>}>
        <p>저장하지 않은 입력이 있습니다. 수정 전·후 화면을 전환하면 작성 중인 내용이 초기화됩니다.</p>
      </Modal>
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
