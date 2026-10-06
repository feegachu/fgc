import { useEffect, useId, useRef, useState } from 'react'
import { Button } from './Button'
import { today } from '../lib/format'
export function MonthSelector({
  value,
  onApply,
  disabledMonths = [],
  openTabCount = 1,
}: {
  value: string
  onApply: (month: string) => void | Promise<void>
  disabledMonths?: string[]
  openTabCount?: number
}) {
  const id = useId()
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const cells = useRef<(HTMLButtonElement | null)[]>([])
  const [open, setOpen] = useState(false)
  const [year, setYear] = useState(Number(value.slice(0, 4)))
  const [draft, setDraft] = useState<string | null>(value)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [focused, setFocused] = useState(Number(value.slice(5)) - 1)
  const enabledIndex =
    Array.from({ length: 12 }, (_, index) => index).find(
      (index) => !disabledMonths.includes(`${String(year).padStart(4, '0')}-${String(index + 1).padStart(2, '0')}`),
    ) ?? -1
  const focusMonth = `${String(year).padStart(4, '0')}-${String(focused + 1).padStart(2, '0')}`
  const tabStop = disabledMonths.includes(focusMonth) ? enabledIndex : focused
  useEffect(() => {
    if (open) (cells.current[tabStop] ?? trigger.current)?.focus()
  }, [open, year, tabStop])
  function close() {
    if (!busy) {
      setOpen(false)
      trigger.current?.focus()
    }
  }
  useEffect(() => {
    if (!open || busy) return
    const dismiss = (event: Event) => {
      if (event instanceof KeyboardEvent && event.key !== 'Escape') return
      if (event.type === 'pointerdown' && root.current?.contains(event.target as Node)) return
      setOpen(false)
      trigger.current?.focus()
    }
    document.addEventListener('pointerdown', dismiss)
    document.addEventListener('keydown', dismiss)
    return () => {
      document.removeEventListener('pointerdown', dismiss)
      document.removeEventListener('keydown', dismiss)
    }
  }, [open, busy])
  async function apply() {
    if (!draft || disabledMonths.includes(draft) || busy) return
    setBusy(true)
    setError('')
    try {
      await onApply(draft)
      setOpen(false)
      trigger.current?.focus()
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '기준 정산월을 적용하지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }
  function move(start: number, step: number) {
    let target = start + step
    while (target >= 0 && target < 12 && cells.current[target]?.disabled) target += step < 0 ? -1 : 1
    if (target >= 0 && target < 12) {
      setFocused(target)
      cells.current[target]?.focus()
    }
  }
  return (
    <div ref={root} className={`month-selector ${open ? 'is-open' : ''}`}>
      <button
        ref={trigger}
        type="button"
        className="month-selector-trigger tabular-nums"
        aria-label={`기준 정산월 ${value}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => {
          if (open) close()
          else {
            setYear(Number(value.slice(0, 4)))
            setDraft(value)
            setError('')
            const index = Number(value.slice(5)) - 1
            setFocused(index)
            setOpen(true)
          }
        }}
      >
        {value}
        <span className="material-symbols-rounded month-selector-chevron" aria-hidden="true">
          expand_more
        </span>
      </button>
      {open && (
        <div
          id={id}
          className="month-selector-popover"
          role="dialog"
          aria-label="기준 정산월 선택"
          aria-describedby={`${id}-scope`}
        >
          <div className="month-selector-year-navigation">
            <button
              type="button"
              className="icon-button"
              aria-label="이전 연도"
              disabled={year <= 1 || busy}
              onClick={() => {
                setYear(year - 1)
                setDraft(null)
                setFocused(0)
              }}
            >
              <span className="material-symbols-rounded" aria-hidden="true">
                chevron_left
              </span>
            </button>
            <strong className="month-selector-year tabular-nums">{year}년</strong>
            <button
              type="button"
              className="icon-button"
              aria-label="다음 연도"
              disabled={year >= 9999 || busy}
              onClick={() => {
                setYear(year + 1)
                setDraft(null)
                setFocused(0)
              }}
            >
              <span className="material-symbols-rounded" aria-hidden="true">
                chevron_right
              </span>
            </button>
          </div>
          <div className="month-selector-grid" role="grid" aria-label="정산월 선택">
            {[0, 1, 2].map((row) => (
              <div role="row" className="month-selector-row" key={row}>
                {[0, 1, 2, 3].map((column) => {
                  const index = row * 4 + column
                  const month = `${String(year).padStart(4, '0')}-${String(index + 1).padStart(2, '0')}`
                  const disabled = disabledMonths.includes(month)
                  return (
                    <button
                      key={month}
                      ref={(element) => {
                        cells.current[index] = element
                      }}
                      className={`month-selector-cell ${draft === month ? 'is-selected' : ''} ${today().slice(0, 7) === month ? 'is-current' : ''}`}
                      type="button"
                      role="gridcell"
                      aria-label={`${index + 1}월`}
                      aria-selected={draft === month}
                      disabled={disabled || busy}
                      tabIndex={tabStop === index && !disabled ? 0 : -1}
                      onFocus={() => setFocused(index)}
                      onClick={() => setDraft(month)}
                      onKeyDown={(event) => {
                        const offsets: Record<string, number> = {
                          ArrowLeft: -1,
                          ArrowRight: 1,
                          ArrowUp: -4,
                          ArrowDown: 4,
                        }
                        if (event.key in offsets) {
                          event.preventDefault()
                          move(index, offsets[event.key])
                        }
                      }}
                    >
                      {index + 1}월
                    </button>
                  )
                })}
              </div>
            ))}
          </div>
          <div id={`${id}-scope`} className="month-selector-scope">
            <span className="material-symbols-rounded" aria-hidden="true">
              info
            </span>
            <p>
              적용하면 열린 업무 탭 <strong>{openTabCount}</strong>개의 조회 기준이
              <br />
              <strong>{draft ? `${Number(draft.slice(0, 4))}년 ${Number(draft.slice(5))}월` : '선택한 월'}</strong>로
              변경됩니다.
            </p>
          </div>
          <div className="month-selector-footer">
            <p className="month-selector-selection">선택 · {draft ?? '-'}</p>
            <div className="month-selector-actions">
              <Button variant="secondary" disabled={busy} onClick={close}>
                취소
              </Button>
              <Button disabled={!draft || disabledMonths.includes(draft)} loading={busy} onClick={() => void apply()}>
                적용
              </Button>
            </div>
          </div>
          {error && (
            <p role="alert" className="field-error">
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
