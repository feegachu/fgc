import { Pagination } from '../../components/Pagination'

export function ReconciliationPagination({
  page,
  totalPages,
  label,
  onPageChange,
}: {
  page: number
  totalPages: number
  label: string
  onPageChange: (page: number) => void
}) {
  if (totalPages <= 1) return null
  return (
    <footer className="reco-react-pagination">
      <Pagination legacy page={page} totalPages={totalPages} label={label} onPageChange={onPageChange} />
    </footer>
  )
}
