import { StrictMode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useSearchParams } from 'react-router'
import { Modal } from '../../../components/Modal'
import { ScreenHelp } from './ScreenHelp'
import type { ScreenHelpStep } from './ScreenHelp'
import { ScreenWalkthrough } from './ScreenWalkthrough'
import { SaveGuide } from './save-guide'
import definitions from './screen-help.json'
import { screenWalkthroughs } from './screen-walkthroughs'

vi.mock('react-router', async original => ({
  ...await original<typeof import('react-router')>(), useOutletContext: () => ({ helpVersion: 'after' }),
}))
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{ x: 0, y: 100, width: 100, height: 40 }] as unknown as DOMRectList)
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })
const steps: ScreenHelpStep[] = ['first', 'hidden', 'duplicate', 'last'].map(targetId => ({
  targetId, group: '안내', title: `${targetId} 설명`, instruction: '현재 요소의 의미', details: [{ label: '확인할 점', text: '상태를 확인합니다.' }], interaction: '화면에서 확인합니다.',
}))

it('starts presentation only for the visible step and cleans up on fold, next and close', async () => {
  const stop = vi.fn(), start = vi.fn(() => stop)
  render(<main id="main-content"><ScreenWalkthrough title="재생" steps={[{ ...steps[0], onEnter: start }, steps[3]]} />
    <div data-guide-target="first" /><div data-guide-target="last" /></main>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  expect(start).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: '접기' }))
  expect(stop).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: '펼치기' }))
  expect(start).toHaveBeenCalledTimes(2)
  await userEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(stop).toHaveBeenCalledTimes(2)
  await userEvent.click(screen.getByRole('button', { name: '이전' }))
  await userEvent.click(screen.getByRole('button', { name: '종료' }))
  expect(start).toHaveBeenCalledTimes(3)
  expect(stop).toHaveBeenCalledTimes(3)
})

it('covers every remaining screen and modal with detailed, uniquely identified explanations', () => {
  const keys = definitions.filter(item => item.screenId !== 'DASH-W01').map(item => item.variant ? `${item.screenId}.${item.variant}` : item.screenId)
  expect(Object.keys(screenWalkthroughs).sort()).toEqual(keys.sort())
  for (const [key, tour] of Object.entries(screenWalkthroughs)) {
    expect(tour.length, key).toBeGreaterThanOrEqual(2)
    expect(new Set(tour.map(step => step.targetId)).size, key).toBe(tour.length)
    for (const step of tour) {
      expect(step.instruction.length, `${key}/${step.targetId}`).toBeGreaterThan(30)
      expect(step.details[0].text.length).toBeGreaterThan(25)
      expect(step.interaction).not.toBe('')
      if (step.selector) expect(() => document.querySelector(step.selector!)).not.toThrow()
    }
  }
})

it('opens immediately, omits hidden or ambiguous targets, and advances without saving valid input', async () => {
  const user = userEvent.setup(), write = vi.fn(), save = new SaveGuide()
  save.update('draft', false)
  render(<StrictMode><MemoryRouter><main id="main-content">
    <ScreenHelp screenId="CONT-W03" walkthrough={steps} saveGuide={save} />
    <section data-guide-target="first"><input aria-label="입력" defaultValue="작성중" /></section>
    <section data-guide-target="hidden" hidden />
    <div data-guide-target="duplicate" /><div data-guide-target="duplicate" />
    <button data-guide-target="last" disabled onClick={write}>실제 저장</button>
  </main></MemoryRouter></StrictMode>)
  await user.click(screen.getByRole('button', { name: '도움말' }))
  expect(screen.getByText('화면 요소 안내 · 1/2')).toBeVisible()
  expect(save.isActive()).toBe(false)
  await user.type(screen.getByLabelText('입력'), ' 유지')
  await user.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('heading', { name: 'last 설명' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '안내 마치기' }))
  expect(write).not.toHaveBeenCalled()
  expect(screen.getByLabelText('입력')).toHaveValue('작성중 유지')
  expect(screen.getByRole('button', { name: '도움말' })).toHaveFocus()
})

it('uses the detailed payment tour rather than invoking the old payment trigger', async () => {
  const legacy = vi.fn()
  render(<main id="main-content"><ScreenHelp screenId="TRAN-W02" legacyTrigger={<button onClick={legacy}>기존 도움말</button>} />
    <button data-guide-target="payment.save">실제 저장</button><button data-guide-target="payment.precheck">실제 검증</button>
  </main>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  expect(screen.getByRole('heading', { name: '초안 저장' })).toBeVisible()
  expect(legacy).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: '기존 도움말' })).toBeNull()
})

it('Escape closes only the tour inside an editor, preserving the modal and draft', async () => {
  const dismiss = vi.fn()
  render(<Modal open title="편집" onClose={dismiss}>
    <ScreenWalkthrough title="입력" steps={steps} />
    <section data-guide-target="first"><input aria-label="사유" defaultValue="보존" /></section>
    <button data-guide-target="last">저장</button>
  </Modal>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  expect(within(screen.getByRole('dialog')).getByRole('region', { name: '입력 도움말' })).toBeVisible()
  await userEvent.keyboard('{Escape}')
  expect(dismiss).not.toHaveBeenCalled()
  expect(screen.getByRole('dialog')).toBeVisible()
  expect(screen.getByLabelText('사유')).toHaveValue('보존')
  expect(screen.getByRole('button', { name: '도움말' })).toHaveFocus()
})

it('keeps Next available when a target disappears and allows reopening for newly visible sections', async () => {
  const { rerender } = render(<main id="main-content"><ScreenWalkthrough title="조건" steps={steps} /><div data-guide-target="first" /><div data-guide-target="last" /></main>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  rerender(<main id="main-content"><ScreenWalkthrough title="조건" steps={steps} /><div data-guide-target="hidden" /><div data-guide-target="last" /></main>)
  await waitFor(() => expect(screen.getByText(/화면 구성이 바뀌어/)).toBeVisible())
  await userEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('heading', { name: 'last 설명' })).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '안내 마치기' }))
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  expect(screen.getByRole('heading', { name: 'hidden 설명' })).toBeVisible()
})

it('shows an immediate empty state rather than waiting for unavailable targets', async () => {
  render(<main id="main-content"><ScreenWalkthrough title="빈 화면" steps={steps} /></main>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  expect(screen.getByRole('heading', { name: '안내할 화면을 준비하고 있어요' })).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('region')).toBeNull()
})

it('keeps the explanation through row selection and query reordering but closes on month changes', async () => {
  function Page() {
    const [, setParams] = useSearchParams()
    return <main id="main-content"><ScreenHelp screenId="EXCP-W01" walkthrough={steps} />
      <div data-guide-target="first" /><div data-guide-target="last" />
      <button onClick={() => setParams({ month: '2026-07', selected: '2' })}>행 선택</button>
      <button onClick={() => setParams({ month: '2026-08', selected: '2' })}>월 변경</button>
    </main>
  }
  render(<MemoryRouter initialEntries={['/?selected=1&month=2026-07']}><Page /></MemoryRouter>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  await userEvent.click(screen.getByRole('button', { name: '행 선택' }))
  expect(screen.getByRole('heading', { name: 'first 설명' })).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '월 변경' }))
  expect(screen.queryByRole('heading', { name: 'first 설명' })).toBeNull()
})
