import { useRef } from 'react'
import { act, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useSpotlight } from './useSpotlight'

function Card({ target }: { target: HTMLElement | null }) {
  const card = useRef<HTMLElement>(null)
  const position = useSpotlight(target, card)
  return <section ref={card} aria-label="Guide card" style={position.card}>Next</section>
}

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

it.each([
  { initialTop: 8, height: 32, cardTop: '124px' },
  { initialTop: 400, height: 200, cardTop: '344px' },
  { initialTop: 100, height: 500, cardTop: '344px' },
])('keeps the target below the fixed header when reserving coach space ($initialTop, $height)', ({ initialTop, height, cardTop }) => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  const header = document.createElement('header')
  header.className = 'app-header'
  const target = document.createElement('button')
  document.body.append(header, target)
  vi.spyOn(header, 'getBoundingClientRect').mockReturnValue(new DOMRect(255, 0, 1025, 52))
  let top = initialTop
  vi.spyOn(target, 'getBoundingClientRect').mockImplementation(() => new DOMRect(356, top, 100, height))
  vi.spyOn(window, 'scrollBy').mockImplementation((options) => {
    top -= (options as ScrollToOptions).top ?? 0
  })
  try {
    render(<Card target={target} />)
    act(() => { vi.advanceTimersByTime(80) })
    if (height <= 252) expect(target.getBoundingClientRect().top).toBeGreaterThanOrEqual(68)
    else expect(target.getBoundingClientRect().bottom).toBe(320)
    expect(screen.getByRole('region', { name: 'Guide card' })).toHaveStyle({ top: cardTop })
  } finally { header.remove(); target.remove() }
})

it('keeps the whole card in the viewport when page scrolling cannot expose it, including repeated resizes', () => {
  vi.useFakeTimers()
  let resize: (() => void) | undefined
  vi.stubGlobal('ResizeObserver', class { constructor(callback: () => void) { resize = callback } observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(430)
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (this: HTMLElement) {
    // A fixed auto-height card is stretched by top + the stylesheet bottom:22px.
    // The real regression toggled 228px natural height and 46dvh (331px).
    return this.style.top && this.style.top !== 'auto' && this.style.bottom !== 'auto' ? 331 : 228
  })
  const target = document.createElement('div')
  document.body.append(target)
  vi.spyOn(target, 'getBoundingClientRect').mockReturnValue(new DOMRect(270, 320, 600, 180))
  try {
    render(<Card target={target} />)
    act(() => { vi.advanceTimersByTime(20) })
    const card = screen.getByRole('region', { name: 'Guide card' })
    expect(window.scrollBy).toHaveBeenCalledWith({ top: 180, behavior: 'instant' })
    expect(card).toHaveStyle({ top: '480px', bottom: 'auto', right: 'auto', left: '270px', width: '360px' })
    expect(Number.parseFloat(card.style.top) + card.offsetHeight).toBeLessThanOrEqual(window.innerHeight - 12)
    const stable = card.getAttribute('style')
    for (let index = 0; index < 5; index++) {
      act(() => { resize?.(); vi.advanceTimersByTime(20) })
      expect(card.getAttribute('style')).toBe(stable)
      expect(card.offsetHeight).toBe(228)
    }
    act(() => { vi.advanceTimersByTime(40) })
    vi.mocked(window.scrollBy).mockClear()
    act(() => { window.dispatchEvent(new Event('scroll')); vi.advanceTimersByTime(40) })
    expect(window.scrollBy).not.toHaveBeenCalled()
  } finally { target.remove() }
})
