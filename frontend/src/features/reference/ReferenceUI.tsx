import { useId } from 'react'
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
}: {
  tabs: readonly { id: T; label: string }[]
  active: T
  onChange: (id: T) => void
  children: ReactNode
  panelClassName?: string
}) {
  const id = useId()
  return (
    <>
      <div className="reference-tabs" role="tablist" aria-label="조회 항목">
        {tabs.map((tab, index) => (
          <button
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
        className={`surface reference-panel ${panelClassName}`}
        role="tabpanel"
        aria-labelledby={`${id}-${active}`}
        tabIndex={0}
      >
        {children}
      </section>
    </>
  )
}
export function ReferenceHeader({ title, description }: { title: string; description: string }) {
  return (
    <header className="page-header">
      <div className="page-header-copy">
        <h1 className="page-title">{title}</h1>
        <p className="page-description">{description}</p>
      </div>
    </header>
  )
}
