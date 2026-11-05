import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useWalkthroughTarget } from './useWalkthroughTarget'
beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  document.body.innerHTML = '<main id="main-content"></main>'
})
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); document.body.innerHTML = '' })
it('pauses after five seconds, preserves input and requires explicit retry after recovery', async () => {
  const { result } = renderHook(() => useWalkthroughTarget('#field', 'contract'))
  expect(result.current.status).toBe('WAITING')
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(result.current.status).toBe('PAUSED')
  expect(result.current.message).toContain('5초')
  await act(async () => { document.querySelector('main')!.innerHTML = '<input id="field" value="사용자 입력">' })
  expect(result.current.target).toBeNull()
  act(() => result.current.retry())
  expect(result.current.status).toBe('READY')
  expect((result.current.target as HTMLInputElement).value).toBe('사용자 입력')
})
it('rejects duplicates and hidden/disappearing targets without resuming automatically', async () => {
  document.querySelector('main')!.innerHTML = '<input id="field">'
  const { result } = renderHook(() => useWalkthroughTarget('#field', 'contract'))
  expect(result.current.status).toBe('READY')
  await act(async () => { document.querySelector('main')!.insertAdjacentHTML('beforeend', '<input id="field">') })
  expect(result.current.status).toBe('PAUSED')
  expect(result.current.message).toContain('중복')
  await act(async () => { document.querySelectorAll('#field')[1].remove() })
  act(() => result.current.retry())
  expect(result.current.status).toBe('READY')
  await act(async () => { (document.querySelector('#field') as HTMLElement).style.visibility = 'hidden' })
  expect(result.current.status).toBe('PAUSED')
  expect(result.current.target).toBeNull()
})
it('cancels the old deadline on a step change and on unmount', async () => {
  const { result, rerender, unmount } = renderHook(({ selector }) => useWalkthroughTarget(selector, 'contract'), { initialProps: { selector: '#missing' } })
  await act(async () => { await vi.advanceTimersByTimeAsync(4000); document.querySelector('main')!.innerHTML = '<input id="present">' })
  rerender({ selector: '#present' })
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(result.current.status).toBe('READY')
  unmount()
  expect(vi.getTimerCount()).toBe(0)
})
