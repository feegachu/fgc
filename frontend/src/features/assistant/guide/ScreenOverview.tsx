import { useEffect, useRef } from 'react'
import type { ScreenSnapshot } from './screen-snapshot'

export function ScreenOverview({ snapshot, title }: { snapshot: ScreenSnapshot; title: string }) {
  const host = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const container = host.current!
    const shadow = container.shadowRoot ?? container.attachShadow({ mode: 'open' })
    const styles = [...document.querySelectorAll('style, link[rel="stylesheet"]')].map(node => node.cloneNode(true))
    const frame = document.createElement('div')
    frame.className = 'snapshot-frame'
    frame.style.cssText = 'position:relative;transform-origin:top left'
    frame.style.width = `${snapshot.width}px`
    frame.style.height = `${snapshot.height}px`
    frame.inert = true
    frame.append(snapshot.content)
    const markers = snapshot.areas.map((area, index) => {
      const marker = document.createElement('span')
      marker.className = 'snapshot-number'
      marker.style.cssText = 'position:absolute;display:grid;place-items:center;border:2px solid white;border-radius:50%;background:var(--color-fgc-blue);color:white;font-weight:700;line-height:1;z-index:10000;box-sizing:border-box'
      marker.textContent = String(index + 1)
      marker.style.left = `${area.x}px`
      marker.style.top = `${area.y}px`
      frame.append(marker)
      return marker
    })
    shadow.replaceChildren(...styles, frame)
    const resize = () => {
      const scale = Math.min(1, container.clientWidth / snapshot.width)
      if (scale <= 0) return
      frame.style.transform = `scale(${scale})`
      container.style.height = `${snapshot.height * scale}px`
      markers.forEach((marker, index) => {
        marker.style.width = marker.style.height = `${24 / scale}px`
        marker.style.fontSize = `${12 / scale}px`
        marker.style.transform = 'translate(-25%, -25%)'
        // Keep full-size number badges inside the preview even at mobile scale.
        marker.style.left = `${Math.max(6 / scale, Math.min(snapshot.areas[index].x, snapshot.width - 18 / scale))}px`
        marker.style.top = `${Math.max(6 / scale, Math.min(snapshot.areas[index].y, snapshot.height - 18 / scale))}px`
      })
      snapshot.scroll.forEach(({ element, top, left }) => { element.scrollTop = top; element.scrollLeft = left })
    }
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(resize) : null
    observer?.observe(container)
    window.addEventListener('resize', resize)
    shadow.querySelectorAll('link').forEach(link => link.addEventListener('load', resize))
    resize()
    return () => { observer?.disconnect(); window.removeEventListener('resize', resize); shadow.replaceChildren() }
  }, [snapshot])
  return (
    <div className="screen-help-overview" role="region" aria-label="화면 예시와 설명" tabIndex={0}>
      <figure>
        <div className="screen-help-snapshot-scroll" role="img" aria-label={`${title} 화면 미리보기`} tabIndex={0}>
          <div ref={host} aria-hidden="true" className="screen-help-snapshot" />
        </div>
        <figcaption>도움말을 열 때 표시된 화면입니다. 번호는 현재 보이는 영역을 가리킵니다.</figcaption>
      </figure>
      <ol className="screen-help-legend" aria-label="번호별 화면 설명">
        {snapshot.areas.map((area, index) => (
          <li key={area.selector}>
            <span aria-hidden="true" className="screen-help-number">{index + 1}</span>
            <div><h3>{area.title}</h3><p>{area.description}</p></div>
          </li>
        ))}
        {!snapshot.areas.length && <li>표시된 내용을 확인한 뒤 필요한 탭이나 상세를 열고 도움말을 다시 볼 수 있습니다.</li>}
      </ol>
    </div>
  )
}
