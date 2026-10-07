import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { Button } from './Button'
import { Field } from './Field'
import { StatusBadge } from './StatusBadge'
import { KpiCard } from './KpiCard'
import { DataTable } from './DataTable'
import { Pagination } from './Pagination'
import { FilterBar } from './FilterBar'
import { useSearchParamsState } from '../hooks/useSearchParamsState'
import { Modal } from './Modal'
import { ToastRegion } from './Toast'
import { toast, useToastStore } from '../stores/toasts'
import { notifyApiError } from '../lib/api/errorNotifications'
import { ApiError } from '../lib/api/errors'
import { EvidenceLink } from './EvidenceLink'
import { MonthSelector } from './MonthSelector'

afterEach(() => {
  vi.useRealTimers()
  useToastStore.setState({ messages: [] })
  vi.restoreAllMocks()
})
it('Button loading과 Field 오류를 입력·라벨에 연결한다', () => {
  render(
    <>
      <Button loading>저장</Button>
      <Field label="사유" required error="사유를 입력하세요">
        <input />
      </Field>
    </>,
  )
  expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled()
  const input = screen.getByRole('textbox', { name: '사유' })
  expect(input).toBeRequired()
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(input).toHaveAccessibleDescription('사유를 입력하세요')
})
it('StatusBadge는 색상 의미와 서버 문구를 함께, KPI는 값·단위를 표시한다', () => {
  render(
    <>
      <StatusBadge tone="warning">주의</StatusBadge>
      <KpiCard label="후보 계약" value="1,234" unit="건" footer="이번 월" />
    </>,
  )
  expect(screen.getByText('주의')).toHaveClass('status-badge-warning')
  expect(screen.getByRole('region', { name: '후보 계약' })).toHaveTextContent('1,234건')
})
it('DataTable 정렬·행 선택·페이지 전체 선택·전체 보기/접기를 제공한다', async () => {
  const user = userEvent.setup()
  const sort = vi.fn()
  function Table() {
    const [selected, setSelected] = useState<string[]>([])
    return (
      <DataTable
        caption="계약 목록"
        rows={[
          { id: 'C1', reason: '검증 상세 근거' },
          { id: 'C2', reason: '다른 근거' },
        ]}
        rowKey={(row) => row.id}
        columns={[
          { key: 'id', label: '계약', sortable: true, render: (row) => row.id },
          { key: 'reason', label: '근거', expandable: true, render: (row) => row.reason },
        ]}
        sort={{ key: 'id', direction: 'asc' }}
        onSort={sort}
        selectedKeys={selected}
        onSelectionChange={setSelected}
      />
    )
  }
  render(<Table />)
  await user.click(screen.getByRole('button', { name: '계약' }))
  expect(sort).toHaveBeenCalledWith({ key: 'id', direction: 'desc' })
  expect(screen.getByRole('columnheader', { name: '계약' })).toHaveAttribute('aria-sort', 'ascending')
  await user.click(screen.getByLabelText('C1 선택'))
  expect(screen.getByLabelText('C1 선택')).toBeChecked()
  await user.click(screen.getByLabelText('현재 페이지 전체 선택'))
  expect(screen.getByLabelText('C2 선택')).toBeChecked()
  await user.click(screen.getAllByRole('button', { name: '전체 보기' })[0])
  expect(screen.getByRole('button', { name: '접기' })).toHaveAttribute('aria-expanded', 'true')
  await user.click(screen.getByRole('button', { name: '접기' }))
  expect(screen.getAllByRole('button', { name: '전체 보기' })).toHaveLength(2)
})
it('DataTable 빈 목록은 오류 대신 빈 상태 문구를 보여 준다', () => {
  render(
    <DataTable
      caption="빈 목록"
      rows={[]}
      rowKey={() => ''}
      columns={[{ key: 'id', label: '계약', render: () => '' }]}
    />,
  )
  expect(screen.getByRole('table', { name: '빈 목록' })).toHaveTextContent('조건에 맞는 자료가 없습니다.')
})
it('Pagination은 1-base 5페이지 그룹이며 끝에서 다음을 막는다', async () => {
  const change = vi.fn()
  const { rerender } = render(<Pagination page={6} totalPages={12} onPageChange={change} />)
  expect(screen.getByRole('button', { name: '6' })).toHaveAttribute('aria-current', 'page')
  expect(screen.queryByRole('button', { name: '5' })).toBeNull()
  await userEvent.click(screen.getByRole('button', { name: '이전' }))
  expect(change).toHaveBeenLastCalledWith(5)
  await userEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(change).toHaveBeenLastCalledWith(11)
  rerender(<Pagination page={12} totalPages={12} onPageChange={change} />)
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '마지막' })).toBeDisabled()
})
it('FilterBar와 URL 훅은 다른 조건·기준월을 보존하고 조회 시 page만 초기화한다', async () => {
  function Filters() {
    const { values, update, reset, params } = useSearchParamsState({ status: '' })
    return (
      <>
        <FilterBar onSubmit={() => update({ status: 'ACTIVE' })} onReset={reset}>
          <span>{values.status}</span>
        </FilterBar>
        <output>{params.toString()}</output>
      </>
    )
  }
  render(
    <MemoryRouter initialEntries={['/contracts?month=2026-07&status=OLD&page=4&insurerId=1']}>
      <Filters />
    </MemoryRouter>,
  )
  await userEvent.click(screen.getByRole('button', { name: '조회' }))
  expect(screen.getByRole('status')).toHaveTextContent('month=2026-07&status=ACTIVE&insurerId=1')
  await userEvent.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByRole('status')).toHaveTextContent('month=2026-07&insurerId=1')
})
describe('Modal', () => {
  it('포커스 순환·Esc·닫힌 뒤 원래 포커스 복원', async () => {
    const user = userEvent.setup()
    function Dialog() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button onClick={() => setOpen(true)}>열기</button>
          <Modal title="재검증" open={open} onClose={() => setOpen(false)}>
            <input aria-label="사유" />
            <button>실행</button>
          </Modal>
        </>
      )
    }
    render(<Dialog />)
    await user.click(screen.getByRole('button', { name: '열기' }))
    const close = screen.getByRole('button', { name: '모달 닫기' })
    expect(close).toHaveFocus()
    await user.tab({ shift: true })
    expect(screen.getByRole('button', { name: '실행' })).toHaveFocus()
    await user.tab()
    expect(close).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('button', { name: '열기' })).toHaveFocus()
  })
  it('배경 클릭은 옵션에 따라서만 닫는다', () => {
    const close = vi.fn()
    const { rerender } = render(
      <Modal title="검증" open onClose={close}>
        <p>내용</p>
      </Modal>,
    )
    fireEvent.click(screen.getByRole('dialog').parentElement!)
    expect(close).not.toHaveBeenCalled()
    rerender(
      <Modal title="검증" open closeOnBackdrop onClose={close}>
        <p>내용</p>
      </Modal>,
    )
    fireEvent.click(screen.getByRole('dialog').parentElement!)
    expect(close).toHaveBeenCalledOnce()
  })
})
it('Toast 일반 알림은 5초 후 닫고 오류·로딩은 수동으로 닫는다', () => {
  vi.useFakeTimers()
  render(<ToastRegion />)
  act(() => {
    toast('성공', 'success')
    toast('오류', 'error')
    toast('로딩', 'loading')
  })
  act(() => vi.advanceTimersByTime(4999))
  expect(screen.getByText('성공')).toBeInTheDocument()
  act(() => vi.advanceTimersByTime(1))
  expect(screen.queryByText('성공')).toBeNull()
  expect(screen.getByText('로딩')).toBeInTheDocument()
  fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: '알림 닫기' }))
  expect(screen.queryByText('오류')).toBeNull()
  act(() => notifyApiError(new ApiError({ code: 'FGC-COMMON-500', message: '실패' }, 'trace', 500)))
  expect(screen.getByRole('alert')).toHaveTextContent('실패 (FGC-COMMON-500 · 요청 ID: trace)')
})
it('EvidenceLink는 hover·focus로 열고 클릭 고정·Esc로 해제한다', async () => {
  const user = userEvent.setup()
  render(<EvidenceLink label="REG-08">제4-32조제11항</EvidenceLink>)
  const trigger = screen.getByRole('button', { name: 'REG-08' })
  await user.hover(trigger)
  expect(screen.getByRole('tooltip')).toHaveTextContent('제4-32조제11항')
  await user.click(trigger)
  await user.unhover(trigger)
  await new Promise((resolve) => setTimeout(resolve, 150))
  expect(screen.getByRole('tooltip')).toBeInTheDocument()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('tooltip')).toBeNull()
  trigger.blur()
  act(() => trigger.focus())
  expect(screen.getByRole('tooltip')).toBeInTheDocument()
})
it('MonthSelector는 선택 초안을 적용하기 전까지 유지하고 비활성 월을 건너뛴다', async () => {
  const user = userEvent.setup()
  const apply = vi.fn()
  render(<MonthSelector value="2026-01" disabledMonths={['2026-02']} onApply={apply} openTabCount={3} />)
  await user.click(screen.getByRole('button', { name: '기준 정산월 2026-01' }))
  expect(screen.getAllByRole('gridcell')).toHaveLength(12)
  expect(screen.getByRole('gridcell', { name: '2월' })).toBeDisabled()
  act(() => screen.getByRole('gridcell', { name: '1월' }).focus())
  await user.keyboard('{ArrowRight}{Enter}')
  expect(screen.getByRole('gridcell', { name: '3월' })).toHaveFocus()
  expect(apply).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '적용' }))
  expect(apply).toHaveBeenCalledWith('2026-03')
  expect(screen.queryByRole('dialog')).toBeNull()
})
it('MonthSelector는 연도 이동 후 선택을 요구하고 취소·Esc로 초안을 버린다', async () => {
  const apply = vi.fn()
  render(<MonthSelector value="2026-07" onApply={apply} />)
  await userEvent.click(screen.getByRole('button', { name: '기준 정산월 2026-07' }))
  await userEvent.click(screen.getByRole('button', { name: '다음 연도' }))
  expect(screen.getByText('2027년')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '적용' })).toBeDisabled()
  await userEvent.click(screen.getByRole('gridcell', { name: '4월' }))
  await userEvent.keyboard('{Escape}')
  expect(apply).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: '기준 정산월 2026-07' })).toHaveFocus()
})
it('MonthSelector는 저장된 월이 비활성이면 가능한 월로 포커스를 옮기고 연도 하한을 지킨다', async () => {
  render(<MonthSelector value="0001-01" disabledMonths={['0001-01']} onApply={() => {}} />)
  await userEvent.click(screen.getByRole('button', { name: '기준 정산월 0001-01' }))
  expect(screen.getByRole('gridcell', { name: '2월' })).toHaveFocus()
  expect(screen.getByRole('button', { name: '이전 연도' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '적용' })).toBeDisabled()
})

it('compact pagination keeps a sliding five-page window and moves one page at a time', async () => {
  const change = vi.fn()
  render(<Pagination compact page={6} totalPages={12} onPageChange={change} />)
  for (const page of ['4', '5', '6', '7', '8']) expect(screen.getByRole('button', { name: page })).toBeVisible()
  expect(screen.queryByRole('button', { name: '처음' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '이전 페이지' }))
  expect(change).toHaveBeenLastCalledWith(5)
  await userEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
  expect(change).toHaveBeenLastCalledWith(7)
})
