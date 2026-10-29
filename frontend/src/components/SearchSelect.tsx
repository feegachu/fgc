import { useId, useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties, InputHTMLAttributes } from 'react'
import { createPortal } from 'react-dom'
import { useInfiniteQuery } from '@tanstack/react-query'
import './search-select.css'

export interface SearchPage<T> {
  content: T[]
  page: number
  totalPages: number
}
export interface SearchSource<T> {
  key: readonly unknown[]
  load: (keyword: string, page: number, signal: AbortSignal) => Promise<SearchPage<T>>
  id: (option: T) => string
  label: (option: T) => string
}
type Props<T> = Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'onSelect' | 'size'> & {
  value: string
  selected?: T
  source: SearchSource<T>
  onSelect: (id: string, option?: T) => void
}

/** CMN-C02: 선택값과 검색어를 분리하고 서버의 다음 페이지만 요청한다. */
export function SearchSelect<T>({ value, selected, source, onSelect, disabled, ...input }: Props<T>) {
  const generated = useId()
  const id = input.id ?? generated
  const popupId = `${id}-options`
  const anchor = useRef<HTMLInputElement>(null)
  const popup = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [active, setActive] = useState(-1)
  const [position, setPosition] = useState<CSSProperties>({})
  const result = useInfiniteQuery({
    queryKey: ['selection-search', ...source.key, keyword],
    initialPageParam: 1,
    queryFn: ({ pageParam, signal }) => source.load(keyword, pageParam, signal),
    getNextPageParam: (last) => (last.page < last.totalPages ? last.page + 1 : undefined),
    enabled: open && !disabled,
    retry: false,
    meta: { errorToast: false },
  })
  const options = [
    ...new Map(
      (result.data?.pages.flatMap((page) => page.content) ?? []).map((option) => [source.id(option), option]),
    ).values(),
  ]
  const expanded = open && !disabled
  useLayoutEffect(() => {
    if (!expanded) return
    const place = () => {
      const rect = anchor.current?.getBoundingClientRect()
      if (rect)
        setPosition({
          position: 'fixed',
          left: Math.max(8, Math.min(rect.left, window.innerWidth - Math.max(rect.width, 280) - 8)),
          width: Math.max(rect.width, 280),
          maxWidth: 'calc(100vw - 16px)',
          ...(window.innerHeight - rect.bottom > 240
            ? { top: rect.bottom + 4 }
            : { bottom: window.innerHeight - rect.top + 4 }),
        })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [expanded])
  const choose = (option?: T) => {
    onSelect(option ? source.id(option) : '', option)
    anchor.current?.focus()
    setOpen(false)
    setKeyword('')
    setActive(-1)
  }
  return (
    <div
      className="search-select"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget) && !popup.current?.contains(event.relatedTarget)) {
          setOpen(false)
          setKeyword('')
          setActive(-1)
        }
      }}
    >
      <input
        {...input}
        id={id}
        ref={anchor}
        disabled={disabled}
        type="text"
        role="combobox"
        autoComplete="off"
        aria-autocomplete="list"
        aria-expanded={expanded}
        aria-controls={expanded ? popupId : undefined}
        aria-activedescendant={expanded && options[active] ? `${popupId}-${active}` : undefined}
        value={expanded ? keyword : selected ? source.label(selected) : value ? `선택값 확인 필요 (#${value})` : ''}
        onFocus={() => {
          setOpen(true)
          setActive(-1)
        }}
        onClick={() => setOpen(true)}
        onChange={(event) => {
          setKeyword(event.target.value)
          setActive(-1)
          setOpen(true)
        }}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing || event.keyCode === 229) return
          if (event.key === 'Escape') {
            event.preventDefault()
            event.stopPropagation()
            setOpen(false)
            setKeyword('')
            return
          }
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            setOpen(true)
            const next =
              event.key === 'ArrowDown'
                ? Math.min(active + 1, options.length - 1)
                : active <= 0
                  ? options.length - 1
                  : active - 1
            setActive(next)
            document.getElementById(`${popupId}-${next}`)?.scrollIntoView?.({ block: 'nearest' })
          } else if (event.key === 'Enter' && expanded) {
            event.preventDefault()
            if (options[active]) choose(options[active])
          }
        }}
      />
      {expanded &&
        createPortal(
          <div className="search-select-popup" ref={popup} style={position}>
            <div
              id={popupId}
              role="listbox"
              aria-label={`${input['aria-label'] ?? input.placeholder ?? '선택'} 검색 결과`}
            >
              {options.map((option, index) => (
                <div
                  key={source.id(option)}
                  id={`${popupId}-${index}`}
                  role="option"
                  data-value={source.id(option)}
                  aria-selected={active === index}
                  className={active === index ? 'is-active' : ''}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => choose(option)}
                >
                  {source.label(option)}
                </div>
              ))}
            </div>
            {result.isFetching && <p role="status">검색 중입니다.</p>}
            {!result.isFetching && !result.isError && !options.length && (
              <p role="status">조건에 맞는 자료가 없습니다.</p>
            )}
            {result.isError && (
              <p role="alert">
                자료를 불러오지 못했습니다.{' '}
                <button type="button" onClick={() => void result.refetch()}>
                  다시 시도
                </button>
              </p>
            )}
            {result.hasNextPage && (
              <button type="button" disabled={result.isFetching} onClick={() => void result.fetchNextPage()}>
                더 불러오기
              </button>
            )}
            {value && (
              <button type="button" onClick={() => choose()}>
                선택 해제
              </button>
            )}
          </div>,
          document.body,
        )}
    </div>
  )
}
