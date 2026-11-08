import type { ReactNode } from 'react'
export function LedgerOverview({ children, className = '', busy = false }: { children: ReactNode; className?: string; busy?: boolean }) {
  return <section data-guide-feature="features.ledger.ledger-page.section" data-guide-target="ledger.overview" className={`surface ${className}`} aria-busy={busy}>{children}</section>
}
