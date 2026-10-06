import { useEffect } from 'react'
import { Link } from 'react-router'
import { useSidebarStore } from '../../stores/sidebar'
import { screens } from '../screens'
import type { ScreenDefinition } from '../screens'
export interface ShellUser { loginId: string; userName: string; roleCode: string; canProcess: boolean; canViewAuditLog: boolean }
import { roleLabels } from './labels'
const groups = [
  { id: 'contracts', title: '계약·지급', icon: 'payments', paths: ['/contracts', '/transactions', '/schedules'] },
  { id: 'compliance', title: '규제 검증', icon: 'fact_check', paths: ['/cap-checks', '/arbitrage-checks', '/validation-runs'] },
  { id: 'ledger', title: '원장·대사', icon: 'menu_book', paths: ['/journals', '/reconciliations'] },
  { id: 'admin', title: '관리', icon: 'settings', paths: ['/base', '/policies', '/audit-logs'] },
]
export function Sidebar({ user, current, hrefFor, onLogout, loggingOut }: { user: ShellUser; current?: ScreenDefinition; hrefFor: (path: string) => string; onLogout: () => void; loggingOut: boolean }) {
  const collapsed = useSidebarStore((state) => state.collapsed)
  const openSections = useSidebarStore((state) => state.openSections)
  const setCollapsed = useSidebarStore((state) => state.setCollapsed)
  const setSection = useSidebarStore((state) => state.setSection)
  useEffect(() => { if (current?.section) setSection(current.section, true) }, [current?.section, setSection])
  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 79.9375rem)')
    if (!media) return
    const synchronize = () => { try { if (localStorage.getItem('fgc.sidebar.collapsed.v1') === null) setCollapsed(media.matches, false) } catch { setCollapsed(media.matches, false) } }
    media.addEventListener('change', synchronize)
    return () => media.removeEventListener('change', synchronize)
  }, [setCollapsed])
  function link(path: string, sub = false) {
    const definition = screens.find((screen) => screen.path === path)!
    if (definition.permission === 'canViewAuditLog' && !user.canViewAuditLog) return null
    return <li key={path}><Link className={`${sub ? 'sidebar-subnav-link' : 'sidebar-nav-link'} ${current?.menu === path ? 'is-active' : ''}`} to={hrefFor(path)} aria-current={current?.menu === path ? 'page' : undefined}>{!sub && <span className="material-symbols-rounded" aria-hidden="true">{definition.icon}</span>}<span className={sub ? undefined : 'sidebar-nav-label'}>{definition.title}</span></Link></li>
  }
  return <aside id="app-sidebar" className="app-sidebar"><div className="sidebar-brand"><Link className="sidebar-brand-link sidebar-brand-home" to={hrefFor('/')} aria-label="FGC 업무 대시보드"><img src={`${import.meta.env.BASE_URL}images/brand/logo-horizontal-white.png`} className="sidebar-brand-horizontal" width={114} height={31} alt="FGC" /></Link><button className="sidebar-expand-button" type="button" aria-label="사이드바 펼치기" aria-expanded={!collapsed} aria-controls="app-sidebar" onClick={() => setCollapsed(false)}><img src={`${import.meta.env.BASE_URL}images/brand/logo-symbol-white.png`} width={48} height={48} alt="" /></button><button className="icon-button sidebar-collapse-button" type="button" aria-label="사이드바 접기" aria-expanded={!collapsed} aria-controls="app-sidebar" onClick={() => setCollapsed(true)}><span className="material-symbols-rounded" aria-hidden="true">keyboard_double_arrow_left</span></button></div><nav className="sidebar-nav" aria-label="주요 업무 메뉴"><ul className="sidebar-nav-list">{link('/')}{groups.slice(0, 3).map((group) => <li key={group.id}><details className="sidebar-nav-details" open={openSections.includes(group.id)}><summary className="sidebar-nav-summary" onClick={(event) => { event.preventDefault(); if (collapsed) setCollapsed(false); setSection(group.id, collapsed || !openSections.includes(group.id)) }}><span className="material-symbols-rounded" aria-hidden="true">{group.icon}</span><span className="sidebar-nav-label">{group.title}</span><span /><span className="material-symbols-rounded sidebar-nav-chevron" aria-hidden="true">chevron_right</span></summary><ul className="sidebar-subnav">{group.paths.map((path) => link(path, true))}</ul></details></li>)}{link('/exceptions')}<li className="sidebar-nav-section"><details className="sidebar-nav-details" open={openSections.includes('admin')}><summary className="sidebar-nav-summary" onClick={(event) => { event.preventDefault(); if (collapsed) setCollapsed(false); setSection('admin', collapsed || !openSections.includes('admin')) }}><span className="material-symbols-rounded" aria-hidden="true">settings</span><span className="sidebar-nav-label">관리</span><span /><span className="material-symbols-rounded sidebar-nav-chevron" aria-hidden="true">chevron_right</span></summary><ul className="sidebar-subnav">{groups[3].paths.map((path) => link(path, true))}</ul></details></li></ul></nav><div className="sidebar-user-panel"><span className="sidebar-user-avatar material-symbols-rounded" aria-hidden="true">person</span><div className="sidebar-user-copy"><p className="sidebar-user-name">{user.userName}</p><p className="sidebar-user-role">{roleLabels[user.roleCode] ?? user.roleCode}</p></div><button className="sidebar-logout-button" type="button" aria-label="로그아웃" disabled={loggingOut} onClick={onLogout}><span className="material-symbols-rounded" aria-hidden="true">logout</span></button></div></aside>
}
