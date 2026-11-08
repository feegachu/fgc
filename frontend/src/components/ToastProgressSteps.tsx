import type { CSSProperties } from 'react'
import type { ToastProgress } from '../stores/toasts'
import './toast-progress.css'

export function ToastProgressSteps({ progress }: { progress: ToastProgress }) {
  const { steps, completed, running } = progress
  const start = Math.max(0, Math.min(completed - 1, steps.length - 3))
  return <div className="toast-step-viewport" aria-hidden="true">
    <div className="toast-step-track" style={{ transform: `translateX(-${start * 100 / 3}%)` }}>
      {steps.map((label, index) => {
        const done = index < completed
        const active = index === running
        return <div className={`toast-step${done ? ' is-done' : ''}${active ? ' is-running' : ''}${active && progress.buffering ? ' is-buffering' : ''}`}
          key={label} style={{ '--step-fill': index < completed - 1 || (done && running === index + 1) ? 1 : 0 } as CSSProperties}>
          {index < steps.length - 1 && <span className="toast-step-line"><span /></span>}
          <span className="toast-step-circle">
            <svg className="toast-step-check" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
            <span className="toast-step-number">{index + 1}</span>
          </span>
          <span className="toast-step-label">{label}</span>
        </div>
      })}
    </div>
  </div>
}
