import { matchPath } from 'react-router'
import type { Permission } from '../features/auth/types'
export type { Permission } from '../features/auth/types'
export interface ScreenDefinition {
  id: string
  path: string
  title: string
  menu: string
  section?: string
  icon: string
  permission?: Permission
  legacy: string
}
export const screens: ScreenDefinition[] = [
  { id: 'DASH-W01', path: '/', title: '업무 대시보드', menu: '/', icon: 'dashboard', legacy: '/' },
  {
    id: 'BASE-W01',
    path: '/base',
    title: '기준정보',
    menu: '/base',
    section: 'admin',
    icon: 'settings',
    legacy: '/base',
  },
  {
    id: 'POL-W01',
    path: '/policies',
    title: '정책·룰셋',
    menu: '/policies',
    section: 'admin',
    icon: 'settings',
    legacy: '/policies',
  },
  {
    id: 'CONT-W03',
    path: '/contracts/new',
    title: '계약 등록',
    menu: '/contracts',
    section: 'contracts',
    icon: 'description',
    permission: 'canProcess',
    legacy: '/contracts/new',
  },
  {
    id: 'CONT-W03',
    path: '/contracts/:id/edit',
    title: '계약 수정',
    menu: '/contracts',
    section: 'contracts',
    icon: 'description',
    permission: 'canProcess',
    legacy: '/contracts/:id/edit',
  },
  {
    id: 'CONT-W02',
    path: '/contracts/:id',
    title: '계약 상세',
    menu: '/contracts',
    section: 'contracts',
    icon: 'description',
    legacy: '/contracts/:id',
  },
  {
    id: 'CONT-W01',
    path: '/contracts',
    title: '보험계약',
    menu: '/contracts',
    section: 'contracts',
    icon: 'description',
    legacy: '/contracts',
  },
  {
    id: 'TRAN-W02',
    path: '/transactions/new',
    title: '수수료 지급 등록',
    menu: '/transactions',
    section: 'contracts',
    icon: 'payments',
    permission: 'canProcess',
    legacy: '/transactions/new',
  },
  {
    id: 'TRAN-W01',
    path: '/transactions',
    title: '수수료 지급',
    menu: '/transactions',
    section: 'contracts',
    icon: 'payments',
    legacy: '/transactions',
  },
  {
    id: 'SCHE-W02',
    path: '/schedules/:id',
    title: '예상 스케줄 상세',
    menu: '/schedules',
    section: 'contracts',
    icon: 'description',
    legacy: '/schedules/:id',
  },
  {
    id: 'SCHE-W01',
    path: '/schedules',
    title: '예상 스케줄',
    menu: '/schedules',
    section: 'contracts',
    icon: 'description',
    legacy: '/schedules',
  },
  {
    id: 'CAP-W01',
    path: '/cap-checks',
    title: '1,200% 한도 검증',
    menu: '/cap-checks',
    section: 'compliance',
    icon: 'fact_check',
    legacy: '/cap-checks',
  },
  {
    id: 'ARB-W01',
    path: '/arbitrage-checks',
    title: '차익거래 검증',
    menu: '/arbitrage-checks',
    section: 'compliance',
    icon: 'fact_check',
    legacy: '/arbitrage-checks',
  },
  {
    id: 'LEDG-W01',
    path: '/journals',
    title: '검증원장',
    menu: '/journals',
    section: 'ledger',
    icon: 'menu_book',
    legacy: '/journals',
  },
  {
    id: 'RECO-W01',
    path: '/reconciliations',
    title: '대사 실행·결과',
    menu: '/reconciliations',
    section: 'ledger',
    icon: 'menu_book',
    legacy: '/reconciliations',
  },
  { id: 'EXCP-W01', path: '/exceptions', title: '예외', menu: '/exceptions', icon: 'inbox', legacy: '/exceptions' },
  {
    id: 'VRUN-W02',
    path: '/validation-runs/:id',
    title: '월 통합검증 상세',
    menu: '/validation-runs',
    section: 'compliance',
    icon: 'fact_check',
    legacy: '/validation-runs/:id',
  },
  {
    id: 'VRUN-W01',
    path: '/validation-runs',
    title: '월 통합검증',
    menu: '/validation-runs',
    section: 'compliance',
    icon: 'fact_check',
    legacy: '/validation-runs',
  },
  {
    id: 'AUDT-W01',
    path: '/audit-logs',
    title: '감사로그',
    menu: '/audit-logs',
    section: 'admin',
    icon: 'settings',
    permission: 'canViewAuditLog',
    legacy: '/audit-logs',
  },
]
export const screenFor = (pathname: string) =>
  screens.find((screen) => matchPath({ path: screen.path, end: true }, pathname))
// 화면 권한 판정은 여기 한 곳에서 한다. 권한 종류가 늘어도 호출부는 그대로다.
export const canOpen = (screen: ScreenDefinition, user: Partial<Record<Permission, boolean>>) =>
  !screen.permission || user[screen.permission] === true
export function legacyHref(screen: ScreenDefinition, pathname: string, search: string) {
  const params = matchPath(screen.path, pathname)?.params ?? {}
  return screen.legacy.replace(/:([a-z]+)/g, (_, key: string) => encodeURIComponent(params[key] ?? '')) + search
}
