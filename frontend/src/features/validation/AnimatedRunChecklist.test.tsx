import { act, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AnimatedRunChecklist } from './AnimatedRunChecklist'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

it('reports presentation completion only after all eight results and the final check animation finish', () => {
  vi.useFakeTimers()
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  const onPresented = vi.fn()
  const data = { passed: true, conditions: Array.from({ length: 8 }, (_, index) => ({ no: index + 1, label: `조건 ${index + 1}`, passed: true, count: 0 })) }
  const view = render(<AnimatedRunChecklist completed={0} data={data} retry={() => {}} onPresented={onPresented} />)
  act(() => { vi.advanceTimersByTime(10000) })
  expect(onPresented).not.toHaveBeenCalled()
  view.rerender(<AnimatedRunChecklist completed={8} data={data} retry={() => {}} onPresented={onPresented} />)
  for (let index = 0; index < 8; index++) act(() => { vi.advanceTimersByTime(650) })
  expect(onPresented).not.toHaveBeenCalled()
  act(() => { vi.advanceTimersByTime(649) })
  expect(onPresented).not.toHaveBeenCalled()
  act(() => { vi.advanceTimersByTime(1) })
  expect(onPresented).toHaveBeenCalledTimes(1)
})
