import { matchPath } from 'react-router'
import { screens, screenFor } from '../../app/screens'
import { findGuide, GUIDE_CATALOG } from './catalog'
import { findWorkflowGuide, WORKFLOW_GUIDES } from './workflow-catalog'

/** AI guide IDs identify destinations only. The destination page owns its entire help flow. */
export const PAGE_HELP_TARGETS = [
  ...WORKFLOW_GUIDES.map(guide => ({ workflowId: guide.workflowId, route: guide.route, screenId: guide.steps[0].screenId })),
  ...GUIDE_CATALOG.filter(guide => guide.enabled).map(guide => ({ workflowId: guide.workflowId, route: '/transactions/new', screenId: 'TRAN-W02' })),
]

export function pageHelpDestination(workflowId: string, pathname: string, search: string, month: string | null) {
  const destination = PAGE_HELP_TARGETS.find(entry => entry.workflowId === workflowId)
  if (!destination || !findWorkflowGuide(workflowId, '1.0') && !findGuide(workflowId, '1.0')) return null
  const current = screenFor(pathname)
  const target = screens.find(screen => screen.path === destination.route)
  if (!target) return null
  const selected = current?.menu === target.menu ? matchPath({ path: current.path, end: true }, pathname)?.params.id : undefined
  const route = destination.route.includes(':id') ? selected ? destination.route.replace(':id', encodeURIComponent(selected)) : target.menu : destination.route
  const query = route === pathname ? new URLSearchParams(search) : new URLSearchParams()
  if (month) query.set('month', month)
  return { pathname: route, screen: screenFor(route)!, href: route + (query.size ? '?' + query : '') }
}
