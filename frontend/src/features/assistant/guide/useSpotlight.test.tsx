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

it('keeps user scrolling free when the guide card resizes after following an offscreen target', () => {
  vi.useFakeTimers()
  let resized: (() => void) | undefined
  vi.stubGlobal('ResizeObserver', class { constructor(callback: () => void) { resized = callback } observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  const scroll = vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  const target = document.createElement('button')
  document.body.append(target)
  let top = 100
  vi.spyOn(target, 'getBoundingClientRect').mockImplementation(() => new DOMRect(300, top, 100, 40))
  const reveal = vi.fn()
  target.scrollIntoView = reveal
  try {
    render(<Card target={target} />)
    act(() => { vi.advanceTimersByTime(80) })
    scroll.mockClear(); reveal.mockClear()
    top = -300
    fireEvent.scroll(window)
    act(() => { vi.advanceTimersByTime(40) })
    for (let index = 0; index < 3; index++) {
      act(() => { resized?.(); vi.advanceTimersByTime(80) })
    }
    expect(scroll).not.toHaveBeenCalled()
    expect(reveal).not.toHaveBeenCalled()
    expect(screen.getByRole('region', { name: 'Guide card' })).toHaveAttribute('data-box', 'null')
  } finally { target.remove() }
})

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
    expect(card).toHaveStyle({ top: '68px', bottom: 'auto', right: 'auto', left: '270px', width: '360px' })
    expect(Number.parseFloat(card.style.top) + card.offsetHeight).toBeLessThanOrEqual(320 - 10)
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

it.each([
  { left: 100, top: 560, width: 100, height: 40, cardLeft: 100, cardTop: 236, maxHeight: 360 },
  { left: 100, top: 260, width: 200, height: 200, cardLeft: 324, cardTop: 260, maxHeight: 360 },
  { left: 980, top: 260, width: 200, height: 200, cardLeft: 596, cardTop: 260, maxHeight: 360 },
  { left: 100, top: 200, width: 1100, height: 300, cardLeft: 100, cardTop: 524, maxHeight: 184 },
])('keeps the highlighted target clear in a constrained viewport ($left, $top, $width)', (fixture) => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(300)
  const target = document.createElement('button')
  document.body.append(target)
  vi.spyOn(target, 'getBoundingClientRect').mockReturnValue(new DOMRect(fixture.left, fixture.top, fixture.width, fixture.height))
  try {
    render(<Card target={target} />)
    act(() => { vi.advanceTimersByTime(80) })
    const card = screen.getByRole('region', { name: 'Guide card' })
    expect(card).toHaveStyle({ left: `${fixture.cardLeft}px`, top: `${fixture.cardTop}px`, maxHeight: `${fixture.maxHeight}px`, overflowY: 'auto' })
    const box = JSON.parse(card.dataset.box!)
    const bottom = fixture.cardTop + Math.min(300, fixture.maxHeight)
    expect(bottom).toBeLessThanOrEqual(708)
    expect(fixture.cardLeft + 360 <= box.left || fixture.cardLeft >= box.left + box.width || bottom <= box.top || fixture.cardTop >= box.top + box.height).toBe(true)
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
    expect(JSON.parse(card.dataset.box!)).toEqual({ left: 90, top: 90, width: 320, height: 120 })
    rect.mockReturnValue(new DOMRect(200, 120, 300, 100))
    fireEvent.resize(window)
    act(() => { vi.advanceTimersByTime(80) })
    expect(JSON.parse(card.dataset.box!).left).toBe(190)
    vi.mocked(window.scrollBy).mockClear()
    rect.mockReturnValue(new DOMRect(300, 120, 300, 100))
    fireEvent.scroll(window)
    act(() => { vi.advanceTimersByTime(80) })
    expect(JSON.parse(card.dataset.box!).left).toBe(290)
    expect(window.scrollBy).not.toHaveBeenCalled()
    rect.mockReturnValue(new DOMRect(300, 140, 240, 260))
    vi.mocked(window.scrollBy).mockImplementation(() => {
      rect.mockReturnValue(new DOMRect(300, 60, 240, 260))
    })
    act(() => { vi.advanceTimersByTime(120) })
    expect(window.scrollBy).toHaveBeenCalledWith({ top: 80, behavior: 'instant' })
    expect(JSON.parse(card.dataset.box!)).toEqual({ left: 290, top: 50, width: 260, height: 280 })
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

it.each([-300, 900])('keeps guide controls reachable after the user scrolls the target to %ipx', (top) => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(300)
  const header = document.createElement('header')
  header.className = 'app-header'
  const target = document.createElement('button')
  document.body.append(header, target)
  vi.spyOn(header, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 1280, 64))
  const rect = vi.spyOn(target, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 100, 40))
  try {
    render(<Card target={target} />)
    act(() => { vi.advanceTimersByTime(80) })
    vi.mocked(window.scrollBy).mockClear()
    rect.mockReturnValue(new DOMRect(100, top, 100, 40))
    fireEvent.scroll(window)
    act(() => { vi.advanceTimersByTime(80) })
    const card = screen.getByRole('region', { name: 'Guide card' })
    expect(Number.parseFloat(card.style.top)).toBeGreaterThanOrEqual(72)
    expect(Number.parseFloat(card.style.top) + Math.min(300, Number.parseFloat(card.style.maxHeight))).toBeLessThanOrEqual(708)
    expect(card).toHaveAttribute('data-box', 'null')
    expect(window.scrollBy).not.toHaveBeenCalled()
  } finally { header.remove(); target.remove() }
})


it('uses a scrollable bottom card when a full-width target leaves no usable vertical slot', () => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(300)
  const target = document.createElement('div')
  document.body.append(target)
  vi.spyOn(target, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 1280, 720))
  try {
    render(<Card target={target} />)
    act(() => { vi.advanceTimersByTime(80) })
    expect(screen.getByRole('region', { name: 'Guide card' })).toHaveStyle({
      top: '408px', maxHeight: '360px', overflowY: 'auto', bottom: 'auto',
    })
  } finally { target.remove() }
})
