import { Fragment, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { ResizeHandle } from './ResizeHandle'
import { clamp, MAX_COLUMN_WIDTH, MIN_COLUMN_WIDTH, readLayout, saveLayout } from './resizeLayout'
import './resize.css'
export interface TableColumn<T> {
  key: string
  label: ReactNode
  render: (row: T) => ReactNode
  width?: string
  cellClassName?: string
  sortable?: boolean
  align?: 'left' | 'center' | 'number'
}
export interface DataTableProps<T> {
  className?: string
  viewportClassName?: string
  caption: string
  columns: TableColumn<T>[]
  rows: T[]
  rowKey: (row: T) => string
  sort?: { key: string; direction: 'asc' | 'desc' }
  onSort?: (sort: { key: string; direction: 'asc' | 'desc' }) => void
  selectedKeys?: string[]
  onSelectionChange?: (keys: string[]) => void
  onRowClick?: (row: T) => void
  onRowActivate?: (row: T) => void
  rowLabel?: (row: T) => string
  rowClassName?: (row: T) => string
  emptyMessage?: ReactNode
  footer?: ReactNode
  bodyId?: string
  resizeKey?: string
  resizable?: boolean
}
export function DataTable<T>(props: DataTableProps<T>) {
  const layoutKey = `table:${props.resizeKey ?? `${window.location.pathname}:${props.caption}`}:${props.onSelectionChange ? 'selection:' : ''}${props.columns.map((column) => column.key).join('|')}`
  return <TableContents key={layoutKey} {...props} layoutKey={layoutKey} />
}

function TableContents<T>({
  caption,
  className = '',
  viewportClassName = '',
  columns,
  rows,
  rowKey,
  sort,
  onSort,
  selectedKeys = [],
  onSelectionChange,
  onRowClick,
  onRowActivate,
  rowClassName,
  rowLabel,
  emptyMessage = '조건에 맞는 자료가 없습니다.',
  footer,
  bodyId,
  resizable = true,
  layoutKey,
}: DataTableProps<T> & { layoutKey: string }) {
  const tableRef = useRef<HTMLTableElement>(null)
  const columnCount = columns.length + (onSelectionChange ? 1 : 0)
  const [widths, setWidths] = useState<number[] | null>(() => {
    const saved = readLayout(layoutKey)
    return Array.isArray(saved) &&
      saved.length === columnCount &&
      saved.every(
        (width) =>
          typeof width === 'number' && Number.isFinite(width) && width >= MIN_COLUMN_WIDTH && width <= MAX_COLUMN_WIDTH,
      )
      ? saved
      : null
  })
  const [minimumWidths, setMinimumWidths] = useState<number[]>([])
  const activeWidths = resizable
    ? (widths?.map((width, index) => Math.max(width, minimumWidths[index] ?? MIN_COLUMN_WIDTH)) ?? null)
    : null
  const [handles, setHandles] = useState<{ left: number; top: number; width: number; height: number }[]>([])

  useEffect(() => {
    const table = tableRef.current
    if (!table || !resizable || typeof ResizeObserver === 'undefined') return
    const syncHandles = () => {
      const tableBox = table.getBoundingClientRect()
      const measured = Array.from(table.tHead?.rows[0]?.cells ?? []).map((cell) => {
        const box = cell.getBoundingClientRect()
        return {
          left: box.left - tableBox.left + table.offsetLeft + box.width - 10,
          top: box.top - tableBox.top + table.offsetTop,
          width: box.width,
          height: box.height,
        }
      })
      setHandles((previous) => (JSON.stringify(measured) === JSON.stringify(previous) ? previous : measured))
      const minimums = Array.from(table.tHead?.rows[0]?.cells ?? []).map((header, index) => {
        let badgeWidth = 0
        for (const row of Array.from(table.tBodies[0]?.rows ?? [])) {
          const cell = row.cells[index]
          if (!cell || cell.colSpan !== 1) continue
          const padding = getComputedStyle(cell)
          for (const badge of cell.querySelectorAll<HTMLElement>('.status-badge')) {
            badgeWidth = Math.max(
              badgeWidth,
              badge.getBoundingClientRect().width + parseFloat(padding.paddingLeft) + parseFloat(padding.paddingRight),
            )
          }
        }
        if (!badgeWidth) return MIN_COLUMN_WIDTH
        // Keep both the heading and the longest tag on the current page readable.
        const range = document.createRange()
        range.selectNodeContents(header)
        const padding = getComputedStyle(header)
        const headingWidth =
          range.getBoundingClientRect().width + parseFloat(padding.paddingLeft) + parseFloat(padding.paddingRight)
        return Math.ceil(Math.max(MIN_COLUMN_WIDTH, badgeWidth, headingWidth)) + 1
      })
      setMinimumWidths((previous) => (JSON.stringify(previous) === JSON.stringify(minimums) ? previous : minimums))
    }
    const observer = new ResizeObserver(syncHandles)
    observer.observe(table)
    for (const cell of Array.from(table.tHead?.rows[0]?.cells ?? [])) observer.observe(cell)
    for (const badge of table.querySelectorAll('.status-badge')) observer.observe(badge)
    // Sticky headers stay resizable while their viewport scrolls vertically.
    const viewport = table.parentElement
    viewport?.addEventListener('scroll', syncHandles, { passive: true })
    return () => {
      observer.disconnect()
      viewport?.removeEventListener('scroll', syncHandles)
    }
  }, [resizable, rows, columns])

  function measureWidths(index: number, shrinking = false) {
    // The last column absorbs spare space. Do not persist that extra space when
    // another column is resized, or every drag would unnecessarily widen the table.
    return Array.from(tableRef.current?.tHead?.rows[0]?.cells ?? []).map((cell, i) =>
      activeWidths && (i !== index || (shrinking && i === columnCount - 1))
        ? activeWidths[i]
        : clamp(
            cell.getBoundingClientRect().width,
            minimumWidths[i] ?? MIN_COLUMN_WIDTH,
            Math.max(MAX_COLUMN_WIDTH, minimumWidths[i] ?? 0),
          ),
    )
  }
  function resizeColumn(initial: number[], index: number, delta: number) {
    const next = initial.map((width, i) =>
      i === index
        ? clamp(width + delta, minimumWidths[i] ?? MIN_COLUMN_WIDTH, Math.max(MAX_COLUMN_WIDTH, minimumWidths[i] ?? 0))
        : width,
    )
    setWidths(next)
    saveLayout(layoutKey, next)
  }
  function resetWidths() {
    setWidths(null)
    saveLayout(layoutKey, null)
  }
  const keys = rows.map(rowKey)
  const allSelected = keys.length > 0 && keys.every((key) => selectedKeys.includes(key))
  function select(key: string) {
    onSelectionChange?.(
      selectedKeys.includes(key) ? selectedKeys.filter((item) => item !== key) : [...selectedKeys, key],
    )
  }
  return (
    <div className={`data-table-viewport ${resizable ? 'resizable-table-viewport' : ''} ${viewportClassName}`}>
      <table
        ref={tableRef}
        className={`data-table ${resizable ? 'data-table-resizable' : ''} ${className}`}
        style={
          activeWidths
            ? {
                width: `max(100%, ${activeWidths.reduce((sum, width) => sum + width, 0)}px)`,
                minWidth: 0,
                tableLayout: 'fixed',
              }
            : undefined
        }
      >
        <caption className="visually-hidden">{caption}</caption>
        {(activeWidths ||
          columns.some((column) => column.width) ||
          minimumWidths.some((width) => width > MIN_COLUMN_WIDTH)) && (
          <colgroup>
            {onSelectionChange && <col style={{ width: activeWidths?.[0] }} />}
            {columns.map((column, index) => (
              <col
                key={column.key}
                style={{
                  width: activeWidths
                    ? index === columns.length - 1
                      ? undefined
                      : activeWidths[index + (onSelectionChange ? 1 : 0)]
                    : resizable && minimumWidths[index + (onSelectionChange ? 1 : 0)] > MIN_COLUMN_WIDTH
                      ? `max(${column.width ?? '0px'}, ${minimumWidths[index + (onSelectionChange ? 1 : 0)]}px)`
                      : column.width,
                }}
              />
            ))}
          </colgroup>
        )}
        <thead>
          <tr>
            {onSelectionChange && (
              <th scope="col">
                <input
                  type="checkbox"
                  aria-label="현재 페이지 전체 선택"
                  checked={allSelected}
                  onChange={() =>
                    onSelectionChange(
                      allSelected
                        ? selectedKeys.filter((key) => !keys.includes(key))
                        : [...new Set([...selectedKeys, ...keys])],
                    )
                  }
                />
              </th>
            )}
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={`is-${column.align ?? 'left'}`}
                aria-sort={
                  sort?.key === column.key
                    ? sort.direction === 'asc'
                      ? 'ascending'
                      : 'descending'
                    : column.sortable
                      ? 'none'
                      : undefined
                }
              >
                {column.sortable && onSort ? (
                  <button
                    type="button"
                    onClick={() =>
                      onSort({
                        key: column.key,
                        direction: sort?.key === column.key && sort.direction === 'asc' ? 'desc' : 'asc',
                      })
                    }
                  >
                    {column.label}
                    <span aria-hidden="true">
                      {' '}
                      {sort?.key === column.key ? (sort.direction === 'asc' ? '↑' : '↓') : '↕'}
                    </span>
                  </button>
                ) : (
                  column.label
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody id={bodyId}>
          {rows.length ? (
            rows.map((row) => (
              <tr
                key={rowKey(row)}
                tabIndex={onRowActivate ? 0 : undefined}
                aria-label={rowLabel?.(row)}
                aria-selected={onRowActivate ? selectedKeys.includes(rowKey(row)) : undefined}
                onKeyDown={
                  onRowActivate
                    ? (event) => {
                        if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) {
                          event.preventDefault()
                          onRowActivate(row)
                        }
                      }
                    : undefined
                }
                className={[selectedKeys.includes(rowKey(row)) ? 'is-selected' : '', rowClassName?.(row)]
                  .filter(Boolean)
                  .join(' ')}
                onClick={
                  onRowActivate
                    ? (event) => {
                        if (!(event.target as Element).closest('button, a, input, summary')) onRowActivate(row)
                      }
                    : undefined
                }
              >
                {onSelectionChange && (
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`${rowKey(row)} 선택`}
                      checked={selectedKeys.includes(rowKey(row))}
                      onChange={() => select(rowKey(row))}
                    />
                  </td>
                )}
                {columns.map((column, index) => (
                  <td key={column.key} className={`is-${column.align ?? 'left'} ${column.cellClassName ?? ''}`}>
                    <Fragment>
                      {index === 0 && onRowClick ? (
                        <button type="button" className="table-row-link" onClick={() => onRowClick(row)}>
                          {column.render(row)}
                        </button>
                      ) : (
                        column.render(row)
                      )}
                    </Fragment>
                  </td>
                ))}
              </tr>
            ))
          ) : (
            <tr>
              <td colSpan={columns.length + (onSelectionChange ? 1 : 0)} className="table-empty">
                {emptyMessage}
              </td>
            </tr>
          )}
        </tbody>
        {footer && <tfoot>{footer}</tfoot>}
      </table>
      {resizable &&
        columns.map((column, index) => {
          const cellIndex = index + (onSelectionChange ? 1 : 0)
          const handle = handles[cellIndex]
          return (
            handle && (
              <ResizeHandle
                key={column.key}
                className="column-resize-handle"
                style={{ left: handle.left, top: handle.top, height: handle.height }}
                label={`${typeof column.label === 'string' ? column.label : column.key} 열 너비`}
                value={handle.width}
                min={minimumWidths[cellIndex] ?? MIN_COLUMN_WIDTH}
                max={Math.max(MAX_COLUMN_WIDTH, handle.width)}
                onStart={() => {
                  const initial = measureWidths(cellIndex)
                  const smaller = measureWidths(cellIndex, true)
                  return (delta) => resizeColumn(delta < 0 ? smaller : initial, cellIndex, delta)
                }}
                onStep={(direction) => resizeColumn(measureWidths(cellIndex, direction < 0), cellIndex, direction * 16)}
                onReset={resetWidths}
              />
            )
          )
        })}
    </div>
  )
}
