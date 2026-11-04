import { describe, expect, it } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { shouldRetry } from '../../app/queryClient'
import { ApiError } from '../../lib/api/client'
import { readFilters, statementDetailQuery, statementError, statementListQuery, statementPath } from './api'

describe('명세 요청 경로의 설계사 ID', () => {
  it.each(['1', '9', '9007199254740993', '9223372036854775807'])('Long ID %s를 정밀도 손실 없이 사용한다', (id) => {
    expect(statementPath(id)).toBe(`/api/v1/reports/agents/${id}/statements`)
  })
  it.each(['', '0', '-1', '01', '1.5', '1e3', ' 9', '9/..', '../../transactions', '%2f', '9223372036854775808', '99999999999999999999'])('잘못된 ID %s를 본인 경로로 대체하지 않고 거절한다', (id) => {
    expect(() => statementPath(id)).toThrow('설계사 ID를 확인')
  })
  it('명시적인 본인 조회만 본인 경로를 사용한다', () => {
    expect(statementPath(null)).toBe('/api/v1/agent/me/statements')
  })
})

it('운영 재시도 정책에서도 잘못된 ID는 목록·상세 모두 한 번만 검증한다', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: shouldRetry, retryDelay: 0 } } })
  const filters = readFilters(new URLSearchParams(), '2026-07')
  try {
    const listQuery = statementListQuery(filters, '../invalid', true)
    await expect(client.fetchQuery(listQuery)).rejects.toThrow('설계사 ID를 확인')
    expect(client.getQueryState(listQuery.queryKey)?.fetchFailureCount).toBe(1)
    const detailQuery = statementDetailQuery('entry', '../invalid', true)
    await expect(client.fetchQuery(detailQuery)).rejects.toThrow('설계사 ID를 확인')
    expect(client.getQueryState(detailQuery.queryKey)?.fetchFailureCount).toBe(1)
  } finally {
    client.clear()
  }
})

it('요청 ID가 없는 서버 오류도 실패 안내와 대체 ID를 표시한다', () => {
  expect(statementError(new ApiError(null, null, 500))).toBe('수수료 명세를 불러오지 못했습니다. 잠시 후 다시 시도하세요. (요청 ID: -)')
})
