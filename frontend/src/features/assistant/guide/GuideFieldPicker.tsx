import { useEffect, useState, type CSSProperties } from 'react'

const isoDate = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`

/** Guide-only picker: native popups cannot be animated or opened after a scroll without a user gesture. */
export function GuideFieldPicker({ target }: { target: HTMLElement | null }) {
  const field = target instanceof HTMLSelectElement || target instanceof HTMLInputElement && target.type === 'date' ? target : null
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState<CSSProperties>({})
  const [month, setMonth] = useState(() => {
    const date = field?.value && /^\d{4}-\d{2}-\d{2}$/.test(field.value) ? new Date(`${field.value}T12:00:00`) : new Date()
    return new Date(date.getFullYear(), date.getMonth(), 1)
  })
  useEffect(() => {
    if (!field) return
    const show = () => {
      if (field.matches(':disabled')) return
      if (field instanceof HTMLInputElement && /^\d{4}-\d{2}-\d{2}$/.test(field.value)) {
        const date = new Date(`${field.value}T12:00:00`)
        setMonth(new Date(date.getFullYear(), date.getMonth(), 1))
      }
      setOpen(true)
    }
    const hide = () => setOpen(false)
    const click = (event: Event) => { event.preventDefault(); show() }
    field.addEventListener('fgc-guide-open', show)
    field.addEventListener('fgc-guide-close', hide)
    field.addEventListener('mousedown', click)
    return () => {
      field.removeEventListener('fgc-guide-open', show)
      field.removeEventListener('fgc-guide-close', hide)
      field.removeEventListener('mousedown', click)
    }
  }, [field])
  useEffect(() => {
    if (!open || !field) return
    const place = () => {
      const rect = field.getBoundingClientRect()
      const width = Math.min(field instanceof HTMLSelectElement ? Math.max(280, rect.width) : 300, innerWidth - 24)
      const room = innerHeight - rect.bottom - 16
      const below = room >= 250 || rect.top < 300
      setPosition({ left: Math.max(12, Math.min(rect.left, innerWidth - width - 12)), width,
        ...(below ? { top: rect.bottom + 6 } : { bottom: innerHeight - rect.top + 6 }),
        maxHeight: Math.max(120, below ? room : rect.top - 16) })
    }
    place()
    window.addEventListener('scroll', place, true)
    window.addEventListener('resize', place)
    return () => { window.removeEventListener('scroll', place, true); window.removeEventListener('resize', place) }
  }, [open, field])
  if (!open || !field) return null
  const choose = (value: string) => {
    // Use the real control's React onChange; validation and derived values stay owned by the form.
    if (field.value !== value) {
      const prototype = field instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(field, value)
      field.dispatchEvent(new Event('input', { bubbles: true }))
      field.dispatchEvent(new Event('change', { bubbles: true }))
    }
    field.dispatchEvent(new Event('fgc-guide-selected'))
    setOpen(false)
    field.focus({ preventScroll: true })
  }
  return <div className="guide-field-picker" style={position} data-guide-picker onMouseDown={event => event.preventDefault()}>
    {field instanceof HTMLSelectElement ? <div role="listbox" aria-label={`${field.name === 'insurerId' ? '보험회사' : '계약상태'} 선택 목록`}>
      {[...field.options].map(option => <button type="button" role="option" key={option.value} aria-selected={option.value === field.value}
        disabled={option.disabled || !option.value} onClick={() => choose(option.value)}>{option.label}</button>)}
    </div> : <div role="group" aria-label="계약일 달력">
      <header>
        <button type="button" aria-label="이전 달" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))}>‹</button>
        <strong aria-live="polite">{month.getFullYear()}년 {month.getMonth() + 1}월</strong>
        <button type="button" aria-label="다음 달" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))}>›</button>
      </header>
      <div className="guide-calendar-grid">
        {['일', '월', '화', '수', '목', '금', '토'].map(day => <span key={day}>{day}</span>)}
        {Array.from({ length: month.getDay() }, (_, index) => <span key={`empty-${index}`} />)}
        {Array.from({ length: new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate() }, (_, index) => {
          const value = isoDate(new Date(month.getFullYear(), month.getMonth(), index + 1))
          return <button type="button" key={value} aria-label={value} aria-pressed={value === field.value}
            disabled={Boolean(field.min && value < field.min || field.max && value > field.max || field.readOnly)}
            onClick={() => choose(value)}>{index + 1}</button>
        })}
      </div>
    </div>}
  </div>
}
