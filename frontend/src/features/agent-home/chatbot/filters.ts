import type { CompareFilters } from './api'

/** 입력 중인 세부 조건. 보험료는 입력 편의를 위해 문자열로 들고 있다가 요청 직전에 숫자로 바꾼다. */
export interface FilterDraft {
  insurerIds: number[]
  monthlyPremium: string
  excludeCapOverflow: boolean
}

export const EMPTY_FILTERS: FilterDraft = { insurerIds: [], monthlyPremium: '', excludeCapOverflow: false }

const MAX_PREMIUM = 100_000_000

/** 서버 요청용 조건. 비어 있거나 잘못된 보험료는 보내지 않는다. */
export function toFilters(draft: FilterDraft): CompareFilters {
  const premium = Number(draft.monthlyPremium)
  return {
    insurerIds: draft.insurerIds,
    monthlyPremium: Number.isInteger(premium) && premium > 0 && premium <= MAX_PREMIUM ? premium : null,
    excludeCapOverflow: draft.excludeCapOverflow,
  }
}
