/** 목록의 정렬 상태 ↔ URL/API 의 `sort=key,asc|desc` 표기 (#604) */
export type SortDirection = 'asc' | 'desc'
export interface TableSort {
  key: string
  direction: SortDirection
}

/** 허용 key 목록에 없거나 형식이 틀리면 undefined (서버 기본 정렬을 따른다). */
export function parseSort(raw: string | null | undefined, allowed?: readonly string[]): TableSort | undefined {
  const [key, direction, ...rest] = (raw ?? '').split(',')
  if (!key || rest.length || (direction !== 'asc' && direction !== 'desc')) return undefined
  if (allowed && !allowed.includes(key)) return undefined
  return { key, direction }
}

export const formatSort = (sort?: TableSort) => (sort ? `${sort.key},${sort.direction}` : '')

/** DataTable 의 sort/onSort 에 그대로 넘기는 값. 정렬이 바뀌면 apply 가 URL 을 갱신한다(페이지는 호출한 쪽이 1로 돌린다). */
export function sortBinding(raw: string | null | undefined, allowed: readonly string[], apply: (value: string) => void) {
  return { sort: parseSort(raw, allowed), onSort: (next: TableSort) => apply(formatSort(next)) }
}
