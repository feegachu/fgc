/// <reference types="node" />
import { describe, expect, it, vi } from 'vitest'
import { apiClient, createApiClient } from './client'
import type { MeResponse } from './client'
import { conditionOptions, emptyDraft } from '../../features/policies/management'
import { normalizePolicyDetail, performPolicyAction, savePolicyDraft } from '../../features/policies/writeApi'
import type { PolicyDetailResponse } from '../../features/policies/writeApi'

const origin = process.env.FGC_INTEGRATION_URL
// The Java opt-in test starts a real HTTP server and an isolated Testcontainers database.
// Java signs an expired access for the same live session; refresh returns a normal token.
describe.skipIf(!origin)('실제 JWT 서버 연동', () => {
  function environment() {
    let cookie = ''
    let token: string | null = null
    const calls: string[] = []
    const redirects: string[] = []
    const fetcher: typeof fetch = async (path, init) => {
      calls.push(String(path))
      const headers = new Headers(init?.headers)
      headers.set('Origin', origin!)
      if (cookie) headers.set('Cookie', cookie)
      const response = await fetch(`${origin}${path}`, { ...init, headers })
      const setCookie = response.headers.get('Set-Cookie')
      if (setCookie) cookie = setCookie.split(';')[0]
      return response
    }
    const client = createApiClient({
      fetch: fetcher,
      base: '/app/',
      location: {
        pathname: '/app/contracts',
        search: '?page=2',
        hash: '',
        assign: (url) => {
          redirects.push(String(url))
        },
      },
      getToken: () => token,
      setToken: (value) => {
        token = value
      },
    })
    return {
      client,
      calls,
      redirects,
      getToken: () => token,
      setToken: (value: string) => {
        token = value
      },
      setCookie: (value: string) => {
        cookie = value
      },
      raw: fetcher,
    }
  }
  it('실제 access 만료 → 동시 3요청 refresh 1회 → 원 요청 성공', async () => {
    const env = environment()
    env.setToken(process.env.FGC_EXPIRED_ACCESS!)
    env.setCookie(`fgc_refresh=${process.env.FGC_EXPIRED_REFRESH!}`)
    const results = await Promise.all([1, 2, 3].map(() => env.client.request('/api/v1/contracts')))
    expect(results.every((result) => result.error === null)).toBe(true)
    expect(env.calls.filter((path) => path === '/api/v1/auth/refresh')).toHaveLength(1)
    expect(env.getToken()).toBeTruthy()
    expect(env.redirects).toHaveLength(0)
  })
  it('실제 refresh 쿠키 없음 → 토큰 삭제와 원 경로 보존', async () => {
    const env = environment()
    await env.client.login('settle01', 'fgc1234!')
    env.setCookie('')
    env.setToken('invalid-access')
    await expect(env.client.request('/api/v1/contracts')).rejects.toMatchObject({ status: 401, code: 'FGC-AUTH-002' })
    expect(env.getToken()).toBeNull()
    expect(env.redirects).toEqual(['/app/login?redirect=%2Fcontracts%3Fpage%3D2'])
  })
  it('실제 중복 로그인 업무 오류는 refresh 0회와 duplicate 이유를 보존한다', async () => {
    const older = environment()
    const newer = environment()
    await older.client.login('settle01', 'fgc1234!')
    await newer.client.login('settle01', 'fgc1234!')
    await expect(older.client.request('/api/v1/contracts')).rejects.toMatchObject({ status: 401, code: 'FGC-AUTH-004' })
    expect(older.calls.filter((path) => path === '/api/v1/auth/refresh')).toHaveLength(0)
    expect(older.getToken()).toBeNull()
    expect(older.redirects).toEqual(['/app/login?reason=duplicate&redirect=%2Fcontracts%3Fpage%3D2'])
  })
  it('실제 /auth/me는 선택 월과 무관하게 서버 기본 기준월을 반환한다', async () => {
    const env = environment()
    await env.client.login('audit01', 'fgc1234!')
    try {
      const response = await env.client.request<MeResponse>('/api/v1/auth/me?month=2040-12')
      expect(response.data.demoMonth).toBe(process.env.FGC_EXPECTED_DEMO_MONTH)
      expect(response.data.canViewAuditLog).toBe(true)
    } finally {
      await env.client.logout()
    }
  })
  it('정책 UI 어댑터로 실제 초안 저장·수정·제출·다른 사용자 승인·활성화·복제를 수행한다', async () => {
    const writer = environment()
    const approver = environment()
    await writer.client.login('admin', 'fgc1234!')
    await approver.client.login('gaadmin', 'fgc1234!')
    const request = vi.spyOn(apiClient, 'request').mockImplementation(writer.client.request)
    const key = () => crypto.randomUUID()
    const draft = {
      ...emptyDraft(),
      policyCode: `UI-HTTP-${key()}`,
      policyName: '실제 HTTP 정책 UI 연결 검증',
      policyType: 'SCHEDULE_ELIGIBILITY',
      sourceClass: 'GA_POLICY',
      effectiveFrom: '2000-01-01',
      effectiveTo: '2000-12-31',
      regulationRefs: ['REG-03'],
      sourceRefs: ['SRC-022'],
      sourceDetails: [
        {
          ref: 'SRC-022',
          title: '승인된 근거',
          explanation: '',
          url: null,
          statusTags: ['회사정책'],
        },
      ],
      parameters: conditionOptions.map(({ key, value, description }) => ({ key, value, description })),
    }
    const read = async (id: number) =>
      normalizePolicyDetail((await apiClient.request<PolicyDetailResponse>(`/api/v1/policies/${id}`)).data)
    try {
      const createKey = key()
      const created = await savePolicyDraft(draft, undefined, createKey)
      expect(created.status).toBe('DRAFT')
      expect(await savePolicyDraft(draft, undefined, createKey)).toEqual(created)
      const detail = await read(created.policyVersionId)
      expect(detail.editDocument?.basicDocumentVersion).toBe('')
      expect(detail.editDocument?.sourceDetails[0]).toMatchObject({ ref: 'SRC-022', explanation: '', url: null })
      expect(detail.editDocument?.sourceDetails[0]).not.toHaveProperty('statusTags')
      expect(detail.updatedAt).toBe(detail.editDocument?.expectedUpdatedAt)
      expect(detail.allowedActions).toContain('SUBMIT')
      const updated = await savePolicyDraft(
        { ...detail.editDocument!, policyName: '수정한 HTTP 정책' },
        created.policyVersionId,
        key(),
      )
      expect(updated.updatedAt).not.toBe(created.updatedAt)
      await expect(savePolicyDraft(detail.editDocument!, created.policyVersionId, key())).rejects.toMatchObject({
        status: 409,
        code: 'FGC-POL-003',
      })
      const review = await performPolicyAction(
        created.policyVersionId,
        'SUBMIT',
        '검토 요청',
        {
          expectedStatus: updated.status,
          expectedUpdatedAt: updated.updatedAt,
        },
        key(),
      )
      expect(review.status).toBe('REVIEW')
      expect((await read(created.policyVersionId)).allowedActions).not.toContain('APPROVE')
      await expect(
        performPolicyAction(
          created.policyVersionId,
          'APPROVE',
          '자기 승인 차단 확인',
          {
            expectedStatus: review.status,
            expectedUpdatedAt: review.updatedAt,
          },
          key(),
        ),
      ).rejects.toMatchObject({ status: 409, code: 'FGC-POL-001' })
      request.mockImplementation(approver.client.request)
      expect((await read(created.policyVersionId)).allowedActions).toContain('APPROVE')
      const approved = await performPolicyAction(
        created.policyVersionId,
        'APPROVE',
        '다른 사용자 승인',
        {
          expectedStatus: review.status,
          expectedUpdatedAt: review.updatedAt,
        },
        key(),
      )
      const active = await performPolicyAction(
        created.policyVersionId,
        'ACTIVATE',
        '검증 기간 적용',
        {
          expectedStatus: approved.status,
          expectedUpdatedAt: approved.updatedAt,
        },
        key(),
      )
      expect(active.status).toBe('ACTIVE')
      const activeDetail = await read(created.policyVersionId)
      expect(activeDetail.allowedActions).toContain('NEW_VERSION')
      expect(activeDetail.approvalActions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            actionType: 'APPROVE',
            actor: 'gaadmin',
            reason: '다른 사용자 승인',
            actedAt: expect.any(String),
          }),
        ]),
      )
      const cloned = await performPolicyAction(
        created.policyVersionId,
        'NEW_VERSION',
        '새 버전 작성',
        {
          effectiveFrom: '2001-01-01',
          effectiveTo: '',
        },
        key(),
      )
      expect(cloned).toMatchObject({ status: 'DRAFT', versionNo: 2, approvedBy: null, approvedAt: null })
      const cloneDetail = await read(cloned.policyVersionId)
      expect(cloneDetail.editDocument?.parameters).toHaveLength(draft.parameters.length)
      expect(cloneDetail.editDocument?.parameters).toEqual(expect.arrayContaining(draft.parameters))
      expect(cloneDetail.editDocument?.effectiveTo).toBe('')
    } finally {
      request.mockRestore()
      await Promise.allSettled([writer.client.logout(), approver.client.logout()])
    }
  }, 30_000)
})
