import { Alignment, Fit, Layout, RuntimeLoader, useRive } from '@rive-app/react-canvas'
import riveWasm from '@rive-app/canvas/rive.wasm?url'
import { useState } from 'react'
import type { ReactNode } from 'react'
import picoMotion from './pico-motion-purple.riv?url'

// 기본값은 unpkg CDN 에서 WASM 을 받는다. 외부 CDN 없이 동작하도록 번들에 포함한 파일을 쓴다.
RuntimeLoader.setWasmUrl(riveWasm)

const LAYOUT = new Layout({ fit: Fit.Contain, alignment: Alignment.Center })

/** PicoMotion 이 지연 로드하는 Rive 캔버스. 첫 프레임을 그리기 전까지는 정지 그림을 겹쳐 둔다. */
export default function PicoRive({
  artboard,
  fallback,
  onError,
}: {
  artboard: string
  fallback: ReactNode
  onError: () => void
}) {
  const [ready, setReady] = useState(false)
  const { RiveComponent } = useRive({
    src: picoMotion,
    artboard,
    stateMachine: 'Motion',
    autoplay: true,
    layout: LAYOUT,
    onLoad: () => setReady(true),
    onLoadError: onError,
  })
  return (
    <>
      {!ready && fallback}
      <RiveComponent className={`pico-motion-canvas${ready ? ' is-ready' : ''}`} />
    </>
  )
}
