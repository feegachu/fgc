import { cloneElement, useId } from 'react'
import type { ReactElement, HTMLAttributes } from 'react'
export interface FieldProps {
  label: string
  required?: boolean
  helper?: string
  error?: string
  children: ReactElement<HTMLAttributes<HTMLElement> & { id?: string; required?: boolean }>
}
export function Field({ label, required, helper, error, children }: FieldProps) {
  const generatedId = useId()
  const id = children.props.id ?? generatedId
  const messageId = `${id}-message`
  return <div className={`field ${error ? 'is-error' : ''}`}><label className="field-label" htmlFor={id}>{label}{required && <span className="field-required" aria-hidden="true"> *</span>}</label>{cloneElement(children, { id, required, 'aria-invalid': error ? true : undefined, 'aria-describedby': [children.props['aria-describedby'], (error || helper) && messageId].filter(Boolean).join(' ') || undefined, className: `field-control ${children.props.className ?? ''}` })}{(error || helper) && <p id={messageId} className={error ? 'field-error' : 'field-helper'} role={error ? 'alert' : undefined}>{error || helper}</p>}</div>
}
