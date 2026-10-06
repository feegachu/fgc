import type { ReactNode } from 'react'
export type StatusTone = 'success' | 'info' | 'review' | 'warning' | 'risk' | 'error' | 'neutral'
export function StatusBadge({ tone = 'neutral', children }: { tone?: StatusTone; children: ReactNode }) {
  return <span className={`status-badge status-badge-${tone}`}>{children}</span>
}
