import { useRef } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useGuideScrollLock } from './useGuideScrollLock'

function Guide({ open = true }: { open?: boolean }) {
  const card = useRef<HTMLElement>(null)
  useGuideScrollLock(card)
  return open ? <section ref={card} className="payment-guide-card" aria-label="설명" style={{ overflowY: 'auto' }}>긴 설명</section> : null
}
afterEach(() => vi.restoreAllMocks())

it('blocks page wheel events, allows the explanation to scroll, and releases on close', () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([new DOMRect(0, 0, 400, 300)] as unknown as DOMRectList)
  const { rerender, unmount } = render(<Guide />)
  const card = screen.getByRole('region')
  vi.spyOn(card, 'scrollHeight', 'get').mockReturnValue(900)
  vi.spyOn(card, 'clientHeight', 'get').mockReturnValue(300)
  expect(fireEvent.wheel(document.body, { deltaY: 100 })).toBe(false)
  expect(fireEvent.wheel(card, { deltaY: 100 })).toBe(true)
  expect(fireEvent.wheel(card, { deltaY: -100 })).toBe(false)
  card.scrollTop = 600
  expect(fireEvent.wheel(card, { deltaY: 100 })).toBe(false)
  expect(fireEvent.wheel(card, { deltaY: -100 })).toBe(true)
  expect(fireEvent.wheel(document.body, { deltaY: 100, ctrlKey: true })).toBe(true)
  rerender(<Guide open={false} />)
  expect(fireEvent.wheel(document.body, { deltaY: 100 })).toBe(true)
  unmount()
  expect(fireEvent.wheel(document.body, { deltaY: 100 })).toBe(true)
})
