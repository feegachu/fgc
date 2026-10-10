import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { ReactNode } from 'react'
// icon 이면 1차 evidence 마크업처럼 ⓘ 아이콘 버튼을 그리고 label 은 접근 가능한 이름으로만 쓴다.
export function EvidenceLink({
  label,
  children,
  icon = false,
}: {
  label: string
  children: ReactNode
  icon?: boolean
}) {
  const id = useId()
  const trigger = useRef<HTMLButtonElement>(null)
  const popover = useRef<HTMLDivElement>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [open, setOpen] = useState(false)
  const [pinned, setPinned] = useState(false)
  function show() {
    if (timer.current) clearTimeout(timer.current)
    setOpen(true)
  }
  function close() {
    if (!pinned) timer.current = setTimeout(() => setOpen(false), 120)
  }
  useLayoutEffect(() => {
    if (!open) return
    const place = () => {
      const anchor = trigger.current!.getBoundingClientRect()
      const box = popover.current!.getBoundingClientRect()
      const left = Math.max(8, Math.min(anchor.left, window.innerWidth - box.width - 8))
      const top = Math.max(8, Math.min(anchor.bottom + 8, window.innerHeight - box.height - 8))
      popover.current!.style.setProperty('--evidence-x', `${left}px`)
      popover.current!.style.setProperty('--evidence-y', `${top}px`)
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open])
  useEffect(() => {
    if (!open) return
    function dismiss(event: Event) {
      if (event instanceof KeyboardEvent && event.key !== 'Escape') return
      if (
        event.type === 'pointerdown' &&
        (trigger.current?.contains(event.target as Node) || popover.current?.contains(event.target as Node))
      )
        return
      setPinned(false)
      setOpen(false)
    }
    document.addEventListener('pointerdown', dismiss)
    document.addEventListener('keydown', dismiss)
    return () => {
      document.removeEventListener('pointerdown', dismiss)
      document.removeEventListener('keydown', dismiss)
    }
  }, [open])
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )
  return (
    <span className="evidence">
      <button
        ref={trigger}
        className={icon ? 'evidence-trigger' : 'evidence-trigger react-evidence-trigger'}
        type="button"
        aria-label={icon ? label : undefined}
        aria-describedby={open ? id : undefined}
        aria-expanded={open}
        onMouseEnter={show}
        onMouseLeave={close}
        onFocus={show}
        onBlur={(event) => {
          if (!popover.current?.contains(event.relatedTarget)) close()
        }}
        onClick={() => {
          if (timer.current) clearTimeout(timer.current)
          setPinned(!pinned)
          setOpen(!pinned)
        }}
      >
        {icon ? (
          <span className="material-symbols-rounded" aria-hidden="true">
            info
          </span>
        ) : (
          label
        )}
      </button>
      {open &&
        createPortal(
          <div
            id={id}
            role="tooltip"
            ref={popover}
            className="evidence-popover react-evidence-popover"
            onMouseEnter={show}
            onMouseLeave={close}
          >
            <div className="evidence-body">{children}</div>
          </div>,
          document.body,
        )}
    </span>
  )
}
