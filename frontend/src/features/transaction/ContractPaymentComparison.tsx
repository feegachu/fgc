import { useId, useLayoutEffect, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'

type Box = { key: string; x: number; y: number; width: number; height: number }
type Geometry = { width: number; height: number; panels: Box[]; amounts: Box[] }

/** The comparison is visual only; it never copies an expected amount into a payment. */
export function ContractPaymentComparison({ root }: { root: RefObject<HTMLDivElement | null> }) {
  const maskId = useId()
  const [geometry, setGeometry] = useState<Geometry | null>(null)

  useLayoutEffect(() => {
    const container = root.current
    if (!container) return
    let frame = 0
    const measure = () => {
      frame = 0
      const width = window.innerWidth
      const height = window.innerHeight
      const box = (element: Element, key: string, padding: number): Box | null => {
        if (!element.getClientRects().length) return null
        const rect = element.getBoundingClientRect()
        let left = Math.max(0, rect.left - padding)
        let top = Math.max(0, rect.top - padding)
        let right = Math.min(width, rect.right + padding)
        let bottom = Math.min(height, rect.bottom + padding)
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
        const bounds = element && box(element, key, 6)
        return bounds ? [bounds] : []
      })
      const amounts = [...container.querySelectorAll<HTMLElement>('[data-compare-amount]')].flatMap(element => {
        const bounds = box(element, element.dataset.compareAmount!, 3)
        return bounds ? [bounds] : []
      })
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
          {geometry.panels.map(({ key, ...bounds }) => <rect key={key} {...bounds} rx="10" fill="black" />)}
        </mask>
      </defs>
      <rect width="100%" height="100%" fill="#17334b38" mask={`url(#${maskId})`} />
      {geometry.panels.map(({ key, ...bounds }) => <rect key={key} {...bounds} rx="10" fill="none" stroke="var(--color-fgc-blue)" strokeWidth="2" />)}
      {geometry.amounts.map(({ key, ...bounds }) => <rect key={key} {...bounds} rx="5" fill="none" stroke="var(--color-fgc-blue)" strokeWidth="2" />)}
    </svg>,
    document.body,
  )
}
