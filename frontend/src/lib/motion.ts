import { useEffect, useRef, useState } from 'react'

/**
 * 화면 모션 가능 여부. 사용자가 "동작 줄이기"를 켰거나 matchMedia 가 없는 환경(테스트 등)에서는 모션을 쓰지 않는다.
 * 모션을 끄면 값은 처음부터 최종값으로 그려지므로 화면 정보에는 영향이 없다.
 */
export function canAnimate(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

const easeOutCubic = (t: number) => 1 - Math.pow(1 - t, 3)

/** 숫자를 이전 값에서 목표값까지 부드럽게 올린다. 모션을 쓰지 않으면 목표값을 그대로 돌려준다. */
export function useCountUp(target: number, durationMs = 700): number {
  const [animate] = useState(canAnimate)
  const [value, setValue] = useState(animate ? 0 : target)
  const currentRef = useRef(animate ? 0 : target)

  useEffect(() => {
    if (!animate) return
    const from = currentRef.current
    if (from === target) return
    const start = performance.now()
    let frame = 0
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / durationMs)
      const next = t >= 1 ? target : Math.round(from + (target - from) * easeOutCubic(t))
      currentRef.current = next
      setValue(next)
      if (t < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [animate, target, durationMs])

  return animate ? value : target
}

/** 마운트 직후 한 프레임 뒤에 true 가 된다. CSS transition 으로 "그려지는" 효과를 낼 때 쓴다. 모션을 쓰지 않으면 처음부터 true. */
export function useDrawn(): boolean {
  const [animate] = useState(canAnimate)
  const [drawn, setDrawn] = useState(!animate)
  useEffect(() => {
    if (!animate) return
    const frame = requestAnimationFrame(() => setDrawn(true))
    return () => cancelAnimationFrame(frame)
  }, [animate])
  return drawn
}

/**
 * 숫자 배열을 이전 값에서 목표값까지 부드럽게 옮긴다(필터를 바꿀 때 차트가 모양을 바꾸는 효과).
 * 길이가 달라지면 새로 생기는 칸은 0에서 시작한다. fromZero 이면 처음 그릴 때도 0에서 올라온다.
 * 모션을 쓰지 않으면 목표값을 그대로 돌려준다.
 */
export function useTween(target: number[], durationMs = 500, fromZero = false): number[] {
  const [animate] = useState(canAnimate)
  const key = target.join(',')
  const [value, setValue] = useState<number[]>(animate && fromZero ? target.map(() => 0) : target)
  const currentRef = useRef<number[]>(value)

  useEffect(() => {
    if (!animate) return
    const to = key === '' ? [] : key.split(',').map(Number)
    const from = to.map((_, i) => currentRef.current[i] ?? 0)
    if (from.every((v, i) => v === to[i]) && from.length === currentRef.current.length) return
    const start = performance.now()
    let frame = 0
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / durationMs)
      const next = t >= 1 ? to : to.map((v, i) => from[i] + (v - from[i]) * easeOutCubic(t))
      currentRef.current = next
      setValue(next)
      if (t < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [animate, key, durationMs])

  return animate ? value : target
}
