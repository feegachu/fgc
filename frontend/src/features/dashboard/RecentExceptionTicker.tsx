import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '../../components/StatusBadge'
import { screenMessage } from '../../lib/screenText'
import { withMonth } from '../../stores/workspace'
import type { RecentException } from './api'
import { exceptionStatusTone, severityTone } from './tones'

/** 두 예외 행을 유지하며 한 행씩 위로 순환한다. 조작 중·백그라운드·동작 줄이기 설정에서는 자동 진행을 멈춘다. */
export function RecentExceptionTicker({ rows, month }: { rows: RecentException[]; month: string }) {
  const [index, setIndex] = useState(0)
  const [sliding, setSliding] = useState(false)
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const current = rows.length ? index % rows.length : 0
  // Keep one incoming row below the two visible rows so they move upward together.
  const visibleRows = Array.from(
    { length: Math.min(3, rows.length) },
    (_, offset) => rows[(current + offset) % rows.length],
  )

  useEffect(() => {
    if (hovered || focused || rows.length <= 2) return
    const timer = window.setInterval(() => {
      if (!document.hidden && !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
        setSliding(true)
      }
    }, 4500)
    return () => window.clearInterval(timer)
  }, [hovered, focused, rows.length])

  useEffect(() => {
    if (!sliding) return
    const timer = window.setTimeout(() => {
      setIndex((previous) => (previous + 1) % Math.max(1, rows.length))
      setSliding(false)
    }, 450)
    return () => window.clearTimeout(timer)
  }, [sliding, rows.length])

  return (
    <section
      className={`surface exception-ticker${sliding ? ' is-sliding' : ''}`}
      aria-labelledby="exception-ticker-title"
      data-guide-feature="features.dashboard.exception-ticker.section"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocusCapture={() => {
        setFocused(true)
        setSliding(false)
      }}
      onBlurCapture={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false)
      }}
    >
      <header className="surface-header">
        <h2 id="exception-ticker-title" className="surface-title">
          최근 예외
        </h2>
        <Link
          data-guide-feature="features.dashboard.exception-ticker.all"
          className="run-list-link"
          to={withMonth('/exceptions', month)}
        >
          전체 보기
        </Link>
      </header>
      <div className="data-table-viewport">
        <table data-guide-feature="features.dashboard.exception-ticker.table" className="data-table exception-ticker-table">
          <caption className="visually-hidden">최근 예외 목록</caption>
          <thead>
            <tr>
              <th scope="col">심각도</th>
              <th scope="col">예외 내용</th>
              <th scope="col">상태</th>
            </tr>
          </thead>
          <tbody style={{ '--ticker-visible-rows': Math.max(1, Math.min(2, rows.length)) } as CSSProperties}>
            {visibleRows.length ? (
              visibleRows.map((row, offset) => (
                <tr
                  key={row.exceptionCaseId}
                  className="exception-ticker-row"
                  aria-hidden={offset === 2 || undefined}
                  inert={offset === 2}
                >
                  <td>
                    <StatusBadge
                      kind="severity"
                      optionCount={4}
                      tone={severityTone(row.severity)}
                      tablePresentation="text"
                    >
                      {row.severityLabel ?? '-'}
                    </StatusBadge>
                  </td>
                  <td>
                    <div className="exception-ticker-detail">
                      <Link
                        data-guide-feature="features.dashboard.exception-ticker.detail"
                        title={screenMessage(row.title)}
                        to={withMonth(
                          `/exceptions?${new URLSearchParams({ contractNo: row.contractNo ?? '' })}`,
                          month,
                        )}
                      >
                        {screenMessage(row.title)}
                      </Link>
                    </div>
                  </td>
                  <td>
                    <StatusBadge optionCount={4} tone={exceptionStatusTone(row.status)} tablePresentation="dot">
                      {row.statusLabel ?? '-'}
                    </StatusBadge>
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={3} className="empty-state">
                  조건에 맞는 자료가 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
