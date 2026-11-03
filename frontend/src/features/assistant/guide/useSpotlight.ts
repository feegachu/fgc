import { useEffect, useState } from 'react'
import type { CSSProperties, RefObject } from 'react'

export function useSpotlight(target: HTMLElement | null, card: RefObject<HTMLElement | null>) {
  const [position, setPosition] = useState<{
    box: { left: number; top: number; width: number; height: number } | null
    card: CSSProperties
  }>({ box: null, card: { right: 16, bottom: 16 } })
  useEffect(() => {
    let frame = 0
    let preparedWidth: number | null = null
    const previousMargin = target?.style.scrollMarginBottom ?? ''
    const measure = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const width = document.documentElement.clientWidth || window.innerWidth
        const height = window.innerHeight
        const workspace = document.getElementById('main-content')?.getBoundingClientRect()
        const workspaceLeft = Math.max(0, workspace?.left ?? 0)
        const workspaceRight = Math.min(width, workspace?.right || width)
        const cardLeftBoundary = workspaceRight - workspaceLeft >= 224 ? workspaceLeft : 0
        const cardWidth = Math.min(360, Math.max(0, workspaceRight - cardLeftBoundary - 24))
        if (target?.isConnected && preparedWidth !== workspaceRight - workspaceLeft) {
          preparedWidth = workspaceRight - workspaceLeft
          // Leave room below the real target for the coach, including at the end of a page.
          target.style.scrollMarginBottom = `${Math.min(460, height / 2) + 40}px`
          target.scrollIntoView?.({ block: 'end', inline: 'nearest', behavior: 'instant' })
        }
        const rect = target?.isConnected ? target.getBoundingClientRect() : null
        const headerBottom = Math.max(0, document.querySelector('.app-header')?.getBoundingClientRect().bottom ?? 0)
        const box = rect ? {
          left: Math.max(workspaceLeft, rect.left - 8),
          top: Math.max(headerBottom + 8, rect.top - 8),
          width: Math.max(0, Math.min(workspaceRight, rect.right + 8) - Math.max(workspaceLeft, rect.left - 8)),
          height: Math.max(0, Math.min(height, rect.bottom + 8) - Math.max(headerBottom + 8, rect.top - 8)),
        } : null
        setPosition({
          box: box?.width && box.height ? box : null,
          card: rect ? {
            left: Math.max(cardLeftBoundary + 12, Math.min(rect.left, workspaceRight - cardWidth - 12)),
            top: rect.bottom + 24,
            right: 'auto', bottom: 'auto', width: cardWidth,
          } : { left: 'auto', top: 'auto', right: 16, bottom: 16, width: cardWidth },
        })
      })
    }
    const resize = new ResizeObserver(measure)
    if (target) resize.observe(target)
    if (card.current) resize.observe(card.current)
    window.addEventListener('scroll', measure, true)
    window.addEventListener('resize', measure)
    window.visualViewport?.addEventListener('resize', measure)
    measure()
    return () => {
      cancelAnimationFrame(frame)
      resize.disconnect()
      if (target) target.style.scrollMarginBottom = previousMargin
      window.removeEventListener('scroll', measure, true)
      window.removeEventListener('resize', measure)
      window.visualViewport?.removeEventListener('resize', measure)
    }
  }, [target, card])
  return position
}
