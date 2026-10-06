import { create } from 'zustand'
const collapseKey = 'fgc.sidebar.collapsed.v1'
const sectionKey = 'fgc.sidebar.open-sections.v1'
const sections = ['contracts', 'compliance', 'ledger', 'admin']
function readCollapsed() {
  try {
    const saved = localStorage.getItem(collapseKey)
    return saved === null ? (window.matchMedia?.('(max-width: 79.9375rem)').matches ?? false) : saved === 'true'
  } catch {
    return false
  }
}
function readSections(): string[] {
  try {
    const saved: unknown = JSON.parse(sessionStorage.getItem(sectionKey) ?? '[]')
    return Array.isArray(saved)
      ? saved.filter((item): item is string => typeof item === 'string' && sections.includes(item))
      : []
  } catch {
    return []
  }
}
export const useSidebarStore = create<{
  collapsed: boolean
  openSections: string[]
  setCollapsed: (collapsed: boolean, persist?: boolean) => void
  setSection: (section: string, open: boolean) => void
}>((set) => ({
  collapsed: readCollapsed(),
  openSections: readSections(),
  setCollapsed: (collapsed, persist = true) => {
    set({ collapsed })
    if (persist) {
      try {
        localStorage.setItem(collapseKey, String(collapsed))
      } catch {
        /* Optional preference. */
      }
    }
  },
  setSection: (section, open) =>
    set((state) => {
      const openSections = open
        ? [...new Set([...state.openSections, section])]
        : state.openSections.filter((item) => item !== section)
      try {
        sessionStorage.setItem(sectionKey, JSON.stringify(openSections))
      } catch {
        /* Optional preference. */
      }
      return { openSections }
    }),
}))
