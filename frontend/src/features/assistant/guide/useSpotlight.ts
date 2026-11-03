import { useEffect, useState } from 'react'
import type { CSSProperties, RefObject } from 'react'

export function useSpotlight(target: HTMLElement | null, card: RefObject<HTMLElement | null>) {
  const [position, setPosition] = useState<{
    box: { left: number; top: number; width: number; height: number } | null
    card: CSSProperties
  }>({ box: null, card: { right: 16, bottom: 16 } })
  useEffect(() => {
    let frame = 0
    let placementFrame = 0
    let preparedWidth: number | null = null
    const alignTarget = () => {
      if (!target?.isConnected) return
      target.scrollIntoView?.({ block: 'nearest', inline: 'nearest', behavior: 'instant' })
      const room = Math.min(460, window.innerHeight / 2) + 40
      const overflow = target.getBoundingClientRect().bottom - (window.innerHeight - room)
      if (overflow <= 0) return
      // scroll-margin can be consumed by an overflow-hidden card before the page scrolls.
      // Move the actual page (or modal body) so the real target has room for its coach.
      const modalBody = target.closest<HTMLElement>('.modal-body')
      if (modalBody) modalBody.scrollBy?.({ top: overflow, behavior: 'instant' })
      else window.scrollBy?.({ top: overflow, behavior: 'instant' })
    }
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
          alignTarget()
          // The anchored card adds page scroll room in this render. Re-align once after
          // its layout commits; browser scroll anchoring can otherwise undo the first move.
          cancelAnimationFrame(placementFrame)
          placementFrame = requestAnimationFrame(() => {
            if (target.isConnected) {
              alignTarget()
              measure()
            }
          })
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
    const resize = new ResizeObserver(() => {
      // A result panel can move the target without resizing the target itself.
      // Re-align on layout changes, while ordinary user scroll only updates the attachment.
      preparedWidth = null
      measure()
    })
    if (target) resize.observe(target)
    if (card.current) resize.observe(card.current)
    const workspace = document.getElementById('main-content')
    if (workspace) resize.observe(workspace)
    window.addEventListener('scroll', measure, true)
    window.addEventListener('resize', measure)
    window.visualViewport?.addEventListener('resize', measure)
    measure()
    return () => {
      cancelAnimationFrame(frame)
      cancelAnimationFrame(placementFrame)
      resize.disconnect()
      window.removeEventListener('scroll', measure, true)
      window.removeEventListener('resize', measure)
      window.visualViewport?.removeEventListener('resize', measure)
    }
  }, [target, card])
  return position
}
