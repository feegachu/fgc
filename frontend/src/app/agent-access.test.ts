import { describe, expect, it } from 'vitest'
import { canOpen, screens } from './screens'
import { createWorkspaceStore } from '../stores/workspace'

describe('FUN-085 설계사 화면 범위', () => {
  it('포트폴리오·본인 계약 목록·상세와 지급 목록만 열고 내부 화면·수정 화면을 막는다', () => {
    const agent = { roleCode: 'AGENT', canProcess: false, canViewAuditLog: false }
    expect(screens.filter((screen) => canOpen(screen, agent)).map((screen) => screen.path)).toEqual([
      '/agent-home', '/contracts/:id', '/contracts', '/transactions',
    ])
    expect(canOpen(screens.find((screen) => screen.path === '/contracts/new')!, { ...agent, canProcess: true })).toBe(false)
  })

  it('저장된 관리자 탭을 복원하지 않고 포트폴리오를 첫 탭으로 고정한다', () => {
    const key = 'fgc.react.workspace.v1'
    const values = new Map<string, string>([[key, JSON.stringify({
      owner: 'agent01', month: '2026-07', tabs: [
        { href: '/' }, { href: '/journals' }, { href: '/audit-logs' },
        { href: '/contracts/42?tab=ledger' }, { href: '/transactions' },
      ],
    })]])
    const store = createWorkspaceStore({
      getItem: (name) => values.get(name) ?? null,
      setItem: (name, value) => { values.set(name, value) },
      removeItem: (name) => { values.delete(name) },
    })
    store.getState().initialize('agent01', '2026-07', null, { isAgent: true, canProcess: false, canViewAuditLog: false })
    expect(store.getState().tabs.map((tab) => tab.id)).toEqual(['AGNT-W01', 'CONT-W02', 'TRAN-W01'])
    expect(store.getState().closeTab('AGNT-W01', 'AGNT-W01')).toBeNull()
  })
})
