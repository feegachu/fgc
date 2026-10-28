import { EMPTY_FILTERS, toFilters } from './filters'
import type { FilterDraft } from './filters'

function activeCount(draft: FilterDraft) {
  const filters = toFilters(draft)
  return (filters.insurerIds.length > 0 ? 1 : 0) + (filters.monthlyPremium !== null ? 1 : 0) + (filters.excludeCapOverflow ? 1 : 0)
}

/** 카테고리를 고르기 전에 설정하는 선택 조건. 후보를 거르거나 기준 보험료를 바꿀 뿐 계산 규칙은 서버가 그대로 적용한다. */
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
    <details className="cb-filters">
      <summary>
        세부 조건 설정 (선택)
        {count > 0 && <span className="cb-pill">{count}개 적용</span>}
      </summary>
      <div className="cb-filters-body">
        {insurers.length > 0 && (
          <fieldset>
            <legend>비교할 보험사 (선택하지 않으면 전체)</legend>
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
        <label className="cb-field">
          <span>기준 월 보험료 (원, 비우면 내 계약 평균)</span>
          <input
            type="text"
            inputMode="numeric"
            placeholder="예: 100000"
            value={value.monthlyPremium}
            onChange={(event) => onChange({ ...value, monthlyPremium: event.target.value.replace(/\D/g, '').slice(0, 9) })}
          />
        </label>
        <label className="cb-check">
          <input
            type="checkbox"
            checked={value.excludeCapOverflow}
            onChange={(event) => onChange({ ...value, excludeCapOverflow: event.target.checked })}
          />
          <span>한도(1,200%)를 넘어 이월이 생기는 상품은 제외</span>
        </label>
        {count > 0 && (
          <button type="button" className="cb-btn" onClick={() => onChange(EMPTY_FILTERS)}>
            조건 초기화
          </button>
        )}
      </div>
    </details>
  )
}
