import { Link, useLocation } from 'react-router'
import { AppShellView } from '../app/shell/AppShell'
import { designPreviewUser } from './designFixtures'

export function PreviewNavigation() {
  return (
    <nav className="design-preview-navigation" aria-label="KPI 화면 미리보기">
      {[
        ['/', '대시보드'],
        ['/cap-checks', '한도 검증'],
        ['/arbitrage-checks', '차익거래'],
        ['/exceptions', '예외함'],
        ['/reconciliations?runId=41', '대사'],
        ['/validation-runs/1', '통합검증'],
        ['/agent-home', '설계사'],
      ].map(([to, name]) => (
        <Link key={to} to={to}>
          {name}
        </Link>
      ))}
    </nav>
  )
}

export function DesignPreviewShell() {
  const { pathname } = useLocation()
  return (
    <AppShellView
      user={{ ...designPreviewUser, roleCode: pathname === '/agent-home' ? 'AGENT' : 'SETTLEMENT' }}
      defaultMonth="2026-07"
    />
  )
}
