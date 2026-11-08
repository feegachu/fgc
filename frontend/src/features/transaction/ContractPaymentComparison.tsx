import { useId, useEffect, useLayoutEffect, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'

type Box = { key: string; x: number; y: number; width: number; height: number }
type Geometry = { width: number; height: number; panels: Box[]; amounts: Box[] }

/** The comparison is visual only; it never copies an expected amount into a payment. */
export function ContractPaymentComparison({ root }: { root: RefObject<HTMLDivElement | null> }) {
  const maskId = useId()
  const [phase, setPhase] = useState<'waiting' | 'expected' | 'actual'>('waiting')
  useEffect(() => {
    const show = setTimeout(() => setPhase('expected'), 1450)
    const move = setTimeout(() => setPhase('actual'), 3000)
    return () => { clearTimeout(show); clearTimeout(move) }
  }, [])
  const [geometry, setGeometry] = useState<Geometry | null>(null)

  useLayoutEffect(() => {
    const container = root.current
    if (!container) return
    let frame = 0
    const measure = () => {
      frame = 0
      const width = window.innerWidth
      const height = window.innerHeight
      const input = container.querySelector('[data-compare-amount^="actual-"]')?.getBoundingClientRect()
      if (input) {
        for (const [name, value] of [['width', input.width], ['height', input.height]] as const) {
          const property = `--comparison-amount-${name}`
          const size = `${value}px`
          if (container.style.getPropertyValue(property) !== size) container.style.setProperty(property, size)
        }
      }
      const box = (element: Element, key: string, padding: number, rowLimit?: number): Box | null => {
        if (!element.getClientRects().length) return null
        const rect = element.getBoundingClientRect()
        let left = Math.max(0, rect.left - padding)
        let top = Math.max(0, rect.top - padding)
        let right = Math.min(width, rect.right + padding)
        let bottom = Math.min(height, rect.bottom + padding)
        if (rowLimit) {
          const rows = [...element.querySelectorAll<HTMLElement>('tbody > tr, ol > li')]
            .filter(row => row.getClientRects().length > 0).slice(0, rowLimit)
          const last = rows.at(-1)?.getBoundingClientRect()
          if (last) bottom = Math.min(bottom, last.bottom + padding)
        }
        // Keep outlines inside their scroll viewport, including horizontally scrolled tables.
        for (let parent = element.parentElement; parent; parent = parent.parentElement) {
          const style = getComputedStyle(parent)
          const bounds = parent.getBoundingClientRect()
          if (/(auto|scroll|hidden|clip)/.test(style.overflowX)) {
            left = Math.max(left, bounds.left)
            right = Math.min(right, bounds.right)
          }
          if (/(auto|scroll|hidden|clip)/.test(style.overflowY)) {
            top = Math.max(top, bounds.top)
            bottom = Math.min(bottom, bounds.bottom)
          }
        }
        return right > left && bottom > top ? { key, x: left, y: top, width: right - left, height: bottom - top } : null
      }
      const panels = ['payment.batch.lines', 'payment.batch.expected'].flatMap(key => {
        const element = container.querySelector(`[data-guide-target="${key}"]`)
        const bounds = element && box(element, key, 6, 3)
        return bounds ? [bounds] : []
      })
      const amounts: Box[] = []
      // Match one actual input to its expected schedule line, never by display order alone.
      for (const element of [...container.querySelectorAll<HTMLElement>('[data-compare-amount^="actual-"]')].slice(0, 3)) {
        const key = element.dataset.compareAmount!.slice('actual-'.length)
        const expected = container.querySelector('[data-compare-amount="expected-' + key + '"]')
        const actualBox = box(element, 'actual-' + key, 5)
        const expectedBox = expected && box(expected, 'expected-' + key, 5)
        if (actualBox && expectedBox) { amounts.push(expectedBox, actualBox); break }
      }
      const next = { width, height, panels, amounts }
      setGeometry(previous => JSON.stringify(previous) === JSON.stringify(next) ? previous : next)
    }
    const schedule = () => { if (!frame) frame = requestAnimationFrame(measure) }
    const resize = new ResizeObserver(schedule)
    resize.observe(container)
    container.querySelectorAll('[data-guide-target="payment.batch.lines"], [data-guide-target="payment.batch.expected"]').forEach(element => resize.observe(element))
    const mutation = new MutationObserver(schedule)
    mutation.observe(container, { childList: true, subtree: true, attributes: true, characterData: true })
    window.addEventListener('resize', schedule)
    window.addEventListener('scroll', schedule, true)
    schedule()
    return () => {
      cancelAnimationFrame(frame)
      resize.disconnect()
      mutation.disconnect()
      container.style.removeProperty('--comparison-amount-width')
      container.style.removeProperty('--comparison-amount-height')
      window.removeEventListener('resize', schedule)
      window.removeEventListener('scroll', schedule, true)
    }
  }, [root])

  if (!geometry) return null
  return createPortal(
    <svg className="contract-payment-comparison-overlay" width={geometry.width} height={geometry.height} aria-hidden="true">
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="0" y="0" width={geometry.width} height={geometry.height}>
          <rect width="100%" height="100%" fill="white" />
          {geometry.panels.map(({ key, ...bounds }) => <rect key={key} className="contract-comparison-panel-reveal" {...bounds} rx="10" fill="black" />)}
        </mask>
      </defs>
      <rect width="100%" height="100%" fill="#17334b38" mask={`url(#${maskId})`} />
      {geometry.panels.map(({ key, ...bounds }) => <rect key={key} className="contract-comparison-panel-reveal" {...bounds} rx="10" fill="none" stroke="var(--color-fgc-blue)" strokeWidth="2" />)}
      {phase !== 'waiting' && geometry.amounts.length === 2 && (() => {
        const bounds = geometry.amounts[phase === 'actual' ? 1 : 0]
        return <rect className="contract-comparison-moving-amount"
          style={{ x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height }}
          rx="5" fill="none" stroke="var(--color-fgc-blue)" strokeWidth="2" />
      })()}

    </svg>,
    document.body,
  )
}
