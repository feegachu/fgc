import { useRef } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useSpotlight } from './useSpotlight'

function Card({ target }: { target: HTMLElement | null }) {
  const card = useRef<HTMLElement>(null)
  const position = useSpotlight(target, card)
  return <section ref={card} aria-label="Guide card" data-box={JSON.stringify(position.box)} style={position.card}>Next</section>
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

it('attaches below the real target and keeps the opposite inset clear on repeated resizes', () => {
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
    expect(card).toHaveStyle({ top: '524px', bottom: 'auto', right: 'auto', left: '270px', width: '360px' })
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

it('follows viewport events and panel layout without ResizeObserver, then cleans up', () => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', undefined)
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  const remove = vi.spyOn(window, 'removeEventListener')
  const cancel = vi.spyOn(window, 'cancelAnimationFrame')
  const target = document.createElement('div')
  document.body.append(target)
  const rect = vi.spyOn(target, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 300, 100))
  try {
    const { unmount } = render(<Card target={target} />)
    act(() => { vi.advanceTimersByTime(80) })
    const card = screen.getByRole('region', { name: 'Guide card' })
    expect(JSON.parse(card.dataset.box!)).toEqual({ left: 92, top: 92, width: 316, height: 116 })
    rect.mockReturnValue(new DOMRect(200, 120, 300, 100))
    fireEvent.resize(window)
    act(() => { vi.advanceTimersByTime(80) })
    expect(JSON.parse(card.dataset.box!).left).toBe(192)
    vi.mocked(window.scrollBy).mockClear()
    rect.mockReturnValue(new DOMRect(300, 120, 300, 100))
    fireEvent.scroll(window)
    act(() => { vi.advanceTimersByTime(80) })
    expect(JSON.parse(card.dataset.box!).left).toBe(292)
    expect(window.scrollBy).not.toHaveBeenCalled()
    rect.mockReturnValue(new DOMRect(300, 140, 240, 260))
    vi.mocked(window.scrollBy).mockImplementation(() => {
      rect.mockReturnValue(new DOMRect(300, 60, 240, 260))
    })
    act(() => { vi.advanceTimersByTime(120) })
    expect(window.scrollBy).toHaveBeenCalledWith({ top: 80, behavior: 'instant' })
    expect(JSON.parse(card.dataset.box!)).toEqual({ left: 292, top: 52, width: 256, height: 276 })
    expect(card).toHaveStyle({ top: '344px' })
    target.remove()
    act(() => { vi.advanceTimersByTime(120) })
    expect(card).toHaveAttribute('data-box', 'null')
    document.body.append(target)
    unmount()
    rect.mockClear()
    act(() => { vi.advanceTimersByTime(200) })
    expect(rect).not.toHaveBeenCalled()
    expect(remove).toHaveBeenCalledWith('scroll', expect.any(Function), true)
    expect(remove).toHaveBeenCalledWith('resize', expect.any(Function))
    expect(cancel).toHaveBeenCalled()
  } finally { target.remove() }
})
