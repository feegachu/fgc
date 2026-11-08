import { useEffect, useMemo, useRef, useState } from 'react'

/** Reveal only the completed, validated reply; guide data never comes from partial text. */
export function PicoReplyText({ text, animate, onComplete }: { text: string; animate: boolean; onComplete: () => void }) {
  const letters = useMemo(() => Array.from(text), [text])
  const [length, setLength] = useState(0)
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  const completed = useRef(onComplete)
  useEffect(() => { completed.current = onComplete }, [onComplete])
  useEffect(() => {
    if (!animate || reducedMotion) {
      if (animate) completed.current()
      return
    }
    const started = performance.now()
    const duration = Math.min(5000, Math.max(240, letters.length * 8))
    const timer = window.setInterval(() => {
      const count = Math.min(letters.length, Math.max(1, Math.floor((performance.now() - started) / duration * letters.length)))
      setLength(count)
      if (count === letters.length) { clearInterval(timer); completed.current() }
    }, 24)
    return () => clearInterval(timer)
  }, [letters, animate, reducedMotion])
  const visibleLength = animate && !reducedMotion ? length : letters.length
  const streaming = visibleLength < letters.length
  return <p className="pico-answer-text" data-streaming={streaming} aria-busy={streaming}>
    {letters.slice(0, visibleLength).join('')}{streaming && <span className="pico-text-cursor" aria-hidden="true" />}
  </p>
}
