import type { ReactNode } from 'react'
export function PrecheckResultPanel({ children, className = '', titleId }: { children: ReactNode; className?: string; titleId: string }) {
  return <section data-guide-feature="features.transaction.confirmation-panels.section" data-guide-target="payment.precheck.result"
    className={`surface transaction-gate-panel ${className}`} aria-labelledby={titleId}>{children}</section>
}
