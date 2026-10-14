import { cloneElement, useId } from 'react'
import type { ReactElement, ReactNode, HTMLAttributes } from 'react'
export interface FieldProps {
  label: string
  labelAccessory?: ReactNode
  className?: string
  required?: boolean
  helper?: string
  error?: string
  children: ReactElement<HTMLAttributes<HTMLElement> & { id?: string; required?: boolean }>
}
export function Field({ label, labelAccessory, className = '', required, helper, error, children }: FieldProps) {
  const generatedId = useId()
  const id = children.props.id ?? generatedId
  const messageId = `${id}-message`
  const fieldLabel = (
    <label className={labelAccessory ? undefined : 'field-label'} htmlFor={id}>
      {label}
      {required && (
        <span className="field-required" aria-hidden="true">
          {' '}
          *
        </span>
      )}
    </label>
  )
  return (
    <div className={`field ${className} ${error ? 'is-error' : ''}`}>
      {labelAccessory ? (
        <div className="field-label">
          {fieldLabel}
          {labelAccessory}
        </div>
      ) : (
        fieldLabel
      )}
      {cloneElement(children, {
        id,
        required: required ?? children.props.required,
        'aria-invalid': error ? true : undefined,
        'aria-describedby':
          [children.props['aria-describedby'], (error || helper) && messageId].filter(Boolean).join(' ') || undefined,
        className: `field-control ${children.props.className ?? ''}`,
      })}
      {(error || helper) && (
        <p id={messageId} className={error ? 'field-error' : 'field-helper'} role={error ? 'alert' : undefined}>
          {error || helper}
        </p>
      )}
    </div>
  )
}
