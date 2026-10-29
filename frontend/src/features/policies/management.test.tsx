import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PolicyDraftEditor } from './PolicyDraftEditor'
import { PolicySources } from './PolicyManagement'
import { emptyDraft, emptyRegime, permittedActions, PolicyPreviewContext, safeSourceUrl } from './management'
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
  it('production editor never claims to save or calls write APIs', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyDraftEditor onClose={vi.fn()} onSaved={vi.fn()} />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('button', { name: '초안 저장' })).toBeDisabled()
    expect(screen.getByText('UI 구현 단계 · 저장 API 연결 예정')).toBeInTheDocument()
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
  it('allows deleting a comma one character at a time and saves cleared references as an empty list', async () => {
    const { user, save } = mountRegimeEditor(regimeDraft('SEVEN_YEAR_COMMISSION'))
    await user.click(screen.getByRole('button', { name: /분급 룰셋/ }))
    const input = screen.getByLabelText('근거 코드')
    await user.type(input, 'SRC-A, B')
    await user.keyboard('{Backspace}{Backspace}{Backspace}')
    expect(input).toHaveValue('SRC-A')
    await user.clear(input)
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        feeRegimeRules: [expect.objectContaining({ sourceRefs: [] })],
      }),
      1,
    )
  })
  it('trims, removes empty entries and deduplicates reference codes only when saving', async () => {
    const { user, save } = mountRegimeEditor(regimeDraft('SEVEN_YEAR_COMMISSION'))
    await user.click(screen.getByRole('button', { name: /분급 룰셋/ }))
    const input = screen.getByLabelText('근거 코드')
    await user.type(input, ' SRC-A, REG-B, ,SRC-A, ')
    expect(input).toHaveValue(' SRC-A, REG-B, ,SRC-A, ')
    await user.click(screen.getByRole('button', { name: '초안 저장' }))
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        feeRegimeRules: [expect.objectContaining({ sourceRefs: ['SRC-A', 'REG-B'] })],
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
    expect(screen.getByText(/법정 심의·의결을 대체하지 않습니다/)).toBeVisible()
    expect(screen.getByText('현행규정')).toBeVisible()
    expect(screen.getByText('FAQ')).toBeVisible()
    expect(screen.getByText('상태 자료 없음')).toBeVisible()
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
    policyName: '분급 룰셋 검증',
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
