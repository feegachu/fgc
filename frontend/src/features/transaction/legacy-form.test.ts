import { readFileSync } from 'node:fs'
import { afterEach, expect, it, vi } from 'vitest'
import { waitFor } from '@testing-library/react'
import { ApiError } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { errorText, isNegative, usageRate, won } from '../../lib/format'
import type { Payment, Precheck } from './form-api'
const html = readFileSync('../src/main/resources/templates/transaction/form.html', 'utf8')
const source = readFileSync('../src/main/resources/static/js/features/transaction/transaction-form.js', 'utf8')
const initial: Payment = {
  commissionTransactionId: 77,
  status: 'DRAFT',
  paymentStage: 'GA_TO_FC',
  agentId: 1,
  commissionItemId: 1,
  sourceBusinessKey: 'LEGACY-77',
  settlementMonth: '2026-07-01',
  cashflowType: 'PAYMENT',
  amount: 250000,
  attributions: [
    {
      contractId: 3,
      attributionDate: '2026-07-18',
      amount: 250000,
      inclusionDecisionStatus: 'INCLUDED',
      attributionMethod: 'DIRECT',
    },
  ],
}
const el = <T extends HTMLElement = HTMLButtonElement>(id: string) => document.getElementById(id) as T
const previousUrl = window.location.href
afterEach(() => {
  vi.restoreAllMocks()
  Reflect.deleteProperty(window, 'FgcUi')
  window.history.replaceState({}, '', previousUrl)
  document.body.replaceChildren()
})
async function mount(
  options: { draft?: Payment; precheck?: Precheck; transport?: string; reject?: string; detailFailure?: boolean } = {},
) {
  let draft = options.draft ?? initial
  let confirms = 0
  const request = vi.fn(async (path: string, args: RequestOptions = {}) => {
    let data: unknown
    if (path.startsWith('/api/v1/base/agents'))
      data = {
        content: [{ agentId: 1, agentCode: 'FC-001', agentName: '설계사', rankCode: 'FC', agentStatus: 'ACTIVE' }],
      }
    else if (path.startsWith('/api/v1/base/commission-items'))
      data = [{ commissionItemId: 1, itemCode: 'BASE', itemName: '수수료', cashflowType: 'PAYMENT' }]
    else if (path.startsWith('/api/v1/contracts?'))
      data = { content: [{ contractId: 3, contractNo: 'C-003', productName: '보험', contractDate: '2026-07-18' }] }
    else if (path === '/api/v1/contracts/103') {
      if (options.detailFailure) throw new ApiError({ code: 'FGC-COMMON-404' }, 'DETAIL', 404)
      data = { contractNo: 'C-103' }
    } else if (path === '/api/v1/transactions/77' && args.method === 'PUT') {
      draft = { ...draft, ...(args.body as object) }
      data = draft
    } else if (path === '/api/v1/transactions/77') data = draft
    else if (path.endsWith('/precheck')) {
      if (draft.status === 'CONFIRMED') throw new ApiError({ code: 'FGC-TRAN-005' }, 'PRECHECK', 409)
      data = options.precheck ?? { confirmable: true, capPreview: [], blockers: [] }
    } else if (path.endsWith('/confirm')) {
      confirms++
      if (options.reject) throw new ApiError({ code: options.reject, message: '서버 거절', params: {} }, 'CAP', 422)
      draft = { ...draft, status: 'CONFIRMED' }
      if (options.transport && confirms === 1) {
        if (options.transport === 'network') throw new TypeError('Failed to fetch')
        throw new ApiError({ code: 'FGC-COMMON-500', message: 'private error' }, 'CONFIRM', 500)
      }
      data = draft
    } else throw new Error('Unexpected legacy request: ' + path)
    return { data, error: null, requestId: 'legacy-test' }
  })
  document.body.innerHTML = html
  window.history.replaceState({}, '', '/transactions/new?id=77&month=2026-07')
  el<HTMLInputElement>('settlementMonth').value = '2026-07'
  const dialog = document.querySelector('[data-modal="transaction-confirm"]') as HTMLElement
  const modal = {
    open: vi.fn(() => {
      dialog.hidden = false
    }),
    close: vi.fn(() => {
      dialog.hidden = true
    }),
  }
  dialog.querySelector('[data-modal-close]')!.addEventListener('click', () => modal.close())
  const nativeTimeout = window.setTimeout.bind(window)
  vi.spyOn(window, 'setTimeout').mockImplementation((handler, delay, ...args) =>
    Reflect.apply(nativeTimeout, window, delay === 900 ? [() => undefined, 0] : [handler, delay, ...args]),
  )
  Object.defineProperty(window, 'FgcUi', {
    configurable: true,
    value: {
      apiClient: { request },
      format: { errorText, isNegative, usageRate, won, today: () => '2026-07-31' },
      modal,
      toast: { show: vi.fn() },
    },
  })
  // Execute the actual legacy entry point and template so these checks cover the retained screen.
  new Function('window', 'document', source)(window, document)
  await waitFor(() => expect(document.querySelector('[data-transaction-form]')).toHaveAttribute('aria-busy', 'false'))
  return { request, dialog, modal }
}
async function precheckAndOpen() {
  el('btn-precheck').click()
  await waitFor(() => expect(el('btn-confirm')).toBeEnabled())
  el('btn-confirm').click()
}
it('legacy normal saved draft prechecks and confirms once with the original key (#410)', async () => {
  const { request, modal } = await mount()
  expect(el('btn-precheck')).toBeEnabled()
  await precheckAndOpen()
  el('btn-confirm-submit').click()
  await waitFor(() => expect(modal.close).toHaveBeenCalled())
  expect(el('tx-status')).toHaveTextContent('확정 · #77')
  expect(
    request.mock.calls.filter(([path]) => path.endsWith('/confirm')).map(([, args]) => args?.idempotencyKey),
  ).toEqual(['TRAN-CONFIRM-77'])
})
it.each(['network', '500'])(
  'legacy %s response loss survives close/reopen without losing precheck recovery (#410)',
  async (transport) => {
    const { request, dialog, modal } = await mount({ transport })
    await precheckAndOpen()
    el('btn-confirm-submit').click()
    await waitFor(() => expect(el('btn-confirm')).toBeEnabled())
    expect(el('btn-precheck')).toBeDisabled()
    expect(el('btn-save')).toBeDisabled()
    expect(el('bizKey')).toBeDisabled()
    expect(el('transaction-confirm-blockers')).not.toHaveTextContent('private error')
    dialog.querySelector<HTMLButtonElement>('[data-modal-close]')!.click()
    expect(dialog).not.toBeVisible()
    el('btn-precheck').click()
    expect(request.mock.calls.filter(([path]) => path.endsWith('/precheck'))).toHaveLength(1)
    el('btn-confirm').click()
    expect(dialog).toBeVisible()
    expect(el('btn-confirm-submit')).toBeEnabled()
    el('btn-confirm-submit').click()
    await waitFor(() => expect(modal.close).toHaveBeenCalledTimes(2))
    expect(
      request.mock.calls.filter(([path]) => path.endsWith('/confirm')).map(([, args]) => args?.idempotencyKey),
    ).toEqual(['TRAN-CONFIRM-77', 'TRAN-CONFIRM-77'])
  },
)
it.each(['preview', 'detail', 'failed detail'])(
  'legacy exception follow-up survives first-page limits via %s (#410)',
  async (origin) => {
    const code = origin === 'preview' ? 'FGC-CAP-001' : 'FGC-CAP-002'
    const { request } = await mount({
      draft: { ...initial, attributions: [{ ...initial.attributions![0], contractId: 103 }] },
      reject: code,
      detailFailure: origin === 'failed detail',
      precheck: {
        confirmable: false,
        blockers: [{ code, contractId: 103, message: '차단' }],
        capPreview: origin === 'preview' ? [{ contractId: 103, contractNo: 'C-103' }] : [],
      },
    })
    await precheckAndOpen()
    expect(el('transaction-confirm-followup').querySelector('a')).toBeNull()
    el('btn-confirm-submit').click()
    const link = () => el('transaction-confirm-followup').querySelector('a[href*="/exceptions?"]') as HTMLAnchorElement
    await waitFor(() => expect(link()).not.toBeNull())
    if (origin !== 'failed detail') await waitFor(() => expect(link().href).toContain('contractNo=C-103'))
    else await waitFor(() => expect(request).toHaveBeenCalledWith('/api/v1/contracts/103'))
    expect(link().href).not.toContain('C-003')
    expect(link().href).toContain(origin === 'preview' ? 'type=CAP_VIOLATION' : 'type=CAP_REVIEW_REQUIRED')
    expect(el('btn-confirm-submit')).not.toBeVisible()
    expect(el('btn-confirm')).toBeDisabled()
    if (origin === 'preview')
      expect(request.mock.calls.filter(([path]) => path === '/api/v1/contracts/103')).toHaveLength(0)
  },
)
it('legacy empty saved draft stays empty and cannot confirm a missing-attribution blocker (#410)', async () => {
  await mount({
    draft: { ...initial, attributions: [] },
    precheck: { confirmable: false, capPreview: [], blockers: [{ code: 'FGC-TRAN-002' }] },
  })
  expect(document.querySelectorAll('[data-attr-row]')).toHaveLength(0)
  expect(el('btn-precheck')).toBeEnabled()
  await precheckAndOpen()
  expect(el('btn-confirm-submit')).not.toBeVisible()
})
it('legacy normalized saved attribution requires PUT before precheck (#410)', async () => {
  const { request } = await mount({
    draft: {
      ...initial,
      paymentStage: 'INSURER_TO_GA',
      agentId: undefined,
      attributions: [
        {
          ...initial.attributions![0],
          inclusionDecisionStatus: 'EXCLUDED',
          exclusionType: 'NEW_AGENT_SUPPORT',
          evidenceRef: 'EVIDENCE',
        },
      ],
    },
  })
  expect(document.querySelector('[data-inclusion]')).toHaveValue('INCLUDED')
  expect(el('btn-precheck')).toBeDisabled()
  expect(el('transaction-form-status')).toHaveTextContent('먼저 저장')
  el('btn-save').click()
  await waitFor(() => expect(el('btn-precheck')).toBeEnabled())
  expect(request.mock.calls.find(([, args]) => args?.method === 'PUT')?.[1]?.body).toMatchObject({
    attributions: [{ inclusionDecisionStatus: 'INCLUDED', attributionMethod: 'DIRECT' }],
  })
})

it('legacy ignores a precheck response that predates a draft edit and save (#410)', async () => {
  const { request } = await mount()
  type Envelope = { data: unknown; error: null; requestId: string }
  let complete!: (response: Envelope) => void
  const pending = new Promise<Envelope>((resolve) => {
    complete = resolve
  })
  request.mockImplementationOnce(() => pending)
  el('btn-precheck').click()
  const key = el<HTMLInputElement>('bizKey')
  key.value = 'EDITED-DRAFT'
  key.dispatchEvent(new Event('input', { bubbles: true }))
  expect(el('btn-confirm')).toBeDisabled()
  el('btn-save').click()
  await waitFor(() => expect(el('btn-precheck')).toBeEnabled())
  complete({ data: { confirmable: true, blockers: [], capPreview: [] }, error: null, requestId: 'OLD-PRECHECK' })
  await pending
  await waitFor(() => expect(el('btn-precheck')).not.toHaveClass('is-loading'))
  expect(el('btn-confirm')).toBeDisabled()
})
