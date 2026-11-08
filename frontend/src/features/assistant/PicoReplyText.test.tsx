import { act, cleanup, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { PicoReplyText } from './PicoReplyText'

afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals() })

it('shows the entire reply immediately when reduced motion is preferred', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  const complete = vi.fn()
  const view = render(<PicoReplyText text="전체 답변" animate onComplete={complete} />)
  expect(view.container.querySelector('p')).toHaveTextContent('전체 답변')
  expect(view.container.querySelector('p')).toHaveAttribute('aria-busy', 'false')
  expect(complete).toHaveBeenCalledTimes(1)
})

it('reveals a validated reply gradually, preserves emoji and stops updating after completion', () => {
  vi.useFakeTimers()
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  const complete = vi.fn()
  const text = '안녕하세요 🙂 계약을 조회하고 필요한 화면으로 이동할 수 있습니다.'
  const view = render(<PicoReplyText text={text} animate onComplete={complete} />)
  const answer = view.container.querySelector('p')!
  expect(answer).toHaveAttribute('data-streaming', 'true')
  act(() => { vi.advanceTimersByTime(96) })
  expect(answer.textContent!.length).toBeGreaterThan(0)
  expect(answer.textContent!.length).toBeLessThan(text.length)
  expect(answer.textContent).not.toContain('�')
  expect(complete).not.toHaveBeenCalled()
  act(() => { vi.advanceTimersByTime(1000) })
  expect(answer).toHaveTextContent(text)
  expect(answer).toHaveAttribute('data-streaming', 'false')
  expect(complete).toHaveBeenCalledTimes(1)
  act(() => { vi.advanceTimersByTime(1000) })
  expect(complete).toHaveBeenCalledTimes(1)
})

it('renders an earlier answer immediately and cancels unfinished output when unmounted', () => {
  vi.useFakeTimers()
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  const complete = vi.fn()
  const first = render(<PicoReplyText text="이전 답변" animate={false} onComplete={complete} />)
  expect(first.container).toHaveTextContent('이전 답변')
  expect(first.container.querySelector('p')).toHaveAttribute('data-streaming', 'false')
  first.unmount()
  const next = render(<PicoReplyText text="다음 답변을 준비하고 있습니다." animate onComplete={complete} />)
  next.unmount()
  act(() => { vi.advanceTimersByTime(5000) })
  expect(complete).not.toHaveBeenCalled()
})
