import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { STEPS } from './state'
import { getScreenWalkthrough } from '../guide/page-guides'
import { workflowWalkthrough } from './workflow-walkthrough'
import { useWorkflowWalkthrough } from './useWorkflowWalkthrough'
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  document.body.innerHTML = '<main id="main-content"><input name="contractDate" type="date" data-guide-feature="features.contract.contract-form-page.contract-date"><select name="insurerId" data-guide-feature="features.contract.contract-form-page.insurer-id"><option value="">선택</option><option value="1">보험회사</option></select><input name="productOfferingId" data-guide-feature="features.contract.contract-form-page.product-offering-id" role="combobox" data-selected-value=""></main>'
})
afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })
it('provides explanations for all eight remaining business stages', () => {
  for (const stage of STEPS.filter(value => value !== 'payment.draft')) {
    const steps = workflowWalkthrough(stage, stage === 'contract.create' ? '/contracts/new' : stage === 'schedule.review' ? '/schedules/1' : '/validation-runs/1', false)
    expect(steps.length).toBeGreaterThan(0)
    expect(steps.every(step => step.selector && step.title && step.instruction)).toBe(true)
  }
  expect(workflowWalkthrough('contract.create', '/contracts/new', false)).toHaveLength(9)
  expect(workflowWalkthrough('validation.finalize', '/validation-runs/1', false)).toHaveLength(11)
})
it('requires explicit next and a real SearchSelect selection rather than typed text', async () => {
  const { result } = renderHook(() => useWorkflowWalkthrough('contract.create', '/contracts/new', false))
  await waitFor(() => expect(result.current.target).not.toBeNull())
  expect(result.current.ready).toBe(false)
  act(() => { const date = document.querySelector<HTMLInputElement>('[name=contractDate]')!; date.value = '2026-07-01'; date.dispatchEvent(new Event('input', { bubbles: true })) })
  await waitFor(() => expect(result.current.ready).toBe(true))
  expect(result.current.index).toBe(0)
  act(() => result.current.next())
  await waitFor(() => expect(result.current.ready).toBe(false))
  act(() => { const field = document.querySelector<HTMLSelectElement>('select')!; field.value = '1'; field.dispatchEvent(new Event('change', { bubbles: true })) })
  await waitFor(() => expect(result.current.ready).toBe(true))
  act(() => result.current.next())
  act(() => { const field = document.querySelector<HTMLInputElement>('[role=combobox]')!; field.value = '검색어'; field.dispatchEvent(new Event('input', { bubbles: true })) })
  await waitFor(() => expect(result.current.target?.getAttribute('role')).toBe('combobox'))
  expect(result.current.ready).toBe(false)
  act(() => { document.querySelector<HTMLElement>('[role=combobox]')!.dataset.selectedValue = '7' })
  await waitFor(() => expect(result.current.ready).toBe(true))
  expect(result.current.index).toBe(2)
})
it('blocks missing or duplicate targets and restores the same explanation after recovery', async () => {
  const { result } = renderHook(() => useWorkflowWalkthrough('contract.create', '/contracts/new', false))
  await waitFor(() => expect(result.current.target).not.toBeNull())
  act(() => document.querySelector('main')!.insertAdjacentHTML('beforeend', '<input name="contractDate" data-guide-feature="features.contract.contract-form-page.contract-date">'))
  await waitFor(() => expect(result.current.target).toBeNull())
  expect(result.current.ready).toBe(false)
  act(() => document.querySelectorAll('[name=contractDate]')[1].remove())
  expect(result.current.status).toBe('PAUSED')
  act(() => result.current.retry())
  await waitFor(() => expect(result.current.target).not.toBeNull())
  expect(result.current.index).toBe(0)
})
it('calls the same contract page guide in order and adds a server-result gate at the end', () => {
  const steps = workflowWalkthrough('contract.create', '/contracts/new', false)
  const page = getScreenWalkthrough('CONT-W03').filter(step => !step.targetId.endsWith('next-contract-status') && !step.targetId.endsWith('status-event-effective-at'))
  expect(steps.map(step => step.id)).toEqual(page.map(step => step.targetId))
  expect(steps.map(step => [step.title, step.instruction, step.interaction, step.presentation, step.openControl])).toEqual(
    page.map(step => [step.title, step.instruction, step.interaction, step.presentation, step.openControl]))
  expect(steps.at(-1)?.workflowInstruction).toContain('서버의 계약과 스케줄')
  expect(steps[0].id).toContain('contract-date')
})
it('uses a separate correction handoff even when the resolved exception disappeared', () => {
  const steps = workflowWalkthrough('exception.resolve', '/exceptions', true, true)
  expect(steps).toHaveLength(1)
  expect(steps[0].id).toBe('exception.replacement')
  expect(steps[0].selector).toBe('h1')
})
it('explains returned results rather than searching for inputs after successful navigation', () => {
  expect(workflowWalkthrough('contract.create', '/contracts/4', false, false, false)[0].id).toBe('saved')
  expect(workflowWalkthrough('payment.confirm', '/transactions', false)[0].selector).toContain('payment.results')
})

it('waits for destination loading before observing a replacement payment form', async () => {
  document.querySelector('main')!.innerHTML = '<section data-guide-target="payment.scope">old draft</section>'
  const { result, rerender } = renderHook(({ observing }) =>
    useWorkflowWalkthrough('payment.precheck', '/transactions/new', false, false, observing),
  { initialProps: { observing: false } })
  expect(result.current.step?.title).toBe('저장한 지급 대상을 확인하세요')
  expect(result.current.target).toBeNull()
  await act(async () => { document.querySelector('main')!.innerHTML = '' })
  await act(async () => {
    document.querySelector('main')!.innerHTML = '<section data-guide-target="payment.scope">saved payment</section>'
  })
  rerender({ observing: true })
  await waitFor(() => expect(result.current.ready).toBe(true))
  expect(result.current.target).toHaveTextContent('saved payment')
  // A genuine disappearance after loading must still pause, not silently recover.
  await act(async () => { document.querySelector('main')!.innerHTML = '' })
  await waitFor(() => expect(result.current.status).toBe('PAUSED'))
})
