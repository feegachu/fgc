import { useEffect, useState } from 'react'
import { int } from '../../lib/format'

type Box = { id: string; left: number; top: number; width: number; height: number; label: string; match: boolean }

/** Guide-only visual links. The schedule line ID keeps each amount paired across both panels. */
export function PaymentAmountHighlights({ phase }: { phase: 'expected' | 'attribution' }) {
  const [boxes, setBoxes] = useState<Box[]>([])
  useEffect(() => {
    const root = document.querySelector('.contract-payment-editor')
    if (!root) return
    let frame = 0
    const measure = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const next: Box[] = []
        root.querySelectorAll<HTMLElement>('[data-payment-expected]').forEach(source => {
          const id = source.dataset.paymentExpected!
          const input = root.querySelector<HTMLInputElement>(`[data-payment-attribution="${id}"]`)
          const element = phase === 'expected' ? source : input
          if (!element || !element.getClientRects().length) return
          const rect = element.getBoundingClientRect()
          const header = document.querySelector('.app-header')?.getBoundingClientRect().bottom ?? 0
          const viewport = element.closest('.data-table-viewport')?.getBoundingClientRect()
          if (rect.top < header || rect.bottom > innerHeight || viewport && (rect.left < viewport.left || rect.right > viewport.right)) return
          const expected = source.dataset.expectedAmount
          const known = expected !== undefined && expected !== '' && Number.isFinite(Number(expected))
          const match = known && !!input?.value.trim() && Number(input.value) === Number(expected)
          const label = phase === 'expected' ? '' : !known ? '금액 미정' : match ? '일치' : `예상 ${int(Number(expected))}원`
          next.push({ id, left: rect.left - 4, top: rect.top - 4, width: rect.width + 8, height: rect.height + 8, label, match })
        })
        setBoxes(next)
      })
    }
    measure()
    const observer = new MutationObserver(measure)
    observer.observe(root, { subtree: true, childList: true, attributes: true })
    const resize = new ResizeObserver(measure)
    resize.observe(root)
    root.addEventListener('input', measure)
    window.addEventListener('scroll', measure, true)
    window.addEventListener('resize', measure)
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
      resize.disconnect()
      root.removeEventListener('input', measure)
      window.removeEventListener('scroll', measure, true)
      window.removeEventListener('resize', measure)
    }
  }, [phase])
  return <div className="payment-amount-guide" aria-hidden="true">
    {boxes.map(({ id, label, match, ...rect }) => <div key={id} className="payment-amount-guide-box"
      data-comparing={phase === 'attribution'} data-matching={match} style={rect}>
      {label && <span>{label}</span>}
    </div>)}
  </div>
}
