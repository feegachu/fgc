import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SaveGuide } from './save-guide'
import { useRunGuide } from './useRunGuide'

describe('server execution completion', () => {
  it('requires a terminal success, clears success on errors and new selection, then recovers', () => {
    const guide = new SaveGuide()
    const { rerender } = renderHook(({ id, status, error }) => useRunGuide(guide, '2026-07:GA:1', true, id, status, error), {
      initialProps: { id: '41', status: 'CREATED', error: '' },
    })
    expect(guide.getSnapshot().savedId).toBeNull()
    rerender({ id: '41', status: 'RUNNING', error: '' })
    expect(guide.getSnapshot().pending).toBe(true)
    rerender({ id: '41', status: 'FAILED', error: '실행 실패' })
    expect(guide.getSnapshot().savedId).toBeNull()
    rerender({ id: '42', status: 'COMPLETED', error: '' })
    expect(guide.getSnapshot().savedId).toBe('42')
    rerender({ id: '42', status: 'COMPLETED', error: '결과 조회 실패' })
    expect(guide.getSnapshot().savedId).toBeNull()
    rerender({ id: '42', status: 'COMPLETED', error: '' })
    expect(guide.getSnapshot().savedId).toBe('42')
    rerender({ id: '43', status: 'CREATED', error: '' })
    expect(guide.getSnapshot().savedId).toBeNull()
    expect(guide.getSnapshot().pending).toBe(false)
  })
  it('does not reuse a completed result after input or scope changes', () => {
    const guide = new SaveGuide()
    const { rerender } = renderHook(({ context, id, valid }) => useRunGuide(guide, context, valid, id, 'COMPLETED'), {
      initialProps: { context: 'July:GA:1', id: '44', valid: true },
    })
    expect(guide.getSnapshot().savedId).toBe('44')
    rerender({ context: 'July:GA:2', id: '', valid: true })
    expect(guide.getSnapshot().savedId).toBeNull()
    act(() => guide.deactivate(false))
  })
})
