import { SaveGuide } from './save-guide'
import { StrictMode, useState } from 'react'
import { createPortal } from 'react-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useSearchParams } from 'react-router'
import { ScreenHelp } from './ScreenHelp'
import { FilterBar } from '../../../components/FilterBar'
import definitions from './screen-help.json'
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{ x: 10, y: 10, width: 100, height: 40 }] as unknown as DOMRectList)
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })
it('strict-mode help only explains, preserves input and restores focus on Escape', async () => {
  const user = userEvent.setup()
  const write = vi.fn()
  render(<StrictMode><MemoryRouter><main id="main-content"><ScreenHelp screenId="CONT-W01" />
    <section data-guide-target="contract.filters"><input aria-label="계약 검색" defaultValue="keep" /></section>
    <button data-guide-target="contract.new" onClick={write}>신규 등록</button>
  </main></MemoryRouter></StrictMode>)
  await user.click(screen.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '설명 확인 · 다음' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '설명 확인 · 다음' }))
  expect(write).not.toHaveBeenCalled()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('region', { name: '보험계약 목록 도움말' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '도움말' })).toHaveFocus()
  expect(screen.getByLabelText('계약 검색')).toHaveValue('keep')
})
it('a duplicate live target pauses rather than highlighting an arbitrary element', async () => {
  render(<MemoryRouter><main id="main-content"><ScreenHelp screenId="CONT-W01" /><section data-guide-target="contract.filters"/><section data-guide-target="contract.filters"/></main></MemoryRouter>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  expect(await screen.findByText(/DUPLICATE_TARGET/)).toBeVisible()
})
it('FilterBar forwards the target to its actual form', () => {
  render(<FilterBar data-guide-target="test.filters" onSubmit={() => {}}>필터</FilterBar>)
  expect(screen.getByRole('form', { name: '조회 조건' })).toHaveAttribute('data-guide-target', 'test.filters')
})

it.each(definitions)('registers documented targets for $screenId $variant without crashing', (definition) => {
  render(<ScreenHelp screenId={definition.screenId} variant={definition.variant} />)
  expect(screen.getByRole('button', { name: '도움말' })).toBeVisible()
})
it('Escape closes only the help inside a dialog and keeps the draft', async () => {
  const user = userEvent.setup()
  render(<div role="dialog" aria-label="정책 편집"><ScreenHelp screenId="POL-W01" variant="editor" />
    <form data-guide-target="policy.editor"><input aria-label="정책명" defaultValue="draft" /><button data-guide-target="policy.save">저장</button></form>
  </div>)
  await user.click(screen.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '설명 확인 · 다음' })).toBeEnabled())
  await user.keyboard('{Escape}')
  expect(screen.getByRole('dialog')).toBeVisible()
  expect(screen.getByLabelText('정책명')).toHaveValue('draft')
  expect(screen.getByRole('button', { name: '도움말' })).toHaveFocus()
})

it('registered tab URL changes preserve the active help session', async () => {
  function Contract() {
    const [params, setParams] = useSearchParams()
    return <main id="main-content"><ScreenHelp screenId="CONT-W02" />
      <button id="contract-tab-basic" aria-selected={params.get('tab') !== 'schedules'} onClick={() => setParams({ tab: 'basic' })}>기본정보</button>
      <button id="contract-tab-schedules" aria-selected={params.get('tab') === 'schedules'} onClick={() => setParams({ tab: 'schedules' })}>스케줄</button>
      <section data-guide-target="contract.statusHistory" />
      <section data-guide-target="contract.schedules" hidden={params.get('tab') !== 'schedules'} />
    </main>
  }
  render(<MemoryRouter><Contract /></MemoryRouter>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '설명 확인 · 다음' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '설명 확인 · 다음' }))
  await waitFor(() => expect(screen.getByText('화면 사용법 · 2/2')).toBeVisible())
  expect(screen.getByRole('button', { name: '스케줄' })).toHaveAttribute('aria-selected', 'true')
})

it('withdraws completion after a changed input and offers a fresh guide', async () => {
  const guide = new SaveGuide()
  guide.update('run:1', true)
  render(<main id="main-content"><ScreenHelp screenId="VRUN-W01" saveGuide={guide} outcome="execution" />
    <section data-guide-target="validation.create" />
    <button data-guide-target="validation.createSubmit">실행 생성</button>
  </main>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '다음' }))
  act(() => { guide.begin(); void guide.saved(1) })
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(await screen.findByText('서버 실행 확인 · 가이드 완료')).toBeVisible()
  act(() => guide.update('run:2', true))
  expect(await screen.findByText('실행 결과 변경 · 다시 확인')).toBeVisible()
  expect(screen.queryByText('서버 실행 확인 · 가이드 완료')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '다시 시작' }))
  await waitFor(() => expect(screen.getByText('실행 가이드 · 1/2')).toBeVisible())
})

it('replaying completed help preserves values and never clicks the registered write button', async () => {
  const user = userEvent.setup()
  const write = vi.fn()
  render(<main id="main-content"><ScreenHelp screenId="CONT-W01" />
    <section data-guide-target="contract.filters"><input aria-label="검색 값" defaultValue="keep" /></section>
    <button data-guide-target="contract.new" onClick={write}>신규 등록</button>
  </main>)
  await user.click(screen.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '설명 확인 · 다음' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '설명 확인 · 다음' }))
  await user.click(screen.getByRole('button', { name: '설명 확인 · 다음' }))
  await user.click(await screen.findByRole('button', { name: '다시 보기' }))
  expect(await screen.findByText('화면 사용법 · 1/2')).toBeVisible()
  expect(screen.getByLabelText('검색 값')).toHaveValue('keep')
  expect(write).not.toHaveBeenCalled()
})
it('suspends the background card when a dialog is portalled outside main', async () => {
  function Page() {
    const [modal, setModal] = useState(false)
    return <><main id="main-content"><ScreenHelp screenId="CONT-W01" />
      <section data-guide-target="contract.filters" />
      <button onClick={() => setModal(true)}>모달 열기</button>
    </main>{modal && createPortal(<div role="dialog"><button onClick={() => setModal(false)}>모달 닫기</button></div>, document.body)}</>
  }
  const user = userEvent.setup()
  render(<Page />)
  await user.click(screen.getByRole('button', { name: '도움말' }))
  expect(await screen.findByRole('region', { name: '보험계약 목록 도움말' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '모달 열기' }))
  await waitFor(() => expect(screen.queryByRole('region', { name: '보험계약 목록 도움말' })).not.toBeInTheDocument())
  await user.click(screen.getByRole('button', { name: '모달 닫기' }))
  expect(await screen.findByRole('region', { name: '보험계약 목록 도움말' })).toBeVisible()
})
it('withdraws a highlight when a target becomes hidden through style changes', async () => {
  render(<main id="main-content"><ScreenHelp screenId="CONT-W01" /><section data-guide-target="contract.filters" /></main>)
  await userEvent.click(screen.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '설명 확인 · 다음' })).toBeEnabled())
  act(() => { document.querySelector<HTMLElement>('[data-guide-target="contract.filters"]')!.style.display = 'none' })
  expect(await screen.findByText(/TARGET_CHANGED/)).toBeVisible()
  expect(screen.queryByRole('button', { name: '설명 확인 · 다음' })).not.toBeInTheDocument()
})
it('does not run an unregistered screen variant', () => {
  render(<ScreenHelp screenId="CONT-W01" variant="unknown" />)
  expect(screen.getByRole('button', { name: '도움말' })).toBeDisabled()
})
