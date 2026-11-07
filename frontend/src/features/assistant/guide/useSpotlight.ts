import { useEffect, useState } from 'react'
import type { CSSProperties, RefObject } from 'react'
import { useGuideScrollLock } from './useGuideScrollLock'

const HIGHLIGHT_PADDING = 10

export function useSpotlight(target: HTMLElement | null, card: RefObject<HTMLElement | null>, detailed = false,
  presentation?: { side?: 'right' | 'left' | 'above' | 'below' | 'either'; includeLabel?: boolean; sideBoundary?: string; preserveCardPosition?: boolean; rowLimit?: number }) {
  useGuideScrollLock(card)
  const full = Boolean(presentation), preferredSide = presentation?.side
  const includeLabel = presentation?.includeLabel
  const sideBoundary = presentation?.sideBoundary
  const preserveCardPosition = presentation?.preserveCardPosition
  const rowLimit = presentation?.rowLimit
  const [position, setPosition] = useState<{
    box: { left: number; top: number; width: number; height: number } | null
    card: CSSProperties
  }>({ box: null, card: { right: 16, bottom: 16 } })
  useEffect(() => {
    let frame = 0
    let placementFrame = 0
    let scrollFrame = 0
    let preparedWidth: number | null = null
    let preparedPresentation = ''
    let measuredTarget: DOMRect | null = null
    const targetInHeader = Boolean(target?.closest('.app-header'))
    const scrollSmoothly = (offset: number) => {
      cancelAnimationFrame(scrollFrame)
      const start = window.scrollY
      const end = Math.max(0, Math.min(start + offset, document.documentElement.scrollHeight - window.innerHeight))
      if (Math.abs(end - start) < 1) return
      if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
        window.scrollTo({ top: end, behavior: 'instant' })
        return
      }
      const began = performance.now()
      const tick = (now: number) => {
        const t = Math.min(1, (now - began) / 700)
        window.scrollTo({ top: start + (end - start) * (t * t * (3 - 2 * t)), behavior: 'instant' })
        measure()
        if (t < 1) scrollFrame = requestAnimationFrame(tick)
      }
      scrollFrame = requestAnimationFrame(tick)
    }
    const heightLimit = () => Math.min(detailed ? 560 : 460, window.innerHeight * (detailed && window.innerWidth >= 768 ? 0.65 : 0.5))
    const targetRect = () => {
      const rect = target!.getBoundingClientRect()
      if (includeLabel) {
        const label = target!.closest('.field')?.querySelector('.field-label')?.getBoundingClientRect()
        if (label) return new DOMRect(rect.left, Math.min(rect.top, label.top), rect.width, rect.bottom - Math.min(rect.top, label.top))
      }
      if (rowLimit && !target!.matches('table')) {
        const rows = [...target!.querySelectorAll<HTMLElement>('tbody > tr, ol > li')]
          .filter(row => row.getClientRects().length > 0).slice(0, rowLimit)
        const last = rows.at(-1)?.getBoundingClientRect()
        if (last) return new DOMRect(rect.left, rect.top, rect.width, Math.max(0, Math.min(rect.bottom, last.bottom) - rect.top))
      }
      if (!detailed || !target!.matches('table')) return rect
      const header = target!.querySelector<HTMLElement>(':scope > thead')
      const rows = [...target!.querySelectorAll<HTMLElement>(':scope > tbody > tr')]
        .filter(row => row.getClientRects().length > 0).slice(0, 3)
      const first = header ?? rows[0], last = rows.at(-1) ?? header
      if (!first || !last) return rect
      const viewport = target!.closest('.data-table-viewport')?.getBoundingClientRect()
      const left = Math.max(rect.left, viewport?.left ?? rect.left)
      const right = Math.min(rect.right, viewport?.right ?? rect.right)
      const top = first.getBoundingClientRect().top
      return new DOMRect(left, top, Math.max(0, right - left), Math.max(0, last.getBoundingClientRect().bottom - top))
    }
    const alignTarget = () => {
      if (!target?.isConnected) return
      if (targetInHeader) return
      const tableViewport = target.closest<HTMLElement>('.data-table-viewport')
      if (tableViewport && !target.matches('table')) {
        const element = target.getBoundingClientRect(), viewport = tableViewport.getBoundingClientRect()
        const offset = element.right > viewport.right ? element.right - viewport.right + 12
          : element.left < viewport.left ? element.left - viewport.left - 12 : 0
        if (offset) tableViewport.scrollBy({ left: offset, behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })
      }
      if (detailed) {
        const rect = targetRect()
        const modalBody = target.closest<HTMLElement>('.modal-body')
        const headerBottom = document.querySelector('.app-header')?.getBoundingClientRect().bottom ?? 0
        const top = Math.max(0, headerBottom, modalBody?.getBoundingClientRect().top ?? 0) + 16
        const bottom = Math.min(window.innerHeight, modalBody?.getBoundingClientRect().bottom ?? window.innerHeight) - 16
        // Keep an already visible element still. For large panels, explain their top
        // portion instead of scrolling to the last row to make room for the coach.
        const visibleEnd = Math.min(rect.bottom, rect.top + Math.min(160, Math.max(0, bottom - top)))
        const offset = rect.top < top ? rect.top - top : visibleEnd > bottom ? visibleEnd - bottom : 0
        if (offset) {
          if (modalBody) modalBody.scrollBy?.({ top: offset, behavior: 'instant' })
          else scrollSmoothly(offset)
        }
        return
      }
      target.scrollIntoView?.({ block: 'nearest', inline: 'nearest', behavior: 'instant' })
      const room = heightLimit() + 40
      const rect = target.getBoundingClientRect()
      const modalBody = target.closest<HTMLElement>('.modal-body')
      const headerBottom = document.querySelector('.app-header')?.getBoundingClientRect().bottom ?? 0
      const topBoundary = Math.max(0, headerBottom, modalBody?.getBoundingClientRect().top ?? 0) + 16
      const topGap = rect.top - topBoundary
      // Native nearest scrolling ignores the fixed header. Restore small targets hidden
      // behind it, and keep them visible when reserving coach space.
      const overflow = rect.bottom - (window.innerHeight - room)
      // Large evidence panels must scroll partly above the viewport to expose their coach.
      const fitsAboveCoach = rect.height <= window.innerHeight - room - topBoundary
      let offset = Math.max(0, overflow)
      if (fitsAboveCoach) offset = topGap < 0 ? topGap : Math.min(offset, topGap)
      if (offset === 0) return
      // scroll-margin can be consumed by an overflow-hidden card before the page scrolls.
      // Move the actual page (or modal body) so the real target has room for its coach.
      if (modalBody) modalBody.scrollBy?.({ top: offset, behavior: 'instant' })
      else window.scrollBy?.({ top: offset, behavior: 'instant' })
    }
    const measure = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const width = document.documentElement.clientWidth || window.innerWidth
        const height = window.innerHeight
        const workspace = (target?.closest('#app-sidebar') ? document.querySelector('.app-shell') : document.getElementById('main-content'))?.getBoundingClientRect()
        const workspaceLeft = Math.max(0, workspace?.left ?? 0)
        const workspaceRight = Math.min(width, workspace?.right || width)
        const cardLeftBoundary = workspaceRight - workspaceLeft >= 224 ? workspaceLeft : 0
        if (full && target?.isConnected) {
          const rect = targetRect()
          const popupWidth = includeLabel ? target.matches('input[type="date"]') ? 300 : 280 : 0
          const panel = sideBoundary ? target.closest(sideBoundary)?.getBoundingClientRect() : undefined
          const context = new DOMRect(panel?.left ?? rect.left, rect.top, Math.max(panel?.width ?? rect.width, popupWidth), rect.height)
          const headerBottom = Math.max(0, document.querySelector('.app-header')?.getBoundingClientRect().bottom ?? 0)
          const topBoundary = headerBottom + 16
          const rightRoom = workspaceRight - context.right - 36
          const leftRoom = context.left - cardLeftBoundary - 36
          const side = preferredSide === 'above' || preferredSide === 'below' ? null
            : preferredSide === 'left' && leftRoom >= 280 ? 'left'
              : preferredSide && rightRoom >= 280 ? 'right' : preferredSide && leftRoom >= 280 ? 'left' : null
          const cardWidth = Math.min(420, side === 'right' ? rightRoom : side === 'left' ? leftRoom : workspaceRight - cardLeftBoundary - 24)
          const maxHeight = Math.max(160, height - topBoundary - 12)
          const cardHeight = Math.min(Math.max(card.current?.scrollHeight ?? 0, card.current?.offsetHeight ?? 0) || 280, maxHeight)
          const cardLeft = side === 'right' ? context.right + 24 : side === 'left' ? context.left - cardWidth - 24
            : Math.max(cardLeftBoundary + 12, Math.min(rect.left, workspaceRight - cardWidth - 12))
          let cardTop = side ? Math.max(topBoundary, Math.min(context.top, height - cardHeight - 12))
            : preferredSide === 'above' ? context.top - cardHeight - 24 : context.bottom + 24
          if (!side && preferredSide !== 'below' && cardTop + cardHeight > height - 12) cardTop = context.top - cardHeight - 24
          cardTop = Math.max(topBoundary, Math.min(cardTop, height - cardHeight - 12))
          setPosition(previous => ({
            box: {
              left: Math.max(workspaceLeft, rect.left - HIGHLIGHT_PADDING),
              top: Math.max(targetInHeader ? 0 : headerBottom + 8, rect.top - HIGHLIGHT_PADDING),
              width: Math.max(0, Math.min(workspaceRight, rect.right + HIGHLIGHT_PADDING) - Math.max(workspaceLeft, rect.left - HIGHLIGHT_PADDING)),
              height: Math.max(0, Math.min(height, rect.bottom + HIGHLIGHT_PADDING) - Math.max(targetInHeader ? 0 : headerBottom + 8, rect.top - HIGHLIGHT_PADDING)),
            },
            card: preserveCardPosition && previous.box && typeof previous.card.left === 'number' && typeof previous.card.top === 'number' && typeof previous.card.width === 'number'
              ? { ...previous.card,
                left: Math.max(cardLeftBoundary + 12, Math.min(previous.card.left, workspaceRight - Number(previous.card.width) - 12)),
                top: Math.max(topBoundary, Math.min(previous.card.top, height - cardHeight - 12)),
                width: Math.min(Number(previous.card.width), workspaceRight - cardLeftBoundary - 24), maxHeight }
              : { left: cardLeft, top: cardTop, right: 'auto', bottom: 'auto', width: cardWidth, maxHeight, overflowY: 'auto' },
          }))
          if (preparedPresentation !== `${width}:${height}` && !targetInHeader && !preserveCardPosition) {
            preparedPresentation = `${width}:${height}`
            // Reveal the whole card, reserving a full explanation below it only on narrow screens.
            const bottom = height - 20 - (side ? 0 : cardHeight + 24)
            const offset = preferredSide === 'above'
              ? context.top < topBoundary ? context.top - topBoundary : Math.max(0, context.bottom - height + 20)
              : context.top < topBoundary || context.height > bottom - topBoundary
                ? context.top - topBoundary : Math.max(0, context.bottom - bottom)
            scrollSmoothly(offset)
          }
          return
        }
        const cardWidth = Math.min(detailed ? 420 : 360, Math.max(0, workspaceRight - cardLeftBoundary - 24))
        let rect = target?.isConnected ? targetRect() : null
        // A resize can push the coach offscreen. A position-only change can be user scroll.
        if (rect && measuredTarget
          && (rect.width !== measuredTarget.width || rect.height !== measuredTarget.height)) {
          preparedWidth = null
        }
        if (target?.isConnected && preparedWidth !== workspaceRight - workspaceLeft) {
          preparedWidth = workspaceRight - workspaceLeft
          // Leave room below the real target for the coach, including at the end of a page.
          alignTarget()
          rect = targetRect()
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
        measuredTarget = rect
        const headerBottom = Math.max(0, document.querySelector('.app-header')?.getBoundingClientRect().bottom ?? 0)
        const naturalHeight = Math.max(card.current?.offsetHeight || 0, card.current?.scrollHeight || 0)
        const cardHeight = Math.min(naturalHeight || heightLimit(), heightLimit())
        const topBoundary = headerBottom + 8
        let cardLeft = rect ? Math.max(cardLeftBoundary + 12, Math.min(rect.left, workspaceRight - cardWidth - 12)) : 0
        let cardTop = rect ? rect.bottom + 24 : 0
        let maxHeight = heightLimit()
        if (rect && cardTop + cardHeight > height - 12) {
          if (rect.top - 24 - cardHeight >= topBoundary) {
            cardTop = rect.top - 24 - cardHeight
          } else if (rect.right + 24 + cardWidth <= workspaceRight - 12) {
            cardLeft = rect.right + 24
            cardTop = Math.max(topBoundary, Math.min(rect.top, height - cardHeight - 12))
          } else if (rect.left - 24 - cardWidth >= cardLeftBoundary + 12) {
            cardLeft = rect.left - 24 - cardWidth
            cardTop = Math.max(topBoundary, Math.min(rect.top, height - cardHeight - 12))
          } else {
            // Keep the target uncovered even when neither side fits a full card.
            // The card scrolls internally in the larger free vertical slot.
            const below = Math.max(0, height - 12 - rect.bottom - 24)
            const above = Math.max(0, rect.top - 24 - topBoundary)
            maxHeight = Math.min(maxHeight, Math.max(below, above))
            cardTop = above > below ? topBoundary : rect.bottom + 24
          }
        }
        // A fixed card cannot be scrolled back into view. Keep its controls reachable
        // even when ordinary user scrolling moves the highlighted target offscreen.
        const availableHeight = Math.max(0, height - topBoundary - 12)
        if (maxHeight < Math.min(160, availableHeight)) {
          // An oversized target leaves no usable slot: use the documented bottom card.
          maxHeight = Math.min(heightLimit(), availableHeight)
          cardTop = height - Math.min(cardHeight, maxHeight) - 12
        }
        maxHeight = Math.min(maxHeight, availableHeight)
        cardTop = Math.max(topBoundary, Math.min(cardTop, height - Math.min(cardHeight, maxHeight) - 12))
        const box = rect ? {
          left: Math.max(workspaceLeft, rect.left - HIGHLIGHT_PADDING),
          top: Math.max(targetInHeader ? 0 : headerBottom + 8, rect.top - HIGHLIGHT_PADDING),
          width: Math.max(0, Math.min(workspaceRight, rect.right + HIGHLIGHT_PADDING) - Math.max(workspaceLeft, rect.left - HIGHLIGHT_PADDING)),
          height: Math.max(0, Math.min(height, rect.bottom + HIGHLIGHT_PADDING) - Math.max(targetInHeader ? 0 : headerBottom + 8, rect.top - HIGHLIGHT_PADDING)),
        } : null
        setPosition({
          box: box?.width && box.height ? box : null,
          card: rect ? {
            left: cardLeft, top: cardTop, maxHeight, overflowY: 'auto',
            right: 'auto', bottom: 'auto', width: cardWidth,
          } : { left: 'auto', top: 'auto', right: 16, bottom: 16, width: cardWidth },
        })
      })
    }
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => {
      // The coach resizes while its placement changes. Re-measure it without scrolling
      // the user's page back to the old target. Real target/viewport size changes are
      // detected in measure(), independently of this observer notification.
      measure()
    })
    if (target) resize?.observe(target)
    if (card.current) resize?.observe(card.current)
    const workspace = document.getElementById('main-content')
    if (workspace) resize?.observe(workspace)
    // A split pane can move or resize the target without a viewport event.
    const layoutPoll = !resize && target ? window.setInterval(() => {
      const rect = target.isConnected ? targetRect() : null
      if (rect?.left !== measuredTarget?.left || rect?.top !== measuredTarget?.top
        || rect?.width !== measuredTarget?.width || rect?.height !== measuredTarget?.height) {
        measure()
      }
    }, 100) : undefined
    window.addEventListener('scroll', measure, true)
    window.addEventListener('resize', measure)
    window.visualViewport?.addEventListener('resize', measure)
    measure()
    return () => {
      cancelAnimationFrame(frame)
      cancelAnimationFrame(placementFrame)
      cancelAnimationFrame(scrollFrame)
      resize?.disconnect()
      window.clearInterval(layoutPoll)
      window.removeEventListener('scroll', measure, true)
      window.removeEventListener('resize', measure)
      window.visualViewport?.removeEventListener('resize', measure)
    }
  }, [target, card, detailed, full, preferredSide, includeLabel, sideBoundary, preserveCardPosition, rowLimit])
  return position
}
