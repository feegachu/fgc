import { Button } from './Button'
export function Pagination({ page, totalPages, onPageChange }: { page: number; totalPages: number; onPageChange: (page: number) => void }) {
  const last = Math.max(1, totalPages)
  const current = Math.max(1, Math.min(last, page))
  const start = Math.floor((current - 1) / 5) * 5 + 1
  return <nav className="pagination-controls" aria-label="페이지 선택"><Button variant="ghost" disabled={current === 1} onClick={() => onPageChange(1)}>처음</Button><Button variant="ghost" disabled={start === 1} onClick={() => onPageChange(start - 1)}>이전</Button>{Array.from({ length: Math.min(5, last - start + 1) }, (_, index) => start + index).map((number) => <button type="button" className={`pagination-button ${current === number ? 'is-active' : ''}`} key={number} aria-current={current === number ? 'page' : undefined} onClick={() => onPageChange(number)}>{number}</button>)}<Button variant="ghost" disabled={start + 4 >= last} onClick={() => onPageChange(start + 5)}>다음</Button><Button variant="ghost" disabled={current === last} onClick={() => onPageChange(last)}>마지막</Button></nav>
}
