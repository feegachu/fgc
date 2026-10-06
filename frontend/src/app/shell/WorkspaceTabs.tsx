import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useWorkspaceStore } from '../../stores/workspace'
export function WorkspaceTabs({ activeId }: { activeId?: string }) {
  const tabs = useWorkspaceStore((state) => state.tabs)
  const closeTab = useWorkspaceStore((state) => state.closeTab)
  const navigate = useNavigate()
  const rail = useRef<HTMLDivElement>(null)
  const [overflow, setOverflow] = useState(false)
  useEffect(() => {
    const element = rail.current!
    const update = () => setOverflow(element.scrollWidth > element.clientWidth + 1)
    element
      .querySelector<HTMLElement>('[aria-current="page"]')
      ?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
    update()
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(update) : null
    observer?.observe(element)
    window.addEventListener('resize', update)
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', update)
    }
  }, [tabs, activeId])
  return (
    <nav className="workspace-tab-bar" aria-label="열린 업무 화면">
      <div className="workspace-tabs" ref={rail}>
        {tabs.map((tab) => (
          <div
            className={`workspace-tab ${tab.id === activeId ? 'is-active' : ''} ${tab.modified ? 'is-modified' : ''}`}
            key={tab.id}
          >
            <Link className="workspace-tab-link" to={tab.href} aria-current={tab.id === activeId ? 'page' : undefined}>
              <span className="material-symbols-rounded" aria-hidden="true">
                {tab.icon}
              </span>
              <span className="workspace-tab-label">{tab.title}</span>
            </Link>
            {tab.modified && <span className="workspace-tab-modified" role="img" aria-label="수정됨" />}
            {tab.id !== 'DASH-W01' && (
              <button
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
      <button
        type="button"
        className="workspace-tab-overflow"
        aria-label="열린 화면 더 보기"
        hidden={!overflow}
        onClick={() => rail.current?.scrollBy({ left: 220, behavior: 'smooth' })}
      >
        <span className="material-symbols-rounded" aria-hidden="true">
          keyboard_arrow_right
        </span>
      </button>
    </nav>
  )
}
