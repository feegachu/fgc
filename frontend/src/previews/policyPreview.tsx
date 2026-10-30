import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { AppShellView } from '../app/shell/AppShell'
import { PoliciesPage } from '../features/policies/PoliciesPage'
import { PolicyPreviewContext, policyTypes, sources, statusLabels } from '../features/policies/management'
import type { PolicyWritePreview } from '../features/policies/management'
import type { Policy } from '../features/policies/api'
import { useAuthStore } from '../stores/auth'
import {
  actionsByStatus,
  createPreviewPolicies,
  nextStatus,
  previewCommissionRules,
  previewUser,
} from './policyFixtures'
import '../index.css'
import '../styles/variables.css'
import '../styles/reset.css'
import '../styles/layout.css'
import '../styles/components.css'
import '../styles/utilities.css'
import '../styles/react.css'
import '../features/reference/reference.css'
import '../features/policies/policies.css'

if (!import.meta.env.DEV) throw new Error('Policy preview is only available in the development server.')
const records = createPreviewPolicies()
let sequence = records.length
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
const originalFetch = globalThis.fetch
// Intercept every API call in this separate document. There is no network fallback for API paths.
globalThis.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.origin)
  if (!url.pathname.startsWith('/api/')) return originalFetch(input, init)
  let data: unknown
  const path = url.pathname
  if (path.endsWith('/auth/me')) data = previewUser
  else if (path.endsWith('/auth/logout')) data = null
  else if (path.endsWith('/auth/refresh'))
    data = { accessToken: 'ui-preview-only', expiresIn: 1800, tokenType: 'Bearer' }
  else if (path === '/api/v1/policies') data = records.map((record) => record.header)
  else if (/^\/api\/v1\/policies\/\d+$/.test(path))
    data = records.find((record) => record.header?.policyVersionId === Number(path.split('/').at(-1)))
  else if (path.includes('/base/commission-items'))
    data = [
      { commissionItemId: 1, itemName: '유지관리수수료', activeYn: true },
      { commissionItemId: 2, itemName: '판매수수료', activeYn: true },
    ]
  else if (path.includes('/base/insurers'))
    data = { content: [{ insurerId: 1, insurerName: '예시생명', activeYn: true }], totalPages: 1 }
  else if (path.includes('/base/organizations'))
    data = { content: [{ organizationId: 1, organizationName: '예시 GA 본점', activeYn: true }], totalPages: 1 }
  else if (path.includes('/base/products'))
    data = {
      content: [
        {
          productOfferingId: 101,
          productName: '예시 종신보험',
          offeringVersion: '2029-A',
          salesStartDate: '2029-01-01',
        },
        {
          productOfferingId: 102,
          productName: '예시 종신보험',
          offeringVersion: '2029-B',
          salesStartDate: '2029-04-01',
        },
      ],
      totalPages: 1,
    }
  else if (path.includes('/settlement-months')) data = [{ settlementMonth: '2029-01', status: 'OPEN' }]
  else
    return Response.json(
      {
        data: null,
        error: { code: 'PREVIEW_ONLY', message: '정책 UI 미리보기에서 제공하지 않는 기능입니다.' },
        requestId: 'ui-preview',
      },
      { status: 404 },
    )
  return Response.json({ data, error: null, requestId: 'ui-preview' })
}
useAuthStore.getState().setAccessToken('ui-preview-only')
client.setQueryData(['auth', 'me'], previewUser)
const pause = () => new Promise((resolve) => setTimeout(resolve, 250))
const workflow: PolicyWritePreview = {
  async save(draft, id) {
    await pause()
    const current = records.find((record) => record.header?.policyVersionId === id)
    if (current && current.header?.status !== 'DRAFT') throw new Error('초안만 수정할 수 있습니다.')
    const policyVersionId = id ?? ++sequence
    const header: Policy = {
      ...current?.header,
      policyVersionId,
      policyCode: draft.policyCode,
      policyName: draft.policyName,
      policyType: draft.policyType as Policy['policyType'],
      policyTypeLabel: policyTypes[draft.policyType as keyof typeof policyTypes],
      sourceClass: draft.sourceClass as Policy['sourceClass'],
      sourceClassLabel: sources[draft.sourceClass as keyof typeof sources],
      versionNo: current?.header?.versionNo ?? 1,
      status: 'DRAFT',
      statusLabel: statusLabels.DRAFT,
      effectiveFrom: draft.effectiveFrom,
      effectiveTo: draft.effectiveTo || null,
      regulationRefs: draft.regulationRefs,
      createdBy: current?.header?.createdBy ?? previewUser.loginId,
    }
    const record = {
      ...current,
      header,
      editDocument: structuredClone(draft),
      allowedActions: actionsByStatus.DRAFT,
      sourceDetails: draft.sourceDetails,
      sourceRefs: draft.sourceRefs,
      commissionRules: previewCommissionRules(draft),
      capRuleSets: [],
      refundRateTables: [],
      approvalActions: current?.approvalActions ?? [
        {
          actionType: 'CREATE',
          previousStatus: null,
          status: 'DRAFT',
          actor: previewUser.loginId,
          actedAt: new Date().toISOString(),
          reason: '미리보기 초안 등록',
        },
      ],
    }
    if (current) records[records.indexOf(current)] = record
    else records.unshift(record)
    return policyVersionId
  },
  async action(id, action, reason, dates) {
    await pause()
    const record = records.find((r) => r.header?.policyVersionId === id)!
    const header = record.header!
    if (!record.allowedActions?.includes(action)) throw new Error('현재 상태에서는 실행할 수 없습니다.')
    if (action === 'APPROVE' && header.createdBy === previewUser.loginId)
      throw new Error('작성자는 자기 정책을 승인할 수 없습니다.')
    if (
      action === 'SUBMIT' &&
      (!record.editDocument?.sourceDetails.length ||
        !(
          record.editDocument.commissionRules.length +
          record.editDocument.feeRegimeRules.length +
          record.editDocument.parameters.length +
          record.editDocument.clawbackRules.length
        ))
    )
      throw new Error('제출 전에 규칙과 근거 자료를 각각 하나 이상 추가하세요.')
    if (action === 'SUBMIT' && record.editDocument?.clawbackRules.some((r) => !r.clawbackBasisCode))
      throw new Error('환수 기준이 미확정입니다. 기준금액 코드가 확정된 후 제출하세요.')
    if (action === 'NEW_VERSION') {
      const copy = structuredClone(record)
      const nextId = ++sequence
      copy.header = {
        ...header,
        policyVersionId: nextId,
        versionNo:
          Math.max(
            ...records.filter((r) => r.header?.policyCode === header.policyCode).map((r) => r.header?.versionNo ?? 0),
          ) + 1,
        status: 'DRAFT',
        statusLabel: statusLabels.DRAFT,
        createdBy: previewUser.loginId,
        approvedBy: null,
        approvedAt: null,
        effectiveFrom: dates!.effectiveFrom,
        effectiveTo: dates!.effectiveTo || null,
      }
      copy.allowedActions = actionsByStatus.DRAFT
      copy.createdBy = previewUser.loginId
      copy.approvedBy = null
      copy.approvedAt = null
      copy.approvalActions = [
        {
          actionType: 'NEW_VERSION',
          previousStatus: null,
          status: 'DRAFT',
          actor: previewUser.loginId,
          actedAt: new Date().toISOString(),
          reason,
        },
      ]
      if (copy.editDocument) {
        copy.editDocument.effectiveFrom = dates!.effectiveFrom
        copy.editDocument.effectiveTo = dates!.effectiveTo
        copy.editDocument.commissionRules.forEach((r) => delete r.commissionRuleId)
        copy.editDocument.feeRegimeRules.forEach((r) => delete r.feeRegimeRulesetId)
      }
      records.unshift(copy)
      return nextId
    }
    const status = nextStatus[action]!
    record.approvalActions?.push({
      actionType: action,
      previousStatus: header.status ?? null,
      status,
      actor: previewUser.loginId,
      actedAt: new Date().toISOString(),
      reason,
    })
    record.header = {
      ...header,
      status,
      statusLabel: statusLabels[status],
      ...(action === 'APPROVE' ? { approvedBy: previewUser.loginId, approvedAt: new Date().toISOString() } : {}),
    }
    record.allowedActions = actionsByStatus[status]
    return id
  },
}
const router = createMemoryRouter(
  [
    {
      element: <AppShellView user={previewUser} defaultMonth="2029-01" />,
      children: [
        { path: '/policies', element: <PoliciesPage /> },
        {
          path: '*',
          element: (
            <div className="surface" style={{ padding: 32 }}>
              정책 UI 미리보기입니다. <a data-guide-feature="previews.policy-preview.a" href="/policy-preview">정책 화면으로 돌아가기</a>
            </div>
          ),
        },
      ],
    },
  ],
  { initialEntries: ['/policies?asOf=2029-01-01&month=2029-01'] },
)
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <PolicyPreviewContext.Provider value={workflow}>
      <RouterProvider router={router} />
    </PolicyPreviewContext.Provider>
  </QueryClientProvider>,
)
