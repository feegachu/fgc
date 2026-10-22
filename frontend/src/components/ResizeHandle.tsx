import { useRef, useState } from 'react'
import type { CSSProperties, PointerEvent } from 'react'

interface ResizeHandleProps {
  label: string
  value: number
  min: number
  max: number
  className?: string
  style?: CSSProperties
  onStart: () => (delta: number) => void
  onStep: (direction: number) => void
  onReset: () => void
}

/** Captures the pointer so a drag continues outside the narrow separator. */
export function ResizeHandle({
  label,
  value,
  min,
  max,
  className = '',
  style,
  onStart,
  onStep,
  onReset,
}: ResizeHandleProps) {
  const drag = useRef<{ pointerId: number; x: number; update: (delta: number) => void } | null>(null)
  const [active, setActive] = useState(false)

  function finish(event: PointerEvent<HTMLDivElement>, cancel = false) {
    if (drag.current?.pointerId !== event.pointerId) return
    if (cancel) drag.current.update(0)
    drag.current = null
    setActive(false)
    if (event.currentTarget.hasPointerCapture(event.pointerId))
      event.currentTarget.releasePointerCapture(event.pointerId)
  }

  return (
    <div
      role="separator"
      tabIndex={0}
      aria-label={label}
      aria-orientation="vertical"
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={Math.round(value)}
      title={`${label}: 드래그 또는 좌우 방향키로 조절 · 두 번 클릭 또는 Home으로 기본 너비 복원`}
      className={`resize-handle ${className} ${active ? 'is-resizing' : ''}`}
      style={style}
      onPointerDown={(event) => {
        if (event.button !== 0 || !event.isPrimary) return
        event.preventDefault()
        event.stopPropagation()
        event.currentTarget.focus({ preventScroll: true })
        event.currentTarget.setPointerCapture(event.pointerId)
        drag.current = { pointerId: event.pointerId, x: event.clientX, update: onStart() }
        setActive(true)
      }}
      onPointerMove={(event) => {
        if (drag.current?.pointerId === event.pointerId) drag.current.update(event.clientX - drag.current.x)
      }}
      onPointerUp={(event) => finish(event)}
      onPointerCancel={(event) => finish(event, true)}
      onLostPointerCapture={() => {
        drag.current = null
        setActive(false)
      }}
      onClick={(event) => event.stopPropagation()}
      onDoubleClick={(event) => {
        event.stopPropagation()
        onReset()
      }}
      onKeyDown={(event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home'].includes(event.key)) return
        event.preventDefault()
        event.stopPropagation()
        if (event.key === 'Home') onReset()
        else onStep((event.key === 'ArrowLeft' ? -1 : 1) * (event.shiftKey ? 5 : 1))
      }}
    />
  )
}
