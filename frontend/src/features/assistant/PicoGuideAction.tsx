import type { MouseEventHandler } from 'react'

export function PicoGuideAction({ title, label, disabled, busy, describedBy, onClick }: {
  title: string; label: string; disabled?: boolean; busy?: boolean; describedBy?: string; onClick: MouseEventHandler<HTMLButtonElement>
}) {
  return <div className="pico-guide-action">
    <span className="pico-guide-action-title"><span className="material-symbols-rounded" aria-hidden="true">menu_book</span>{title}</span>
    <button type="button" className="button button-secondary pico-guide-action-button" aria-label={label}
      aria-describedby={describedBy} disabled={disabled} onClick={onClick}>
      <span className="material-symbols-rounded" aria-hidden="true">{busy ? 'progress_activity' : 'arrow_forward'}</span>
      {busy ? '확인 중' : '이동하기'}
    </button>
  </div>
}
