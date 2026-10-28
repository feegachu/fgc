import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PolicyDraftEditor } from './PolicyDraftEditor'
import { emptyDraft, emptyRegime, permittedActions, PolicyPreviewContext, safeSourceUrl } from './management'
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
  it('production editor never claims to save or calls write APIs', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PolicyDraftEditor onClose={vi.fn()} onSaved={vi.fn()} />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('button', { name: '초안 저장' })).toBeDisabled()
    expect(screen.getByText('UI 구현 단계 · 저장 API 연결 예정')).toBeInTheDocument()
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
    expect(await screen.findByLabelText(/정책코드/)).toHaveFocus()
    expect(screen.getByLabelText(/정책명/)).toHaveValue('보존할 정책명')
    expect(save).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(close).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '계속 편집' }))
    expect(screen.getByLabelText(/정책명/)).toHaveValue('보존할 정책명')
  })
})
