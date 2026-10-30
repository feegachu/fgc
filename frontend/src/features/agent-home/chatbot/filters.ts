import type { CompareFilters } from './api'

/** 입력 중인 세부 조건. */
export interface FilterDraft {
  insurerIds: number[]
  excludeCapOverflow: boolean
}

export const EMPTY_FILTERS: FilterDraft = { insurerIds: [], excludeCapOverflow: false }

/** 서버 요청용 조건. 후보를 거르는 조건만 보내고 평가 입력(기준 보험료 등)은 서버가 본인 DB 에서 조회한다. */
export function toFilters(draft: FilterDraft): CompareFilters {
  return { insurerIds: draft.insurerIds, excludeCapOverflow: draft.excludeCapOverflow }
}
