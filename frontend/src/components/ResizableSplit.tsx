import { useRef, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { ResizeHandle } from './ResizeHandle'
import { clamp, readLayout, saveLayout } from './resizeLayout'
import './resize.css'

type ResizableSplitProps = {
  storageKey: string
  label: string
  className?: string
  id?: string
  defaultRatio?: number
} & (
  | { primary: ReactNode; secondary: ReactNode; children?: never }
  | { children: [ReactNode, ReactNode]; primary?: never; secondary?: never }
)

export function ResizableSplit({ storageKey, ...props }: ResizableSplitProps) {
  return <SplitContents key={storageKey} storageKey={storageKey} {...props} />
}

function SplitContents({
  storageKey,
  label,
  primary,
  secondary,
  children,
  id,
  defaultRatio = 60,
  className = '',
}: ResizableSplitProps) {
  const root = useRef<HTMLDivElement>(null)
  const key = `split:${storageKey}`
  const initialRatio = clamp(defaultRatio, 25, 75)
  const [ratio, setRatio] = useState(() => {
    const saved = readLayout(key)
    return typeof saved === 'number' && Number.isFinite(saved) ? clamp(saved, 25, 75) : initialRatio
  })
  function update(value: number) {
    const next = clamp(value, 25, 75)
    setRatio(next)
    saveLayout(key, next)
  }
  return (
    <div
      ref={root}
      id={id}
      className={`resizable-split ${className}`}
      style={{ '--split-primary': `${ratio}fr`, '--split-secondary': `${100 - ratio}fr` } as CSSProperties}
    >
      <div className="resizable-split-panel">{children ? children[0] : primary}</div>
      <ResizeHandle
        className="split-resize-handle"
        label={label}
        value={ratio}
        min={25}
        max={75}
        onStart={() => {
          const initial = ratio
          const width = Math.max(1, (root.current?.getBoundingClientRect().width ?? 1) - 16)
          return (delta) => update(initial + (delta / width) * 100)
        }}
        onStep={(direction) => update(ratio + direction * 2)}
        onReset={() => {
          setRatio(initialRatio)
          saveLayout(key, null)
        }}
      />
      <div className="resizable-split-panel">{children ? children[1] : secondary}</div>
    </div>
  )
}
