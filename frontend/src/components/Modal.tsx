import { useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import type { ReactNode, RefObject } from 'react'
const focusSelector =
  'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'
export function Modal({
  open,
  title,
  children,
  footer,
  onClose,
  closeOnBackdrop = false,
  closeOnEscape = true,
  initialFocusRef,
}: {
  open: boolean
  title: string
  children: ReactNode
  footer?: ReactNode
  onClose: () => void
  closeOnBackdrop?: boolean
  closeOnEscape?: boolean
  initialFocusRef?: RefObject<HTMLElement | null>
}) {
  const titleId = useId()
  const dialog = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])
  useEffect(() => {
    if (!open) return
    const previous = document.activeElement as HTMLElement | null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const element = dialog.current!
    const focusable = () =>
      [...element.querySelectorAll<HTMLElement>(focusSelector)].filter(
        (item) => !item.hidden && !item.closest('[hidden]') && item.getAttribute('aria-hidden') !== 'true',
      )
    ;(initialFocusRef?.current ?? focusable()[0] ?? element).focus()
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && closeOnEscape) {
        event.preventDefault()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const items = focusable()
      if (!items.length) {
        event.preventDefault()
        element.focus()
        return
      }
      const first = items[0],
        last = items[items.length - 1]
      if (event.shiftKey && (document.activeElement === first || !element.contains(document.activeElement))) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (document.activeElement === last || !element.contains(document.activeElement))) {
        event.preventDefault()
        first.focus()
      }
    }
    const focusin = (event: FocusEvent) => {
      if (!element.contains(event.target as Node)) (focusable()[0] ?? element).focus()
    }
    document.addEventListener('keydown', keydown)
    document.addEventListener('focusin', focusin)
    return () => {
      document.removeEventListener('keydown', keydown)
      document.removeEventListener('focusin', focusin)
      document.body.style.overflow = previousOverflow
      if (previous?.isConnected) previous.focus()
    }
  }, [open, closeOnEscape, initialFocusRef])
  if (!open) return null
  return createPortal(
    <div
      className="modal-backdrop"
      onClick={(event) => {
        if (closeOnBackdrop && event.target === event.currentTarget) onClose()
      }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1} ref={dialog}>
        <header className="modal-header">
          <h2 className="modal-title" id={titleId}>
            {title}
          </h2>
          <button type="button" className="icon-button" aria-label="모달 닫기" onClick={onClose}>
            <span className="material-symbols-rounded" aria-hidden="true">
              close
            </span>
          </button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-footer">{footer}</footer>}
      </div>
    </div>,
    document.body,
  )
}
