import { useId, useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '../../lib/api/client'
import { errorText } from '../../lib/format'
export function QueryState({ loading, error }: { loading?: boolean; error?: Error | null }) {
  if (error)
    return (
      <p role="alert">
        {error instanceof ApiError && error.code === 'FGC-COMMON-500'
          ? `요청 ID: ${error.requestId ?? '-'}`
          : errorText(error)}
      </p>
    )
  return loading ? <p role="status">불러오는 중…</p> : null
}
export function ReferenceTabs<T extends string>({
  tabs,
  active,
  onChange,
  children,
  panelClassName = '',
  label = '조회 항목',
}: {
  tabs: readonly { id: T; label: string }[]
  active: T
  onChange: (id: T) => void
  children: ReactNode
  panelClassName?: string
  label?: string
}) {
  const id = useId()
  return (
    <>
      <div className="tab-list" role="tablist" aria-label={label}>
        {tabs.map((tab, index) => (
          <button
            className="tab-button"
            key={tab.id}
            id={`${id}-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={active === tab.id}
            aria-controls={`${id}-panel`}
            tabIndex={active === tab.id ? 0 : -1}
            onClick={() => onChange(tab.id)}
            onKeyDown={(event) => {
              const next =
                event.key === 'ArrowRight'
                  ? (index + 1) % tabs.length
                  : event.key === 'ArrowLeft'
                    ? (index + tabs.length - 1) % tabs.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? tabs.length - 1
                        : null
              if (next !== null) {
                event.preventDefault()
                onChange(tabs[next].id)
                document.getElementById(`${id}-${tabs[next].id}`)?.focus()
              }
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <section
        id={`${id}-panel`}
        className={`tab-panel ${panelClassName}`}
        role="tabpanel"
        aria-labelledby={`${id}-${active}`}
        tabIndex={0}
      >
        {children}
      </section>
    </>
  )
}
// The legacy tables disclose only text that is actually clipped at the current width.
export function ReferenceText({
  value,
  limit,
  singleLine = false,
}: {
  value?: string | null
  limit: number
  singleLine?: boolean
}) {
  const text = value || '-'
  const container = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const node = container.current
    if (!node) return
    const preview = node.querySelector<HTMLElement>('.table-cell-preview')!
    const details = node.querySelector<HTMLDetailsElement>('details')!
    const sync = () => {
      if (!preview.clientWidth) return
      details.hidden =
        preview.scrollWidth <= preview.clientWidth + 1 && preview.scrollHeight <= preview.clientHeight + 1
      if (details.hidden) details.open = false
    }
    const observer = new ResizeObserver(sync)
    observer.observe(preview)
    sync()
    void document.fonts?.ready.then(sync)
    return () => observer.disconnect()
  }, [text, limit])
  if (text.length <= limit) return <>{text}</>
  return (
    <div className="table-cell-disclosure" ref={container}>
      <span className={`table-cell-preview ${singleLine ? 'is-single-line' : ''}`}>{text}</span>
      <details className="table-cell-details" hidden>
        <summary>
          <span className="table-cell-more">전체 보기</span>
          <span className="table-cell-less">접기</span>
          <span className="material-symbols-rounded table-cell-chevron" aria-hidden="true">
            expand_more
          </span>
        </summary>
        <p className="table-cell-full">{text}</p>
      </details>
    </div>
  )
}
