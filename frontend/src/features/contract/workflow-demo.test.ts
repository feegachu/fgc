import { afterEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { createContract } from './api'
import type { ContractCreateRequest } from './api'

afterEach(() => vi.restoreAllMocks())

it('marks only explicit guide registrations and preserves normal requests', async () => {
  const send = vi.spyOn(apiClient, 'request').mockResolvedValue({ data: { contractId: 3 } } as never)
  const body = {} as ContractCreateRequest
  await createContract(body)
  expect(send.mock.calls[0][1]).not.toHaveProperty('headers')
  await createContract(body, true)
  expect(send.mock.calls[1][1]).toMatchObject({ headers: { 'X-FGC-Workflow-Demo': 'true' } })
})
