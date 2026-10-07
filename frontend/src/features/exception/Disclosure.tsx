import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'

/**
 * 표 셀의 "전체 보기" — 기존 화면(table-cell-disclosure)처럼 실제로 잘릴 때만 토글을 드러낸다.
 * preview 는 칸 안에 보이는 내용, full 은 펼쳤을 때 보이는 내용이다.
 */
export function Disclosure({
  preview,
  full = preview,
  singleLine = false,
}: {
  preview: ReactNode
  full?: ReactNode
  singleLine?: boolean
}) {
  const container = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const node = container.current
    if (!node) return
    const previewNode = node.querySelector<HTMLElement>('.table-cell-preview')!
    const details = node.querySelector<HTMLDetailsElement>('details')!
    const sync = () => {
      if (!previewNode.clientWidth) return
      details.hidden =
        previewNode.scrollWidth <= previewNode.clientWidth + 1 &&
        previewNode.scrollHeight <= previewNode.clientHeight + 1
      if (details.hidden) details.open = false
    }
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(sync)
    observer?.observe(previewNode)
    sync()
    void document.fonts?.ready.then(sync)
    return () => observer?.disconnect()
  }, [])
  return (
    <div className="table-cell-disclosure" ref={container}>
      <span className={`table-cell-preview ${singleLine ? 'is-single-line' : ''}`}>{preview}</span>
      <details className="table-cell-details" hidden>
        <summary>
          <span className="table-cell-more">전체 보기</span>
          <span className="table-cell-less">접기</span>
          <span className="material-symbols-rounded table-cell-chevron" aria-hidden="true">
            expand_more
          </span>
        </summary>
        <p className="table-cell-full">{full}</p>
      </details>
    </div>
  )
}
