import { useId, useState, type ReactNode } from 'react'
import './disabled-button-hint.css'

export function DisabledButtonHint({ disabled, reason, children }: { disabled: boolean; reason: string; children: ReactNode }) {
  const id = useId()
  const [open, setOpen] = useState(false)
  return <span className="disabled-button-hint" tabIndex={disabled ? 0 : undefined} aria-describedby={disabled ? id : undefined}
    onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)} onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}
    onKeyDown={event => { if (event.key === 'Escape') setOpen(false) }}>
    {children}
    {disabled && open && <span id={id} role="tooltip" className="disabled-button-tooltip">{reason}</span>}
  </span>
}
