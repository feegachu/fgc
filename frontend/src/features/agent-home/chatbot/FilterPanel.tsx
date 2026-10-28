import { EMPTY_FILTERS, toFilters } from './filters'
import type { FilterDraft } from './filters'

function activeCount(draft: FilterDraft) {
  const filters = toFilters(draft)
  return (filters.insurerIds.length > 0 ? 1 : 0) + (filters.excludeCapOverflow ? 1 : 0)
}

/** 카테고리를 고르기 전에 설정하는 선택 조건. 후보를 거를 뿐 평가 입력과 계산 규칙은 서버가 그대로 적용한다. */
export function FilterPanel({
  insurers,
  value,
  onChange,
}: {
  insurers: { id: number; name: string }[]
  value: FilterDraft
  onChange: (next: FilterDraft) => void
}) {
  const count = activeCount(value)
  const toggleInsurer = (id: number) =>
    onChange({
      ...value,
      insurerIds: value.insurerIds.includes(id) ? value.insurerIds.filter((x) => x !== id) : [...value.insurerIds, id],
    })
  return (
    <section className="cb-filters" aria-label="보험사 세부 선택">
      <div className="cb-filters-head">
        보험사 세부 선택
        <span className="cb-muted">선택 사항</span>
        {count > 0 && <span className="cb-pill">{count}개 적용</span>}
        {count > 0 && (
          <button type="button" className="cb-filters-reset" onClick={() => onChange(EMPTY_FILTERS)}>
            초기화
          </button>
        )}
      </div>
      <div className="cb-filters-body">
        {insurers.length > 0 && (
          <fieldset aria-label="비교할 보험사">
            <div className="cb-chips">
              {insurers.map((insurer) => (
                <button
                  key={insurer.id}
                  type="button"
                  className={`cb-chip${value.insurerIds.includes(insurer.id) ? ' on' : ''}`}
                  aria-pressed={value.insurerIds.includes(insurer.id)}
                  onClick={() => toggleInsurer(insurer.id)}
                >
                  {insurer.name}
                </button>
              ))}
            </div>
          </fieldset>
        )}
        <label className="cb-filter-check">
          <input
            type="checkbox"
            checked={value.excludeCapOverflow}
            onChange={(event) => onChange({ ...value, excludeCapOverflow: event.target.checked })}
          />
          <span>한도를 넘어 이월이 생기는 상품은 제외</span>
        </label>
      </div>
    </section>
  )
}
