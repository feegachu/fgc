import { useLayoutEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { canAnimate } from '../../lib/motion'
import { useWorkspaceStore } from '../../stores/workspace'
export function WorkspaceTabs({ activeId }: { activeId?: string }) {
  const tabs = useWorkspaceStore((state) => state.tabs)
  const closeTab = useWorkspaceStore((state) => state.closeTab)
  const navigate = useNavigate()
  const rail = useRef<HTMLDivElement>(null)
  const [{ overflow, atStart, atEnd }, setScrollState] = useState({ overflow: false, atStart: true, atEnd: true })
  useLayoutEffect(() => {
    const element = rail.current
    const bar = element?.parentElement
    if (!element || !bar) return
    let overflowing = false
    const updateEdges = () => {
      const atStart = element.scrollLeft <= 1
      const atEnd = element.scrollLeft >= element.scrollWidth - element.clientWidth - 1
      setScrollState(previous => previous.overflow === overflowing && previous.atStart === atStart && previous.atEnd === atEnd
        ? previous : { overflow: overflowing, atStart, atEnd })
    }
    const fitTabs = (revealActive = false) => {
      const { paddingLeft, paddingRight } = getComputedStyle(bar)
      const available = bar.getBoundingClientRect().width - parseFloat(paddingLeft) - parseFloat(paddingRight)
      overflowing = tabs.length > Math.max(1, Math.round(available / 180))
      const width = element.getBoundingClientRect().width
      if (width <= 0) return
      const slots = Math.max(1, Math.round(width / 180))
      const previousWidth = element.querySelector<HTMLElement>('.workspace-tab')?.getBoundingClientRect().width || 180
      const tabWidth = width / slots
      let first = Math.max(0, Math.min(Math.round(element.scrollLeft / previousWidth), tabs.length - slots))
      const activeIndex = tabs.findIndex(tab => tab.id === activeId)
      if (revealActive && activeIndex >= 0) {
        if (activeIndex < first) first = activeIndex
        else if (activeIndex >= first + slots) first = activeIndex - slots + 1
      }
      // Use the measured CSS-pixel rail width, including fractional OS/zoom layouts.
      element.style.setProperty('--layout-workspace-tab-width', `${tabWidth}px`)
      element.scrollTo?.({ left: first * tabWidth, behavior: 'instant' })
      updateEdges()
    }
    fitTabs(true)
    const onResize = () => fitTabs()
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(onResize) : null
    observer?.observe(element)
    observer?.observe(bar)
    element.addEventListener('scroll', updateEdges)
    window.addEventListener('resize', onResize)
    return () => {
      observer?.disconnect()
      element.removeEventListener('scroll', updateEdges)
      window.removeEventListener('resize', onResize)
    }
  }, [tabs, activeId, overflow])

  function scrollToEdge(edge: 'start' | 'end') {
    const element = rail.current
    if (!element || (edge === 'start' ? atStart : atEnd)) return
    element.scrollTo({ left: edge === 'start' ? 0 : element.scrollWidth, behavior: canAnimate() ? 'smooth' : 'instant' })
  }

  return (
    <nav className="workspace-tab-bar" aria-label="열린 업무 화면">
      <button data-guide-feature="app.shell.workspace-tabs.button-3"
        type="button"
        className="workspace-tab-overflow"
        aria-label="첫 번째 업무 탭으로 이동"
        aria-disabled={atStart}
        hidden={!overflow}
        onClick={() => scrollToEdge('start')}
      >
        <span className="material-symbols-rounded" aria-hidden="true">
          keyboard_arrow_left
        </span>
      </button>
      <div className="workspace-tabs" ref={rail}>
        {tabs.map((tab, index) => (
          <div
            className={`workspace-tab ${tab.id === activeId ? 'is-active' : ''} ${tab.modified ? 'is-modified' : ''}`}
            key={tab.id}
          >
            <Link data-guide-feature="app.shell.workspace-tabs.link" className="workspace-tab-link" title={tab.title} to={tab.href} aria-current={tab.id === activeId ? 'page' : undefined}>
              <span className="material-symbols-rounded" aria-hidden="true">
                {tab.icon}
              </span>
              <span className="workspace-tab-label">{tab.title}</span>
            </Link>
            {tab.modified && <span className="workspace-tab-modified" role="img" aria-label="수정됨" />}
            {index !== 0 && (
              <button data-guide-feature="app.shell.workspace-tabs.button"
                className="workspace-tab-close"
                type="button"
                aria-label={`${tab.title} 탭 닫기`}
                onClick={() => {
                  const next = closeTab(tab.id, activeId ?? '')
                  if (next) void navigate(next)
                }}
              >
                <span className="material-symbols-rounded" aria-hidden="true">
                  close
                </span>
              </button>
            )}
          </div>
        ))}
      </div>
      <button data-guide-feature="app.shell.workspace-tabs.button-2"
        type="button"
        className="workspace-tab-overflow"
        aria-label="마지막 업무 탭으로 이동"
        aria-disabled={atEnd}
        hidden={!overflow}
        onClick={() => scrollToEdge('end')}
      >
        <span className="material-symbols-rounded" aria-hidden="true">
          keyboard_arrow_right
        </span>
      </button>
    </nav>
  )
}
