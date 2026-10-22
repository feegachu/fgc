import type { ReactNode } from 'react'

/** 긴 셀 값은 컬럼 너비에 맞춰 표시하며 별도의 펼치기 버튼을 만들지 않는다. */
export function CellDisclosure({
  preview,
  singleLine = false,
}: {
  preview: ReactNode
  full?: ReactNode
  singleLine?: boolean
}) {
  return (
    <div className="table-cell-disclosure">
      <span className={`table-cell-preview ${singleLine ? 'is-single-line' : ''}`}>{preview}</span>
    </div>
  )
}
