import { Link, useLocation } from 'react-router'
import { AppShellView } from '../app/shell/AppShell'
import { designPreviewUser } from './designFixtures'

export function PreviewNavigation() {
  return (
    <nav className="design-preview-navigation" aria-label="KPI 화면 미리보기">
      {[
        ['/', '대시보드'],
        ['/contracts', '보험계약'],
        ['/transactions', '수수료 지급'],
        ['/schedules', '예상 스케줄'],
        ['/cap-checks', '한도 검증'],
        ['/arbitrage-checks', '차익거래'],
        ['/clawbacks', '환수 검토'],
        ['/exceptions', '예외함'],
        ['/reconciliations?runId=41', '대사'],
        ['/journals', '검증원장'],
        ['/base', '기준정보'],
        ['/audit-logs', '감사로그'],
        ['/validation-runs', '통합검증 목록'],
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
