import { beforeEach, expect, it } from 'vitest'
import { createWorkspaceStore, validMonth } from './workspace'
import { screens } from '../app/screens'
beforeEach(() => sessionStorage.clear())
const permissions = { canProcess: true, canViewAuditLog: true }
it('11개 업무 탭은 최대 10개로 제한하고 대시보드를 고정·복원한다', () => {
  const store = createWorkspaceStore()
  store.getState().initialize('settle01', '2026-07', null, permissions)
  for (const screen of screens.filter((screen) => !screen.path.includes(':')).slice(0, 12))
    store.getState().openTab({ id: screen.id, title: screen.title, icon: screen.icon, href: screen.path })
  expect(store.getState().tabs).toHaveLength(10)
  expect(store.getState().tabs[0].id).toBe('DASH-W01')
  store.getState().markModified('ARB-W01')
  const restored = createWorkspaceStore()
  restored.getState().initialize('settle01', '2026-08', null, permissions)
  expect(restored.getState().tabs).toHaveLength(10)
  expect(restored.getState().tabs.find((tab) => tab.id === 'ARB-W01')?.modified).toBe(true)
  expect(restored.getState().month).toBe('2026-07')
  expect(restored.getState().closeTab('DASH-W01', 'DASH-W01')).toBeNull()
})
it('기준월은 3개 탭의 URL에 반영하고 page만 지운다', () => {
  const store = createWorkspaceStore()
  store.getState().initialize('settle01', '2026-07', null, permissions)
  store
    .getState()
    .openTab({
      id: 'CONT-W01',
      title: '보험계약',
      icon: 'description',
      href: '/contracts?month=2026-07&page=3&status=ACTIVE',
    })
  store
    .getState()
    .openTab({
      id: 'ARB-W01',
      title: '차익거래 검증',
      icon: 'fact_check',
      href: '/arbitrage-checks?page=2&status=CANDIDATE#detail',
    })
  store.getState().applyMonth('2026-08')
  expect(
    store.getState().tabs.every((tab) => new URL(tab.href, 'http://test').searchParams.get('month') === '2026-08'),
  ).toBe(true)
  expect(store.getState().tabs.every((tab) => !new URL(tab.href, 'http://test').searchParams.has('page'))).toBe(true)
  expect(store.getState().tabs[1].href).toContain('status=ACTIVE')
  expect(store.getState().tabs[2].href).toContain('status=CANDIDATE')
  expect(store.getState().tabs[2].href).toContain('#detail')
})
it('닫은 활성 탭은 오른쪽, 없으면 왼쪽 탭으로 이동한다', () => {
  const store = createWorkspaceStore()
  store.getState().initialize('u', '2026-07', null, permissions)
  for (const screen of screens.filter((screen) => ['/contracts', '/policies'].includes(screen.path)))
    store.getState().openTab({ ...screen, href: screen.path })
  expect(store.getState().closeTab('POL-W01', 'POL-W01')).toBe('/contracts')
  expect(store.getState().closeTab('CONT-W01', 'CONT-W01')).toBe('/?month=2026-07')
})
it('변조·다른 사용자 저장 상태를 배제하고 URL 월을 우선한다', () => {
  sessionStorage.setItem(
    'fgc.react.workspace.v1',
    JSON.stringify({
      owner: 'u',
      month: '2026-07',
      tabs: [
        { href: '//evil.test' },
        { href: '/audit-logs', modified: true },
        { href: '/contracts?status=ACTIVE&page=2', modified: true },
      ],
    }),
  )
  const store = createWorkspaceStore()
  store.getState().initialize('u', '2026-06', '2026-08', { canProcess: false, canViewAuditLog: false })
  expect(store.getState().tabs).toHaveLength(2)
  expect(store.getState().tabs[1].href).toBe('/contracts?status=ACTIVE&month=2026-08')
  const other = createWorkspaceStore()
  other.getState().initialize('different', '2026-06', null, permissions)
  expect(other.getState().tabs).toHaveLength(1)
  expect(other.getState().month).toBe('2026-06')
  expect(validMonth('0000-01')).toBe(false)
  expect(validMonth('2026-13')).toBe(false)
})
