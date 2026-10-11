import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// 운영 CONT-W03 템플릿과 스크립트를 그대로 실행해 정밀한 조회 토큰과 409 후 화면 동작을 검증한다.
const template = readFileSync(resolve(process.cwd(), '../src/main/resources/templates/contract/form.html'), 'utf8')
const script = readFileSync(resolve(process.cwd(), '../src/main/resources/static/js/features/contract/contract-form.js'), 'utf8')
const timestamp = '2026-10-11T01:00:00.123456Z'
const contract = {
  contractId: 21, contractNo: 'CONS-21', contractDate: '2026-07-10', contractStatus: 'ACTIVE',
  insurerId: 1, insurerName: '보험사', productOfferingId: 1, productName: '상품', offeringVersion: 'v1',
  agentId: 1, agentName: '설계사', organizationId: 4, organizationName: '조직', paymentCycleCode: 'MONTHLY',
  premiumPerCycleAmount: 100000, firstPremiumAmount: 100000, monthlyEquivalentFirstPremium: 100000,
  paymentTermMonths: 120, standardSurrenderDeductionAmount: 0, updatedAt: timestamp,
}

function input(id: string) { return document.getElementById(id) as HTMLInputElement }
function saveButton() { return document.getElementById('contract-save-button') as HTMLButtonElement }
function submit() { document.getElementById('contract-form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }

async function initialize(updatedAt: string | null = timestamp, mode = 'edit') {
  document.body.innerHTML = template.match(/<main\b[\s\S]*?<\/main>/)![0]
  // 서버가 렌더링하는 enum 옵션은 브라우저 fixture에서 동일한 값으로 복원한다.
  document.getElementById('contract-status')!.innerHTML = '<option value="ACTIVE">정상</option>'
  document.getElementById('payment-cycle-code')!.innerHTML = '<option value="MONTHLY">월납</option>'
  const form = document.getElementById('contract-form')!
  form.dataset.mode = mode
  form.dataset.contractId = '21'
  const api = {
    getContract: vi.fn().mockResolvedValue({ data: { ...contract, updatedAt } }),
    getInsurers: vi.fn().mockResolvedValue({ data: { content: [{ insurerId: 1, insurerName: '보험사', insurerCode: 'INS', activeYn: true }] } }),
    getProductOfferings: vi.fn().mockResolvedValue({ data: { content: [{ productOfferingId: 1, productName: '상품', offeringVersion: 'v1' }] } }),
    getAgents: vi.fn().mockResolvedValue({ data: { content: [{ agentId: 1, agentName: '설계사', agentCode: 'FC1', rankCode: 'FC', agentStatus: 'ACTIVE', activeYn: true, organizationId: 4, organizationName: '조직' }] } }),
    updateContract: vi.fn().mockRejectedValue({ code: 'FGC-CONT-003' }),
    createContract: vi.fn().mockRejectedValue({ code: 'FGC-COMMON-002', message: '테스트 저장 오류' }),
  }
  Object.assign(window, { FgcUi: { contractApi: api, toast: vi.fn() } })
  // Date와 달리 문자열에 들어 있는 PostgreSQL의 마이크로초를 그대로 전송해야 한다.
  new Function(script)()
  await vi.waitFor(() => expect(document.getElementById('contract-save-hint')!.textContent).not.toContain('불러오는 중'))
  return api
}

describe('legacy contract edit conflict', () => {
  beforeEach(() => {
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
    vi.spyOn(window, 'confirm').mockReturnValue(false)
  })
  afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })

  it('sends the exact timestamp and preserves edits while preventing retries after 409', async () => {
    const api = await initialize()
    input('contract-no').value = 'USER-EDIT'
    submit()
    await vi.waitFor(() => expect(document.getElementById('contract-form-error-message')).toHaveTextContent('현재 입력은 유지됩니다'))
    expect(saveButton()).toBeDisabled()
    expect(api.updateContract).toHaveBeenCalledWith('21', expect.objectContaining({ expectedUpdatedAt: timestamp, contractNo: 'USER-EDIT' }))
    expect(input('contract-no').value).toBe('USER-EDIT')
    expect(document.getElementById('contract-form-error-message')).toHaveTextContent('현재 입력은 유지됩니다')
    const reload = document.getElementById('contract-reload-button') as HTMLButtonElement
    expect(reload.hidden).toBe(false)
    submit()
    expect(api.updateContract).toHaveBeenCalledTimes(1)
    reload.click()
    expect(window.confirm).toHaveBeenCalledOnce()
    expect(input('contract-no').value).toBe('USER-EDIT')
    expect(api.getContract).toHaveBeenCalledTimes(1)
  })

  it('blocks editing without a read timestamp', async () => {
    const api = await initialize(null)
    expect(saveButton()).toBeDisabled()
    submit()
    expect(api.updateContract).not.toHaveBeenCalled()
  })

  it('uses a new timestamp after loading the latest contract', async () => {
    const latest = '2026-10-11T01:05:00.654321+09:00'
    const api = await initialize(latest)
    expect(saveButton()).not.toBeDisabled()
    submit()
    await vi.waitFor(() => expect(api.updateContract).toHaveBeenCalledWith('21', expect.objectContaining({ expectedUpdatedAt: latest })))
  })

  it('keeps creation independent of the edit timestamp', async () => {
    const api = await initialize(null, 'create')
    input('insurer-id').value = '1'
    input('insurer-id').dispatchEvent(new Event('change', { bubbles: true }))
    await vi.waitFor(() => expect((input('product-offering-id') as unknown as HTMLSelectElement).options.length).toBe(2))
    for (const [id, value] of Object.entries({
      'product-offering-id': '1', 'agent-id': '1', 'organization-id': '4', 'contract-no': 'NEW-21',
      'contract-date': '2026-07-10', 'contract-status': 'ACTIVE', 'payment-cycle-code': 'MONTHLY',
      'premium-per-cycle-amount': '100000', 'first-premium-amount': '100000',
      'monthly-equivalent-first-premium': '100000', 'payment-term-months': '120',
    })) input(id).value = value
    submit()
    await vi.waitFor(() => expect(api.createContract).toHaveBeenCalledOnce())
    expect(api.createContract.mock.calls[0][0]).not.toHaveProperty('expectedUpdatedAt')
    expect(api.updateContract).not.toHaveBeenCalled()
  })
})
