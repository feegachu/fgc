import { Fragment, useState } from 'react'
import type { ReactNode } from 'react'
export interface TableColumn<T> {
  key: string
  label: ReactNode
  render: (row: T) => ReactNode
  width?: string
  cellClassName?: string
  sortable?: boolean
  expandable?: boolean
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
  rowClassName?: (row: T) => string
  emptyMessage?: string
}
function ExpandableCell({ children }: { children: ReactNode }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <>
      <div className={expanded ? 'cell-expanded' : 'cell-preview'}>{children}</div>
      <button
        type="button"
        className="button button-ghost"
        aria-expanded={expanded}
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? '접기' : '전체 보기'}
      </button>
    </>
  )
}
export function DataTable<T>({
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
  emptyMessage = '조건에 맞는 자료가 없습니다.',
}: DataTableProps<T>) {
  const keys = rows.map(rowKey)
  const allSelected = keys.length > 0 && keys.every((key) => selectedKeys.includes(key))
  function select(key: string) {
    onSelectionChange?.(
      selectedKeys.includes(key) ? selectedKeys.filter((item) => item !== key) : [...selectedKeys, key],
    )
  }
  return (
    <div className={`data-table-viewport ${viewportClassName}`}>
      <table className={`data-table ${className}`}>
        <caption className="visually-hidden">{caption}</caption>
        {columns.some((column) => column.width) && (
          <colgroup>
            {onSelectionChange && <col />}{' '}
            {columns.map((column) => (
              <col key={column.key} style={{ width: column.width }} />
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
        <tbody>
          {rows.length ? (
            rows.map((row) => (
              <tr
                key={rowKey(row)}
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
                      ) : column.expandable ? (
                        <ExpandableCell>{column.render(row)}</ExpandableCell>
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
      </table>
    </div>
  )
}
