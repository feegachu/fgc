import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../../lib/api/client'
import { readEvidence } from './api'
afterEach(() => vi.restoreAllMocks())
describe('FUN-088 read-only API boundaries', () => {
  it('finds an explicitly selected reconciliation beyond the first page', async () => {
    vi.spyOn(apiClient, 'request').mockImplementation(
      async (path) =>
        ({
          data: path.includes('/results?')
            ? { summary: { resultCount: 0, matchedCount: 0, exceptionCount: 0 } }
            : path.includes('page=2')
              ? { content: [{ reconciliationRunId: 21 }], totalPages: 2 }
              : { content: [{ reconciliationRunId: 1 }], totalPages: 2 },
          error: null,
          requestId: 'READ',
        }) as never,
    )
    const evidence = await readEvidence(
      { month: '2026-07', stage: 'GA_TO_FC', reconciliationRunId: 21 },
      new AbortController().signal,
    )
    expect(evidence.reconciliation?.reconciliationRunId).toBe(21)
    expect(vi.mocked(apiClient.request).mock.calls.every(([, options]) => !options?.method)).toBe(true)
  })
  it('does not complete after a failed read', async () => {
    vi.spyOn(apiClient, 'request').mockRejectedValue(new Error('503'))
    await expect(
      readEvidence({ month: '2026-07', stage: 'GA_TO_FC', paymentId: 3 }, new AbortController().signal),
    ).rejects.toThrow('503')
  })
  it('uses the date contract for exception filtering and rejects an unrelated exception', async () => {
    vi.spyOn(apiClient, 'request').mockImplementation(
      async (path) =>
        ({
          data: path.includes('/exceptions?') ? { content: [], totalElements: 0, totalPages: 0 } : {},
          error: null,
          requestId: 'READ',
        }) as never,
    )
    await expect(
      readEvidence(
        { month: '2026-07', stage: 'GA_TO_FC', validationRunId: 4, exceptionId: 99 },
        new AbortController().signal,
      ),
    ).rejects.toThrow('현재 검증 실행')
    expect(
      vi
        .mocked(apiClient.request)
        .mock.calls.some(([path]) => path.includes('validationMonth=2026-07-01&validationRunId=4')),
    ).toBe(true)
  })
})
