import { create } from 'zustand'
import { screenFor, screens } from '../app/screens'
export interface WorkspaceTab { id: string; title: string; href: string; icon: string; modified: boolean }
interface WorkspaceState {
  tabs: WorkspaceTab[]
  month: string | null
  owner: string | null
  initialize: (owner: string, defaultMonth: string, urlMonth: string | null, permissions: Record<string, boolean>) => void
  openTab: (tab: Omit<WorkspaceTab, 'modified'>) => void
  closeTab: (id: string, activeId: string) => string | null
  markModified: (id: string, modified?: boolean) => void
  applyMonth: (month: string) => void
  clear: () => void
}
export const validMonth = (value: unknown): value is string => typeof value === 'string' && /^(?!0000)\d{4}-(0[1-9]|1[0-2])$/.test(value)
export function withMonth(href: string, month: string, resetPage = true) {
  const url = new URL(href, 'http://workspace.local')
  url.searchParams.set('month', month)
  if (resetPage) url.searchParams.delete('page')
  return url.pathname + url.search + url.hash
}
const dashboard: WorkspaceTab = { id: 'DASH-W01', title: '업무 대시보드', href: '/', icon: 'dashboard', modified: false }
export function createWorkspaceStore(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null = sessionStorage) {
  const key = 'fgc.react.workspace.v1'
  let owner: string | null = null
  function save(tabs: WorkspaceTab[], month: string | null) { try { storage?.setItem(key, JSON.stringify({ owner, tabs, month })) } catch { /* Navigation works when storage is unavailable. */ } }
  return create<WorkspaceState>((set, get) => ({
    tabs: [{ ...dashboard }], month: null, owner: null,
    initialize: (user, defaultMonth, urlMonth, permissions) => {
      if (!validMonth(defaultMonth)) throw new Error('서버 기준 정산월을 확인할 수 없습니다.')
      if (get().owner === user) return
      owner = user
      let saved: { owner?: string; tabs?: unknown; month?: unknown } = {}
      try { saved = JSON.parse(storage?.getItem(key) ?? '{}') ?? {} } catch { /* Ignore invalid storage. */ }
      const month = validMonth(urlMonth) ? urlMonth : saved.owner === user && validMonth(saved.month) ? saved.month : defaultMonth
      const tabs: WorkspaceTab[] = [{ ...dashboard, href: withMonth('/', month) }]
      if (saved.owner === user && Array.isArray(saved.tabs)) {
        for (const candidate of saved.tabs) {
          if (!candidate || typeof candidate.href !== 'string' || !candidate.href.startsWith('/') || candidate.href.startsWith('//') || candidate.href.includes('\\')) continue
          const route = screenFor(new URL(candidate.href, 'http://workspace.local').pathname)
          if (!route || route.id === dashboard.id || (route.permission && !permissions[route.permission]) || tabs.some((tab) => tab.id === route.id)) continue
          tabs.push({ id: route.id, title: route.title, icon: route.icon, href: withMonth(candidate.href, month, validMonth(urlMonth) && urlMonth !== saved.month), modified: candidate.modified === true })
        }
      }
      const bounded = [tabs[0], ...tabs.slice(1).slice(-9)]
      set({ owner, month, tabs: bounded }); save(bounded, month)
    },
    openTab: (tab) => {
      if (!screens.some((screen) => screen.id === tab.id)) return
      let tabs = get().tabs.map((item) => item.id === tab.id ? { ...tab, modified: item.modified } : item)
      if (!tabs.some((item) => item.id === tab.id)) tabs = [...tabs, { ...tab, modified: false }]
      if (tabs.length > 10) tabs.splice(tabs.findIndex((item) => item.id !== dashboard.id), 1)
      set({ tabs }); save(tabs, get().month)
    },
    closeTab: (id, activeId) => {
      if (id === dashboard.id) return null
      const index = get().tabs.findIndex((tab) => tab.id === id)
      if (index < 0) return null
      const tabs = get().tabs.filter((tab) => tab.id !== id)
      set({ tabs }); save(tabs, get().month)
      return id === activeId ? tabs[Math.min(index, tabs.length - 1)].href : null
    },
    markModified: (id, modified = true) => { const tabs = get().tabs.map((tab) => tab.id === id ? { ...tab, modified } : tab); set({ tabs }); save(tabs, get().month) },
    applyMonth: (month) => {
      if (!validMonth(month)) throw new Error('기준 정산월 형식이 올바르지 않습니다.')
      const tabs = get().tabs.map((tab) => ({ ...tab, href: withMonth(tab.href, month) }))
      set({ month, tabs }); save(tabs, month)
    },
    clear: () => { owner = null; set({ owner: null, month: null, tabs: [{ ...dashboard }] }); try { storage?.removeItem(key) } catch { /* No persistent state. */ } },
  }))
}
export const useWorkspaceStore = createWorkspaceStore()
