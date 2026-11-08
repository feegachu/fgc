import { act, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useRunProgressToast } from './useRunProgressToast'
import { useToastStore } from '../../stores/toasts'

afterEach(() => vi.restoreAllMocks())

it('waits for the results layout to paint and cancels a toast when the run stops before paint', () => {
  const frames = new Map<number, FrameRequestCallback>()
  let sequence = 0
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { frames.set(++sequence, callback); return sequence })
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(id => { frames.delete(id) })
  const show = vi.spyOn(useToastStore.getState(), 'show')
  const progress = { steps: ['실행 생성', '대상 선별'], completed: 0, running: 0, buffering: false }
  const { rerender } = renderHook(({ active }) => useRunProgressToast(active, '검증 진행 중', progress), { initialProps: { active: true } })
  expect(show).not.toHaveBeenCalled()
  act(() => { const callback = frames.get(1)!; frames.delete(1); callback(0) })
  expect(show).not.toHaveBeenCalled()
  act(() => { const callback = frames.get(2)!; frames.delete(2); callback(16) })
  expect(show).toHaveBeenCalledTimes(1)
  rerender({ active: false })
  rerender({ active: true })
  rerender({ active: false })
  act(() => { frames.forEach(callback => callback(32)) })
  expect(show).toHaveBeenCalledTimes(1)
})
