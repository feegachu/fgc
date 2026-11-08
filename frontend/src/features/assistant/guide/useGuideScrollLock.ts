import { useEffect, type RefObject } from 'react'

/** Keep the page fixed while a guide is visible; allow reading inside the coach or an open dialog. */
export function useGuideScrollLock(card: RefObject<HTMLElement | null>, allowPageScroll = false) {
  useEffect(() => {
    const lock = (event: WheelEvent) => {
      const guide = card.current
      if (allowPageScroll || !guide?.isConnected || !guide.getClientRects().length || getComputedStyle(guide).visibility === 'hidden' || event.ctrlKey) return
      const target = event.target instanceof Element ? event.target : null
      const reader = target?.closest<HTMLElement>('.payment-guide-card, .settlement-guide, [role="dialog"], .guide-field-picker, .search-select-popup')
      if (reader) {
        for (let node = target; node; node = node.parentElement) {
          const style = getComputedStyle(node)
          if (/auto|scroll/.test(style.overflowY) &&
            (event.deltaY < 0 ? node.scrollTop > 0 : node.scrollTop + node.clientHeight < node.scrollHeight - 1)) return
          if (node === reader) break
        }
      }
      event.preventDefault()
    }
    document.addEventListener('wheel', lock, { passive: false, capture: true })
    return () => document.removeEventListener('wheel', lock, true)
  }, [card, allowPageScroll])
}
