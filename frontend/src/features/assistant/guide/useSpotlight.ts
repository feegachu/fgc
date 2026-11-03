import { useEffect, useState } from 'react'
import type { RefObject } from 'react'

type CardPosition = { left: number | 'auto'; top: number | 'auto'; right: number | 'auto'; bottom: number | 'auto' }
const bottomRight: CardPosition = { left: 'auto', top: 'auto', right: 16, bottom: 16 }

export function useSpotlight(target: HTMLElement | null, card: RefObject<HTMLElement | null>) {
  const [position, setPosition] = useState<{
    box: { left: number; top: number; width: number; height: number } | null
    card: CardPosition
  }>({ box: null, card: bottomRight })
  useEffect(() => {
    let frame = 0
    const measure = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const width = document.documentElement.clientWidth,
          height = window.innerHeight
        const rect = target?.isConnected ? target.getBoundingClientRect() : null
        const box = rect
          ? {
              left: Math.max(0, rect.left - 10),
              top: Math.max(0, rect.top - 10),
              width: Math.max(0, Math.min(width, rect.right + 10) - Math.max(0, rect.left - 10)),
              height: Math.max(0, Math.min(height, rect.bottom + 10) - Math.max(0, rect.top - 10)),
            }
          : null
        const cw = card.current?.offsetWidth ?? 360,
          ch = card.current?.offsetHeight ?? 300
        // Reset opposite insets: CSS bottom defaults must not stretch a top-anchored card (SRC-028).
        const corners: { left: number; top: number; anchor: CardPosition }[] = [
          { left: width - cw - 16, top: height - ch - 16, anchor: bottomRight },
          { left: 16, top: height - ch - 16, anchor: { left: 16, top: 'auto', right: 'auto', bottom: 16 } },
          { left: width - cw - 16, top: 16, anchor: { left: 'auto', top: 16, right: 16, bottom: 'auto' } },
          { left: 16, top: 16, anchor: { left: 16, top: 16, right: 'auto', bottom: 'auto' } },
        ]
        const free =
          box &&
          corners.find(
            (point) =>
              point.left >= 0 &&
              point.top >= 0 &&
              (point.left + cw + 12 < box.left ||
                point.left > box.left + box.width + 12 ||
                point.top + ch + 12 < box.top ||
                point.top > box.top + box.height + 12),
          )
        setPosition({
          box: box?.width && box.height ? box : null,
          card: free ? free.anchor : bottomRight,
        })
      })
    }
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    if (target) resize?.observe(target)
    if (card.current) resize?.observe(card.current)
    window.addEventListener('scroll', measure, true)
    window.addEventListener('resize', measure)
    window.visualViewport?.addEventListener('resize', measure)
    measure()
    return () => {
      cancelAnimationFrame(frame)
      resize?.disconnect()
      window.removeEventListener('scroll', measure, true)
      window.removeEventListener('resize', measure)
      window.visualViewport?.removeEventListener('resize', measure)
    }
  }, [target, card])
  return position
}
