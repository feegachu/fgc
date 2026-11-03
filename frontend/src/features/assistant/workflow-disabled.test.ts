import { afterEach, expect, it, vi } from 'vitest'
import definitions from './workflow-guides.v1.json'

afterEach(() => { vi.doUnmock('./workflow-guides.v1.json'); vi.resetModules() })

it('does not expose disabled tutorials or fail module loading when stale help remains', async () => {
  vi.resetModules()
  vi.doMock('./workflow-guides.v1.json', () => ({ default: definitions.map((guide) =>
    guide.workflowId === 'settlement.journey' ? { ...guide, enabled: false } : guide) }))
  const catalog = await import('./workflow-catalog')
  expect(catalog.findWorkflowGuide('settlement.journey', '1.0')).toBeNull()
  expect(catalog.workflowHelpSteps('settlement.journey')).toEqual([])
  expect(catalog.workflowSteps({ ...definitions.find((guide) => guide.workflowId === 'settlement.journey')!, enabled: false } as import('./workflow-catalog').WorkflowGuide)).toEqual([])
  expect(catalog.findWorkflowGuide('contract.find', '1.0')).not.toBeNull()
})
