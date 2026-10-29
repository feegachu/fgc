import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SearchSelect } from './SearchSelect'
import type { SearchSource } from './SearchSelect'
type Option = { id: string; name: string }
function setup(load: SearchSource<Option>['load']) {
  function Form() {
    const [selected, setSelected] = useState<Option>()
    return (
      <>
        <label htmlFor="agent">설계사</label>
        <SearchSelect
          id="agent"
          value={selected?.id ?? ''}
          selected={selected}
          source={{ key: ['test'], load, id: (row) => row.id, label: (row) => row.name }}
          onSelect={(_, row) => setSelected(row)}
        />
        <button>다음 입력</button>
        <output>{selected?.id}</output>
      </>
    )
  }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <Form />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}
describe('CMN-C02 검색형 선택', () => {
  it('한국어 조합 중 Enter는 선택으로 처리하지 않는다', async () => {
    const user = setup(async (_, page) => ({ content: [{ id: '1', name: '김설계' }], page, totalPages: 1 }))
    const input = screen.getByLabelText('설계사')
    await user.click(input)
    await screen.findByRole('option', { name: '김설계' })
    await user.keyboard('{ArrowDown}')
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    expect(input).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    await user.keyboard('{Enter}')
    expect(input).toHaveValue('김설계')
  })
  it('첫 100건 밖인 600번째를 서버 검색하고 키보드로 선택한다', async () => {
    const load = vi.fn(async (keyword: string, page: number) => ({
      content: keyword === '600' ? [{ id: '600', name: '마지막 설계사' }] : [{ id: '1', name: '첫 설계사' }],
      page,
      totalPages: 1,
    }))
    const user = setup(load)
    await user.type(screen.getByLabelText('설계사'), '600')
    await screen.findByRole('option', { name: '마지막 설계사' })
    await user.keyboard('{ArrowDown}{Enter}')
    expect(screen.getByRole('status')).toHaveTextContent('600')
    expect(load.mock.calls.some(([keyword, page]) => keyword === '600' && page === 1)).toBe(true)
  })
  it('더 불러오기는 검색어와 다음 페이지를 서버로 전달한다', async () => {
    const load = vi.fn(async (keyword: string, page: number) => ({
      content: [{ id: String(page), name: `${keyword} ${page}번` }],
      page,
      totalPages: 2,
    }))
    const user = setup(load)
    await user.type(screen.getByLabelText('설계사'), '검색')
    await screen.findByRole('option', { name: '검색 1번' })
    await user.click(screen.getByRole('button', { name: '더 불러오기' }))
    await screen.findByRole('option', { name: '검색 2번' })
    expect(load.mock.calls.some(([keyword, page]) => keyword === '검색' && page === 2)).toBe(true)
    await user.click(screen.getByRole('option', { name: '검색 2번' }))
    expect(screen.getByRole('status')).toHaveTextContent('2')
  })
  it('0건 안내·Escape·Tab이 선택값을 만들거나 폼을 제출하지 않는다', async () => {
    const user = setup(async (_, page) => ({ content: [], page, totalPages: 0 }))
    await user.click(screen.getByLabelText('설계사'))
    await screen.findByText('조건에 맞는 자료가 없습니다.')
    await user.keyboard('{Escape}')
    expect(screen.getByLabelText('설계사')).toHaveAttribute('aria-expanded', 'false')
    await user.tab()
    expect(screen.getByRole('button', { name: '다음 입력' })).toHaveFocus()
  })
  it('늦게 끝난 이전 검색이 새 검색 결과를 덮어쓰지 않는다', async () => {
    let finish!: (page: { content: Option[]; page: number; totalPages: number }) => void
    const user = setup(async (keyword, page) =>
      keyword === 'old'
        ? new Promise((resolve) => {
            finish = resolve
          })
        : { content: keyword === 'new' ? [{ id: '2', name: '새 결과' }] : [], page, totalPages: 1 },
    )
    await user.type(screen.getByLabelText('설계사'), 'old')
    await waitFor(() => expect(finish).toBeDefined())
    await user.clear(screen.getByLabelText('설계사'))
    await user.type(screen.getByLabelText('설계사'), 'new')
    await screen.findByRole('option', { name: '새 결과' })
    finish({ content: [{ id: '1', name: '이전 결과' }], page: 1, totalPages: 1 })
    await waitFor(() => expect(screen.queryByRole('option', { name: '이전 결과' })).not.toBeInTheDocument())
  })
})
