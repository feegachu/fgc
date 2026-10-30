import { useId } from 'react'
import type { ReactNode } from 'react'
import { CellDisclosure } from '../../components/CellDisclosure'
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
          <button data-guide-feature="features.reference.reference-ui.button"
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
      <section data-guide-feature="features.reference.reference-ui.section"
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
  if (text.length <= limit) return <>{text}</>
  return <CellDisclosure preview={text} singleLine={singleLine} />
}
