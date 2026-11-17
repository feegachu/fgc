/** Keep the check centered without depending on an icon font's baseline. */
export function GateStatusIcon({ state }: { state: string }) {
  return (
    <span className="transaction-gate-icon" aria-hidden="true">
      {state === 'pass' ? (
        <svg className="transaction-gate-check" viewBox="0 0 24 24" fill="none">
          <path d="m6 12 4 4 8-8" pathLength="1" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      ) : (
        <span className="material-symbols-rounded">{state === 'fail' ? 'close' : 'more_horiz'}</span>
      )}
    </span>
  )
}
