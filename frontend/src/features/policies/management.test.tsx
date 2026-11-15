import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PolicyDraftEditor } from './PolicyDraftEditor'
import { policyCodeLabel, policyParameterLabel, policyParameterValue, policySeedText } from './labels'
import { PolicySources } from './PolicyManagement'
import { emptyDraft, emptyRegime, permittedActions, PolicyPreviewContext, refLabel, safeSourceUrl } from './management'
import type { DraftDocument } from './management'
import { validateDraft } from './draftValidation'

describe('policy UI boundaries', () => {
  it('does not infer write permissions when allowedActions are absent or conflict with status/role', () => {
    expect(permittedActions({ status: 'DRAFT' }, {}, 'SYSTEM_ADMIN')).toEqual([])
    expect(permittedActions({ status: 'ACTIVE' }, { allowedActions: ['EDIT', 'NEW_VERSION'] }, 'SETTLEMENT')).toEqual([
      'NEW_VERSION',
    ])
    expect(permittedActions({ status: 'REVIEW' }, { allowedActions: ['APPROVE', 'REJECT'] }, 'COMPLIANCE')).toEqual([])
  })
  it('new policies have no assumed rates, sources, or long-term start installments', () => {
    expect(emptyDraft().commissionRules).toEqual([])
    expect(emptyDraft().sourceDetails).toEqual([])
    expect(emptyRegime('SEVEN_YEAR_COMMISSION').longTermMaintenanceFrom).toBe('')
  })
  it('근거 코드는 저장값을 두고 문서 경로와 승계 기록만 읽기 쉬운 이름으로 보인다', () => {
    expect(refLabel('docs/05_인터페이스정의서_v2_0.md#9-5')).toBe('')
    expect(refLabel('docs/05_인터페이스정의서_v2_0.md')).toBe('')
    expect(refLabel('docs/FGC_가상_GA_운영정책서_v1_0.md#제46조')).toBe('회사 운영정책 제46조')
    // 'policy:45'의 45는 내부 번호라 그대로 보이지 않고, 목록에서 찾으면 정책 코드와 판을 붙인다.
    expect(refLabel('policy:45')).toBe('다음 정책 버전')
    expect(refLabel('policy:45', [{ policyVersionId: 45, versionNo: 2 }])).toBe('다음 정책 버전 v2')
    expect(refLabel('policy:45', [{ policyVersionId: 46, versionNo: 3 }])).toBe('다음 정책 버전')
    // 규정 코드는 이름으로, 이름이 없는 출처 코드와 팀 결정 번호는 표시하지 않는다.
    expect(refLabel('REG-08')).toBe('초년도 1,200%룰')
    expect(refLabel('REG-99')).toBe('관련 규정')
    expect(refLabel('SRC-022')).toBe('')
    expect(refLabel('SRC-025 Q11')).toBe('')
    expect(refLabel('D-01 시연 가정')).toBe('')
    expect(refLabel('FGC 운영정책서 제48조의3')).toBe('회사 운영정책 제48조의3')
    expect(refLabel('가상 GA 운영정책서 v1.0 제19~20조')).toBe('회사 운영정책 제19~20조')
    expect(refLabel('원수사 판매수수료율표 2026')).toBe('보험회사 판매수수료율표 2026')
  })
  it('정책 파라미터의 저장 키와 코드값을 화면 문구로 바꾼다', () => {
    expect(policyParameterLabel('ARBITRAGE_EFFECTIVE_CONTRACT_DATE')).toBe('차익거래 적용 시작일')
    expect(policyParameterLabel('NEW_KEY_FROM_SERVER')).toBe('설정 항목')
    expect(policyParameterValue('MONTHLY_EQUIVALENT_FIRST_PREMIUM_X12')).toBe('월납환산 초회보험료 × 12')
    expect(policyParameterValue(false)).toBe('아니오')
    expect(policyParameterValue(36)).toBe('36')
    expect(policyParameterValue('2026-03-01')).toBe('2026-03-01')
    expect(policyParameterValue('NEW_VALUE_FROM_SERVER')).toBe('확인 필요')
    // 상속 속성이 라벨로 나오면 React 자식이 객체가 되어 화면이 깨진다.
    expect(policyParameterValue('__proto__')).toBe('확인 필요')
    expect(policyParameterValue('constructor')).toBe('확인 필요')
    expect(policyParameterLabel('constructor')).toBe('설정 항목')
    expect(policyCodeLabel('__proto__')).toBe('확인 필요')
    expect(policyCodeLabel('NEWCOMER_NON_CONTRACT')).toBe('신인 지원 계약 미귀속')
  })

  it('시드가 남긴 승인 근거와 출처 문구의 개발 용어를 화면에서만 바꾼다', () => {
    expect(policySeedText('시드 승인 (GOLDEN 프로파일 #332)')).toBe('내부 승인')
    expect(policySeedText('데모 승인 — #475, 운영 정책 승인을 대체하지 않음')).toBe('내부 승인')
    expect(policySeedText('PROJECT_ASSUMPTION — 2026년 말 최종 FAQ 확정 시 재검증 필요')).toBe('자체 기준, 2026년 말 최종 FAQ 확정 시 재검증')
    expect(policySeedText('PROJECT_ASSUMPTION — 합성값, #332 시연 커버리지 보강')).toBe('자체 기준 — 합성값 시연 커버리지 보강')
    expect(policySeedText('REG-10 시행일 보정 (코드리뷰 PR #18 반영)')).toBe('준법경영비 공제 시행일 보정')
    expect(policySeedText('보험회사 예상 해약환급률표 2026 (합성값·#332 보강)')).toBe('보험회사 예상 해약환급률표 2026 (합성값 보강)')
    // 사용자가 입력한 승인 근거는 그대로 보인다.
    expect(policySeedText('의결서-HTTP-001')).toBe('의결서-HTTP-001')
    expect(policySeedText('데모 승인서 2026-001')).toBe('데모 승인서 2026-001')
    expect(policySeedText('위원회 의결서, #123')).toBe('위원회 의결서, #123')
    expect(policySeedText(null)).toBe('-')
    expect(refLabel('운영정책서 제47조: 장기유지 61~84차월은 PROJECT_ASSUMPTION(시드값)')).toBe(
      '장기유지 61~84차월, 5년 이상 유지 요건을 회차로 옮긴 자체 기준',
    )
    expect(refLabel('운영정책서 제46조: 지급 1~48차월(최초 지급회차는 PROJECT_ASSUMPTION)')).toBe('')
  })

  it('does not allow unsafe source links', () => {
    expect(safeSourceUrl('javascript:alert(1)')).toBeNull()
    expect(safeSourceUrl('https://user:password@example.com')).toBeNull()
    expect(safeSourceUrl('/relative')).toBeNull()
    expect(safeSourceUrl('https://example.com/source')).toBe('https://example.com/source')
  })
  it('identifies the exact field when a four-year limit or source URL is invalid', () => {
    const draft = {
      ...emptyDraft(),
      feeRegimeRules: [{ ...emptyRegime('FOUR_YEAR_COMMISSION'), maxInstallments: 49 }],
      sourceDetails: [{ ref: 'SRC-01', title: '자료', explanation: '', url: 'javascript:alert(1)' }],
    }
    expect(validateDraft(draft)['feeRegimeRules.0.maxInstallments']).toBeTruthy()
    expect(validateDraft(draft)['sourceDetails.0.url']).toBeTruthy()
  })
  it.each([
    ['FOUR_YEAR_COMMISSION', 48],
    ['SEVEN_YEAR_COMMISSION', 84],
  ] as const)('%s requires an integer installment limit within 1~%s', (policyType, max) => {
    const draft = regimeDraft(policyType)
    const rule = draft.feeRegimeRules[0]
    const errorPath = 'feeRegimeRules.0.maxInstallments'
    for (const value of [12.5, 0, max + 1, Number.NaN, Number.POSITIVE_INFINITY]) {
      rule.maxInstallments = value
      expect(validateDraft(draft)[errorPath]).toBe(`1~${max}회차 사이의 정수로 입력하세요.`)
    }
    for (const value of [1, max]) {
      rule.maxInstallments = value
      expect(validateDraft(draft)[errorPath]).toBeUndefined()
    }
    rule.maxInstallments = ''
    expect(validateDraft(draft)[errorPath]).toBe('필수 항목을 입력하세요.')
  })
  it('acquisition cost rate needs a positive number and the source of the value', () => {
    const draft = regimeDraft('FOUR_YEAR_COMMISSION')
    expect(validateDraft(draft)).toEqual({})
    draft.parameters = [{ key: 'ACQUISITION_COST_RATE_PCT', value: 0, description: '' }]
    expect(validateDraft(draft)['parameters.ACQUISITION_COST_RATE_PCT']).toBe('0보다 큰 기준율을 입력하세요.')
    expect(validateDraft(draft)['parameters.ACQUISITION_COST_SOURCE']).toBe('값의 출처를 입력하세요.')
    draft.parameters = [{ key: 'ACQUISITION_COST_RATE_PCT', value: 3000, description: '가상 GA 정책' }]
    expect(validateDraft(draft)).toEqual({})
  })
  it('installment editor saves the acquisition cost basis with its source and never fills in a value', { timeout: 20_000 }, async () => {
    const { user, save } = mountRegimeEditor(regimeDraft('FOUR_YEAR_COMMISSION'))
    await user.click(screen.getByRole('button', { name: /계약체결비용 기준/ }))
    const rate = screen.getByLabelText('월납환산 초회보험료 대비 기준율(%)')
    expect(rate).toHaveValue(null)
    await user.type(rate, '3000')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    expect(await screen.findByText('값의 출처를 입력하세요.')).toBeInTheDocument()
    expect(save).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText('값의 출처'), '가상 GA 정책(PROJECT_ASSUMPTION)')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(save).toHaveBeenCalled())
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        parameters: [
          expect.objectContaining({ key: 'ACQUISITION_COST_BASIS', value: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM' }),
          { key: 'ACQUISITION_COST_RATE_PCT', value: 3000, description: '가상 GA 정책(PROJECT_ASSUMPTION)' },
        ],
      }),
      1,
    )
  })
  it('installment editor removes the acquisition cost parameters when both inputs are cleared', { timeout: 20_000 }, async () => {
    const draft = regimeDraft('SEVEN_YEAR_COMMISSION')
    draft.parameters = [
      { key: 'ACQUISITION_COST_BASIS', value: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM', description: '기준금액' },
      { key: 'ACQUISITION_COST_RATE_PCT', value: 3000, description: '가상' },
    ]
    const { user, save } = mountRegimeEditor(draft)
    await user.click(screen.getByRole('button', { name: /계약체결비용 기준/ }))
    await user.clear(screen.getByLabelText('월납환산 초회보험료 대비 기준율(%)'))
    await user.clear(screen.getByLabelText('값의 출처'))
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith(expect.objectContaining({ parameters: [] }), 1))
  })
  it('production editor allows draft saving', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyDraftEditor onClose={vi.fn()} onSaved={vi.fn()} />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('button', { name: '초안 저장' })).toBeEnabled()
    expect(screen.getByText('초안으로 저장한 후 검토를 요청할 수 있습니다.')).toBeInTheDocument()
  })
  it.each([
    [1, 48],
    [1, ''],
    ['', 48],
  ] as const)('rejects four-year long-term values %s / %s', (from, to) => {
    const draft = regimeDraft('FOUR_YEAR_COMMISSION')
    draft.feeRegimeRules[0].longTermMaintenanceFrom = from
    draft.feeRegimeRules[0].longTermMaintenanceTo = to
    expect(validateDraft(draft)['feeRegimeRules.0.longTermMaintenance']).toBeTruthy()
  })
  it('keeps seven-year long-term ranges editable', () => {
    const draft = regimeDraft('SEVEN_YEAR_COMMISSION')
    draft.feeRegimeRules[0].longTermMaintenanceFrom = 61
    draft.feeRegimeRules[0].longTermMaintenanceTo = 84
    expect(validateDraft(draft)).toEqual({})
  })
  it('lets an existing invalid four-year draft clear disabled long-term values before saving', async () => {
    const draft = regimeDraft('FOUR_YEAR_COMMISSION')
    draft.feeRegimeRules[0].longTermMaintenanceFrom = 1
    draft.feeRegimeRules[0].longTermMaintenanceTo = 48
    const { user, save } = mountRegimeEditor(draft)
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    const reset = await screen.findByRole('button', { name: '장기유지 값 초기화' })
    await waitFor(() => expect(reset).toHaveFocus())
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByLabelText('장기유지 시작 회차')).toBeDisabled()
    expect(screen.getByLabelText('장기유지 시작 회차')).toHaveValue(1)
    await user.click(reset)
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        feeRegimeRules: [expect.objectContaining({ longTermMaintenanceFrom: '', longTermMaintenanceTo: '' })],
      }),
      1,
    )
  })
  it('saves a cleared regulation choice as an empty list', async () => {
    const { user, save } = mountRegimeEditor(regimeDraft('SEVEN_YEAR_COMMISSION'))
    await user.click(screen.getByRole('button', { name: /분급 규칙/ }))
    const choice = screen.getByRole('checkbox', { name: '선지급수수료' })
    await user.click(choice)
    expect(choice).toBeChecked()
    await user.click(choice)
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        feeRegimeRules: [expect.objectContaining({ sourceRefs: [] })],
      }),
      1,
    )
  })
  it('saves regulations picked by name as codes and keeps a stored source reference', async () => {
    const draft = regimeDraft('SEVEN_YEAR_COMMISSION')
    draft.feeRegimeRules[0].sourceRefs = ['SRC-022']
    const { user, save } = mountRegimeEditor(draft)
    await user.click(screen.getByRole('button', { name: /분급 규칙/ }))
    await user.click(screen.getByRole('checkbox', { name: '선지급수수료' }))
    expect(screen.queryByText(/REG-|SRC-/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        feeRegimeRules: [expect.objectContaining({ sourceRefs: ['SRC-022', 'REG-02'] })],
      }),
      1,
    )
  })
  it('shows the reference notice and provided status tags without inferring missing statuses from REG codes', () => {
    render(
      <PolicySources
        sources={[{ ref: 'SRC-01', title: '근거', explanation: '', url: null, statusTags: ['현행규정', 'FAQ'] }]}
        refs={['SRC-01', 'REG-02']}
      />,
    )
    expect(screen.getByText(/법정 심의\/의결을 대체하지 않습니다/)).toBeVisible()
    expect(screen.getByText('현행규정')).toBeVisible()
    expect(screen.getByText('FAQ')).toBeVisible()
    expect(screen.getByText('상태 자료 없음')).toBeVisible()
  })
  it('lists committee metadata of the offerings the policy targets as reference information', () => {
    render(
      <PolicySources
        sources={[]}
        refs={[]}
        offerings={[
          {
            productOfferingId: 3,
            productName: '가상 종신',
            offeringVersion: 'V1',
            basicDocumentVersion: 'BD-1',
            salesStartDate: '2026-01-01',
            salesEndDate: null,
            committeeApprovalDate: '2025-12-15',
            committeeDocumentRef: '의결서 2025-12',
            governanceStatus: 'SUSPENDED',
          },
          {
            productOfferingId: 4,
            productName: '가상 정기',
            offeringVersion: 'V2',
            basicDocumentVersion: 'BD-2',
            salesStartDate: '2026-02-01',
            salesEndDate: null,
            committeeApprovalDate: null,
            committeeDocumentRef: null,
            governanceStatus: null,
          },
        ]}
      />,
    )
    const section = screen.getByRole('region', { name: '상품위원회 정보' })
    expect(section).toHaveTextContent('가상 종신, V1')
    expect(section).toHaveTextContent('승인문서 의결서 2025-12')
    expect(section).toHaveTextContent('판매 상태 판매보류')
    expect(section).toHaveTextContent('승인일 미등록')
  })
  it('focuses an invalid field and preserves input, then asks before discarding an edited draft', async () => {
    const save = vi.fn(),
      close = vi.fn()
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyPreviewContext.Provider value={{ save, action: vi.fn() }}>
          <PolicyDraftEditor onClose={close} onSaved={vi.fn()} />
        </PolicyPreviewContext.Provider>
      </QueryClientProvider>,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/정책명/), '보존할 정책명')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    await waitFor(() => expect(screen.getByLabelText(/정책코드/)).toHaveFocus())
    expect(screen.getByLabelText(/정책명/)).toHaveValue('보존할 정책명')
    expect(save).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(close).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '계속 편집' }))
    expect(screen.getByLabelText(/정책명/)).toHaveValue('보존할 정책명')
  })
})

function regimeDraft(policyType: string): DraftDocument {
  return {
    ...emptyDraft(),
    policyCode: 'UI-REGRESSION',
    policyName: '분급 규칙 검증',
    policyType,
    sourceClass: 'PROJECT_ASSUMPTION',
    effectiveFrom: '2029-01-01',
    feeRegimeRules: [
      {
        ...emptyRegime(policyType),
        salesStartDateFrom: '2029-01-01',
        maxInstallments: policyType === 'FOUR_YEAR_COMMISSION' ? 48 : 84,
      },
    ],
  }
}
function mountRegimeEditor(initial: DraftDocument) {
  const save = vi.fn().mockResolvedValue(1)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <PolicyPreviewContext.Provider value={{ save, action: vi.fn() }}>
        <PolicyDraftEditor initial={initial} policyId={1} onClose={vi.fn()} onSaved={vi.fn()} />
      </PolicyPreviewContext.Provider>
    </QueryClientProvider>,
  )
  return { user: userEvent.setup(), save }
}
