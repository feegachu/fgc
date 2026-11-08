import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useValidationPlaybackGuide } from './useValidationPlaybackGuide'
afterEach(() => { cleanup(); document.body.innerHTML = ''; vi.useRealTimers() })
it('hides during playback, recovers on errors, and advances only after results are ready', async () => {
  const root = document.createElement('main'); root.id = 'main-content'
  root.dataset.guideValidationState = 'running'; document.body.append(root)
  const next = vi.fn()
  const hook = renderHook(() => useValidationPlaybackGuide(true, 'validation.playback', next))
  await waitFor(() => expect(hook.result.current.hidden).toBe(true))
  act(() => { root.dataset.guideValidationState = 'error'; root.dataset.guideValidationError = '조회 실패' })
  await waitFor(() => expect(hook.result.current.error).toBe('조회 실패'))
  expect(hook.result.current.hidden).toBe(false)
  expect(next).not.toHaveBeenCalled()
  act(() => { root.dataset.guideValidationState = 'ready'; root.dataset.guideValidationError = '' })
  await waitFor(() => expect(next).toHaveBeenCalledTimes(1), { timeout: 2000 })
})
