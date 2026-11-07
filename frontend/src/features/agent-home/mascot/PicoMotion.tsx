import { Component, Suspense, lazy, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import type { CSSProperties } from 'react'
import { canAnimate } from '../../../lib/motion'
import './pico-motion.css'

/**
 * 설계사 화면의 보라 피코 모션. pico-motion-purple.riv 의 상태별 아트보드를 그린다
 * (원본: 캐릭터 디자인/fgc-bear/character-motion-purple — 파랑 파츠를 보라 그림 색으로 바꾼 것).
 * - still   가만히                       (메인 화면 런처)
 * - wave    웃으며 인사 → 눈 뜨고 갸웃   (챗봇 1단계)
 * - hat     탐정 모자·화난 눈썹, 정지     (챗봇 2단계)
 * - glasses 안경, 10초마다 지시봉 휘두르기 (챗봇 3단계)
 * - worried 당황, 두리번두리번            (답변을 찾지 못했을 때)
 */
export type PicoMotionName = 'still' | 'wave' | 'hat' | 'glasses' | 'worried'

const ARTBOARDS: Record<PicoMotionName, string> = {
  still: 'Bear Still',
  wave: 'Bear Wave',
  hat: 'Bear Hat',
  glasses: 'Bear Glasses',
  worried: 'Bear Worried',
}

const brand = (name: string) => `${import.meta.env.BASE_URL}images/brand/${name}.png`
/** 모션을 쓰지 않거나(동작 줄이기·테스트) Rive 를 불러오기 전·실패했을 때 보여 줄 보라 정지 그림. */
const STILLS: Record<PicoMotionName, string> = {
  still: brand('agent-mascot'),
  wave: brand('chatbot-base'),
  hat: brand('chatbot-search'),
  glasses: brand('chatbot-sum-base'),
  worried: brand('chatbot-sorry'),
}

// Rive 런타임(WASM 포함)은 모션을 실제로 그릴 때만 내려받는다.
const PicoRive = lazy(() => import('./PicoRive'))

/** 보안 헤더(CSP)가 WebAssembly 를 막는 서버에서는 어차피 실패할 내려받기를 하지 않고 정지 그림을 쓴다. */
function canCompileWasm() {
  try {
    return Boolean(new WebAssembly.Module(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])))
  } catch {
    return false
  }
}

/** lazy 청크(Rive·WASM) 로딩이 실패하면 렌더 에러가 되므로, 이 칸 안에서 받아 정지 그림으로 돌린다. */
class RiveBoundary extends Component<{ fallback: ReactNode; onError: () => void; children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  componentDidCatch() {
    this.props.onError()
  }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children
  }
}

/** 동작 줄이기 설정을 마운트 시점뿐 아니라 바뀔 때도 따라간다. */
function useCanAnimate(): boolean {
  const [animate, setAnimate] = useState(canAnimate)
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const query = window.matchMedia('(prefers-reduced-motion: reduce)')
    const sync = () => setAnimate(canAnimate())
    query.addEventListener?.('change', sync)
    return () => query.removeEventListener?.('change', sync)
  }, [])
  return animate
}

export function PicoMotion({ motion, size, className }: { motion: PicoMotionName; size: number; className?: string }) {
  const animate = useCanAnimate()
  const [failed, setFailed] = useState(() => !canCompileWasm())
  const still = <img src={STILLS[motion]} alt="" width={size} height={size} draggable={false} />
  return (
    <span
      className={`pico-motion${className ? ` ${className}` : ''}`}
      style={{ '--pico-size': `${size}px` } as CSSProperties}
      data-pico-motion={motion}
      aria-hidden="true"
    >
      {animate && !failed ? (
        <RiveBoundary fallback={still} onError={() => setFailed(true)}>
          <Suspense fallback={still}>
            <PicoRive key={motion} artboard={ARTBOARDS[motion]} fallback={still} onError={() => setFailed(true)} />
          </Suspense>
        </RiveBoundary>
      ) : (
        still
      )}
    </span>
  )
}
