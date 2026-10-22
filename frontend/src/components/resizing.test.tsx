import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { DataTable } from './DataTable'
import { ResizableSplit } from './ResizableSplit'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

it('잘못된 저장값을 무시하고 컬럼 구성이 바뀌면 이전 너비를 적용하지 않는다', () => {
  localStorage.setItem('fgc:layout:v1:table:example:id', '[300]')
  localStorage.setItem('fgc:layout:v1:table:example:name', '[-1]')
  const { rerender } = render(
    <DataTable
      resizeKey="example"
      caption="테스트"
      rows={[]}
      rowKey={() => ''}
      columns={[{ key: 'id', label: '번호', render: () => '' }]}
    />,
  )
  expect(screen.getByRole('table')).toHaveStyle({ width: 'max(100%, 300px)' })
  rerender(
    <DataTable
      resizeKey="example"
      caption="테스트"
      rows={[]}
      rowKey={() => ''}
      columns={[{ key: 'name', label: '이름', render: () => '' }]}
    />,
  )
  expect(screen.getByRole('table').style.width).toBe('')
})

it('저장이 차단돼도 카드 너비 조절과 초기화를 사용할 수 있다', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('denied')
  })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('quota')
  })
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new Error('denied')
  })
  render(<ResizableSplit storageKey="blocked" label="카드 너비" primary={<p>목록</p>} secondary={<p>상세</p>} />)
  const handle = screen.getByRole('separator', { name: '카드 너비' })
  fireEvent.keyDown(handle, { key: 'ArrowRight' })
  expect(handle).toHaveAttribute('aria-valuenow', '62')
  fireEvent.keyDown(handle, { key: 'Home' })
  expect(handle).toHaveAttribute('aria-valuenow', '60')
})

it('표별 저장 너비를 분리하고 리사이즈 비활성 표에는 저장값을 적용하지 않는다', () => {
  localStorage.setItem('fgc:layout:v1:table:first:id', '[320]')
  const columns = [{ key: 'id', label: '번호', render: () => '' }]
  render(
    <>
      <DataTable resizeKey="first" caption="첫 표" rows={[]} rowKey={() => ''} columns={columns} />
      <DataTable resizeKey="second" caption="둘째 표" rows={[]} rowKey={() => ''} columns={columns} />
      <DataTable resizeKey="first" caption="고정 표" rows={[]} rowKey={() => ''} columns={columns} resizable={false} />
    </>,
  )
  expect(screen.getByRole('table', { name: '첫 표' })).toHaveStyle({ width: 'max(100%, 320px)' })
  expect(screen.getByRole('table', { name: '둘째 표' }).style.width).toBe('')
  expect(screen.getByRole('table', { name: '고정 표' }).style.width).toBe('')
})
