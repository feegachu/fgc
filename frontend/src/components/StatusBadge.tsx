import { Children, type ReactNode } from 'react'
import { screenMessage } from '../lib/screenText'
export type StatusTone = 'success' | 'info' | 'review' | 'warning' | 'risk' | 'error' | 'neutral'
export type BadgeKind = 'status' | 'category' | 'severity' | 'text'
export type TableValuePresentation = 'badge' | 'text' | 'dot'
export function StatusBadge({
  tone = 'neutral',
  kind = 'status',
  optionCount = 0,
  tablePresentation,
  children,
}: {
  tone?: StatusTone
  kind?: BadgeKind
  /** Full column choices, independent of filtering and the current page. */
  optionCount?: number
  tablePresentation?: TableValuePresentation
  children: ReactNode
}) {
  return (
    <span
      className={`status-badge status-badge-${tone}`}
      data-badge-kind={kind}
      data-table-presentation={tablePresentation ?? (optionCount >= 4 ? 'badge' : 'text')}
    >
      {Children.map(children, (child) => (typeof child === 'string' && child ? screenMessage(child) : child))}
    </span>
  )
}

// Category colors are stable for the same code, including server-defined values.
function categoryTone(value: string | number): StatusTone {
  const tones: StatusTone[] = ['info', 'review', 'success', 'warning', 'neutral']
  let hash = 0
  for (const char of String(value)) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return tones[hash % tones.length]
}

export function CategoryBadge({
  value,
  children,
  optionCount,
  tablePresentation,
}: {
  value?: string | number | null
  children?: ReactNode
  optionCount: number
  tablePresentation?: TableValuePresentation
}) {
  const key = value ?? (typeof children === 'string' || typeof children === 'number' ? children : '')
  if (key === '' || key === '-' || key === String.fromCharCode(0x2014)) return <>-</>
  return (
    <StatusBadge
      kind="category"
      tone={categoryTone(key)}
      optionCount={optionCount}
      tablePresentation={tablePresentation}
    >
      {children ?? value}
    </StatusBadge>
  )
}

const valueTones: Record<string, StatusTone> = {
  ACTIVE: 'success',
  APPLIED: 'success',
  COMPLETED: 'success',
  CONFIRMED: 'success',
  FINALIZED: 'success',
  PAID: 'success',
  POSTED: 'success',
  INCLUDED: 'success',
  MATCHED: 'success',
  SUCCESS: 'success',
  RUNNING: 'info',
  PROCESSING: 'info',
  PENDING: 'info',
  DRAFT: 'info',
  NEW: 'info',
  PLANNED: 'info',
  HOLD: 'review',
  REVIEW_REQUIRED: 'review',
  IN_REVIEW: 'review',
  REVERSED: 'review',
  UNPAID: 'warning',
  FAILED: 'error',
  ERROR: 'error',
  REJECTED: 'error',
}
export function StatusValue({
  value,
  label,
  optionCount = 0,
  tablePresentation,
}: {
  value?: string | null
  label?: string
  optionCount?: number
  tablePresentation?: TableValuePresentation
}) {
  if (!value) return <>-</>
  return (
    <StatusBadge tone={valueTones[value] ?? 'neutral'} optionCount={optionCount} tablePresentation={tablePresentation}>
      {label || value}
    </StatusBadge>
  )
}
