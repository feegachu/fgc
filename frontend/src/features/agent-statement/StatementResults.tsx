import type { ReactNode } from 'react'
export function StatementResults({ children, className, titleId, label, busy }: { children: ReactNode; className: string; titleId?: string; label?: string; busy?: boolean }) {
  return <section data-guide-feature="features.agent-statement.agent-statement-page.list-panel" data-guide-target="statement.results"
    className={`surface ${className}`} aria-labelledby={titleId} aria-label={label} aria-busy={busy}>{children}</section>
}
