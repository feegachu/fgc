import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ReferenceTabs } from '../reference/ReferenceUI'
import { guideFeatureId } from './feature-ids'
import permissions from './feature-permissions.v1.json'
import features from './feature-targets.generated.json'
import { findWorkflowGuide, WORKFLOW_GUIDES, workflowHelpSteps, workflowSteps } from './workflow-catalog'

const guide = (id: string) => findWorkflowGuide(id, '1.0')!
const targets = (id: string, role: string) => workflowSteps(guide(id), role).map((step) => step.targetId)

describe('role-scoped tutorial and UI knowledge', () => {
  it('keeps read steps while excluding processing controls and closing every remaining edge', () => {
    for (const role of ['COMPLIANCE', 'GA_ADMIN', 'SETTLEMENT', 'SYSTEM_ADMIN', 'AGENT']) {
      for (const definition of WORKFLOW_GUIDES) {
        const steps = workflowSteps(definition, role)
        const ids = new Set(steps.map((step) => step.stepId))
        for (const step of steps) {
          expect(step.roles).toContain(role)
          for (const edge of step.next) expect(ids.has(edge.stepId)).toBe(true)
        }
      }
    }
    expect(targets('schedule.detail', 'COMPLIANCE')).toContain('features.schedule.schedule-detail-page.table')
    expect(targets('schedule.detail', 'COMPLIANCE')).not.toContain('features.schedule.schedule-detail-page.regenerate-reason')
    expect(targets('ledger.review', 'COMPLIANCE')).not.toContain('features.ledger.ledger-page.textarea')
    expect(targets('ledger.review', 'GA_ADMIN')).toContain('features.ledger.ledger-page.textarea')
    expect(targets('validation.detail', 'SETTLEMENT')).not.toContain('features.validation.validation-run-detail-page.btn-finalize')
    expect(targets('validation.detail', 'GA_ADMIN')).toContain('features.validation.validation-run-detail-page.btn-finalize')
    expect(targets('contract.register', 'COMPLIANCE')).toEqual([])
    expect(targets('validation.create', 'GA_ADMIN')).toEqual([])
  })

  it('does not give read roles processing locations through generated help', () => {
    const read = WORKFLOW_GUIDES.flatMap((definition) => workflowHelpSteps(definition.workflowId, 'COMPLIANCE'))
    const permissionFor = (id: string) => permissions.targets[id as keyof typeof permissions.targets]
    expect(read.length).toBeGreaterThan(0)
    expect(read.every((step) => ['INTERNAL_READ', 'OWN_OR_INTERNAL_READ', 'CAN_VIEW_AUDIT_LOG'].includes(permissionFor(step.targetId)))).toBe(true)
    expect(workflowHelpSteps('payment.amount.review', 'COMPLIANCE')).toEqual([])
    const policy = workflowHelpSteps('policy.lifecycle', 'SETTLEMENT').map((step) => step.targetId)
    expect(policy).toContain('policy.action.submit')
    expect(policy).not.toContain('policy.action.approve')
    expect(policy).not.toContain('policy.action.activate')
  })

  it('keeps agent help within own-data views and excludes internal tabs and mutations', () => {
    const steps = WORKFLOW_GUIDES.flatMap((definition) => workflowSteps(definition, 'AGENT'))
    expect(steps.length).toBeGreaterThan(0)
    expect(steps.every((step) => ['AGNT-W01', 'CONT-W01', 'CONT-W02', 'TRAN-W01'].includes(step.screenId))).toBe(true)
    expect(steps.some((step) => step.targetId === 'contract.tab.ledger')).toBe(false)
    expect(steps.some((step) => step.targetId === 'features.transaction.transaction-list-page.context-link')).toBe(false)
  })

  it('requires an explicit permission decision for every feature, with no obsolete entries', () => {
    expect(Object.keys(permissions.targets).sort()).toEqual(features.map((feature) => feature.id).sort())
    for (const roleGroup of Object.values(permissions.targets)) expect(permissions.roles[roleGroup as keyof typeof permissions.roles].length).toBeGreaterThan(0)
  })

  it('does not invent tabs from unrelated imports or removed draft fields', () => {
    for (const definition of WORKFLOW_GUIDES) {
      for (const step of workflowHelpSteps(definition.workflowId)) {
        if (step.targetId.startsWith('reference.')) expect(['BASE-W01', 'POL-W01']).toContain(step.screenId)
        expect(step.targetId).not.toMatch(/^policy\.draft-select\.feeregimerules\.(insurerid|productofferingid|commissionitemid)$/)
      }
    }
  })
})

it('renders the surrounding screen when a newly added optional guide instance is unregistered', () => {
  render(<ReferenceTabs tabs={[{ id: 'new-business-tab', label: '새 업무' }]} active="new-business-tab" onChange={() => {}}>업무 내용</ReferenceTabs>)
  expect(screen.getByRole('tab', { name: '새 업무' })).not.toHaveAttribute('data-guide-feature')
  expect(screen.getByRole('tabpanel')).toHaveTextContent('업무 내용')
  expect(guideFeatureId('navigation.menu', 'UNREGISTERED')).toBeUndefined()
  expect(guideFeatureId('navigation.menu', 'CONT-W01')).toBe('navigation.menu.cont-w01')
})


it('keeps create and edit help in their actual route variant despite the shared screen ID', () => {
  const edit = targets('contract.edit', 'SETTLEMENT')
  const create = targets('contract.register', 'SETTLEMENT')
  expect(edit).toContain('features.contract.contract-form-page.agent-name')
  expect(edit).not.toContain('features.contract.contract-form-page.agent-id')
  expect(edit).not.toContain('features.contract.contract-form-page.contract-status')
  expect(create).toContain('features.contract.contract-form-page.agent-id')
  expect(create).not.toContain('features.contract.contract-form-page.agent-name')
  expect(create).not.toContain('features.contract.contract-form-page.next-contract-status')
  expect(create.some((id) => id.includes('status-event-'))).toBe(false)
  expect(targets('contract.find', 'COMPLIANCE')).toContain('contract.filter.input.contractdatefrom')
})
