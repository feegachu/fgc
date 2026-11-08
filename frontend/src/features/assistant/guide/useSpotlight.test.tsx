import { useRef } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useSpotlight } from './useSpotlight'

function Card({ target, detailed = false, presentation }: { target: HTMLElement | null; detailed?: boolean; presentation?: Parameters<typeof useSpotlight>[3] }) {
  const card = useRef<HTMLElement>(null)
  const position = useSpotlight(target, card, detailed, presentation)
  return <section ref={card} aria-label="Guide card" data-box={JSON.stringify(position.box)} style={position.card}>Next</section>
}

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

it('places the comparison coach below the first three rows of a long panel', () => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1440)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(900)
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(240)
  const panel = document.createElement('section')
  panel.innerHTML = `<ol>${'<li>예상 스케줄</li>'.repeat(20)}</ol>`
  document.body.append(panel)
  vi.spyOn(panel, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 600, 1000))
  for (const [index, row] of [...panel.querySelectorAll('li')].entries()) {
    const rect = new DOMRect(110, 150 + index * 40, 580, 40)
    vi.spyOn(row, 'getBoundingClientRect').mockReturnValue(rect)
    vi.spyOn(row, 'getClientRects').mockReturnValue([rect] as unknown as DOMRectList)
  }
  try {
    render(<Card target={panel} detailed presentation={{ side: 'below', rowLimit: 3 }} />)
    act(() => { vi.advanceTimersByTime(100) })
    const card = screen.getByRole('region', { name: 'Guide card' })
    expect(JSON.parse(card.dataset.box!)).toEqual({ left: 90, top: 90, width: 620, height: 190 })
    expect(card).toHaveStyle({ left: '100px', top: '294px' })
  } finally { panel.remove() }
})

it.each([0, 2, 20])('highlights columns and at most three of %i result rows without scrolling to the table end', count => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(900)
  const scroll = vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  const table = document.createElement('table')
  table.innerHTML = `<thead><tr><th>계약번호</th></tr></thead><tbody>${Array.from({ length: count }, (_, i) => `<tr><td>${i + 1}</td></tr>`).join('')}</tbody>`
  document.body.append(table)
  vi.spyOn(table, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 900, 24 + count * 40))
  vi.spyOn(table.tHead!, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 900, 24))
  for (const [index, row] of [...table.tBodies[0].rows].entries()) {
    const rect = new DOMRect(100, 124 + index * 40, 900, 40)
    vi.spyOn(row, 'getBoundingClientRect').mockReturnValue(rect)
    vi.spyOn(row, 'getClientRects').mockReturnValue([rect] as unknown as DOMRectList)
  }
  try {
    render(<Card target={table} detailed />)
    act(() => { vi.advanceTimersByTime(100) })
    const box = JSON.parse(screen.getByRole('region', { name: 'Guide card' }).dataset.box!)
    expect(box).toEqual({ left: 90, top: 90, width: 920, height: 44 + Math.min(count, 3) * 40 })
    expect(scroll).not.toHaveBeenCalled()
  } finally { table.remove() }
})

it.each([40, 2400])('leaves a visible top anchor still for detailed help even with %ipx of content', height => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280)
  vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(720)
  const scroll = vi.spyOn(window, 'scrollBy').mockImplementation(() => {})
  const target = document.createElement('section')
  document.body.append(target)
  vi.spyOn(target, 'getBoundingClientRect').mockReturnValue(new DOMRect(300, 100, 800, height))
  target.scrollIntoView = vi.fn()
  try {
    render(<Card target={target} detailed />)
    act(() => { vi.advanceTimersByTime(100) })
    expect(scroll).not.toHaveBeenCalled()
    expect(target.scrollIntoView).not.toHaveBeenCalled()
  } finally { target.remove() }
})

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


it.each(['left', 'right'] as const)('keeps the full workflow coach fixed on the %s across targets, missing targets and viewport resizing', (fixedCard) => {
  vi.useFakeTimers()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  const width = vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1440)
  const height = vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(900)
  const first = document.createElement('section'), next = document.createElement('section')
  document.body.append(first, next)
  vi.spyOn(first, 'getBoundingClientRect').mockReturnValue(new DOMRect(280, 100, 1100, 200))
  vi.spyOn(next, 'getBoundingClientRect').mockReturnValue(new DOMRect(900, 100, 480, 50))
  const view = render(<Card target={first} detailed presentation={{ fixedCard }} />)
  try {
    act(() => { vi.advanceTimersByTime(100) })
    const card = screen.getByRole('region', { name: 'Guide card' })
    expect(card).toHaveStyle({ left: fixedCard === 'right' ? '1008px' : '12px', top: '524px', width: '420px', height: '360px' })
    const original = card.getAttribute('style')
    const originalBox = card.dataset.box
    view.rerender(<Card target={next} detailed presentation={{ side: 'right', fixedCard }} />)
    act(() => { vi.advanceTimersByTime(100) })
    expect(card.getAttribute('style')).toBe(original)
    expect(card.dataset.box).not.toBe(originalBox)
    view.rerender(<Card target={null} detailed presentation={{ fixedCard }} />)
    act(() => { vi.advanceTimersByTime(100) })
    expect(card.getAttribute('style')).toBe(original)
    width.mockReturnValue(360); height.mockReturnValue(400)
    act(() => { fireEvent(window, new Event('resize')); vi.advanceTimersByTime(100) })
    expect(card).toHaveStyle({ left: '12px', top: '24px', width: '336px', height: '360px' })
  } finally { first.remove(); next.remove() }
})
