import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { AssistantDemoMount, PicoAssistant } from './PicoAssistant'
import type { ShellUser } from '../../app/shell/Sidebar'
const user: ShellUser = {
  loginId: 'demo',
  userName: '담당자',
  roleCode: 'SETTLEMENT',
  canProcess: true,
  canViewAuditLog: false,
}
afterEach(() => vi.unstubAllEnvs())
const open = () => userEvent.click(screen.getByRole('button', { name: '피코 업무 비서 열기' }))

it('requires explicit demo opt-in and hides for AGENT, unknown roles and logout', () => {
  vi.stubEnv('VITE_ASSISTANT_DEMO', '')
  const view = render(<AssistantDemoMount user={user} />)
  expect(screen.queryByRole('button', { name: '피코 업무 비서 열기' })).not.toBeInTheDocument()
  view.rerender(<AssistantDemoMount user={user} enabled />)
  expect(screen.getByRole('button', { name: '피코 업무 비서 열기' })).toBeInTheDocument()
  for (const roleCode of ['AGENT', 'UNKNOWN']) {
    view.rerender(<AssistantDemoMount user={{ ...user, roleCode }} enabled />)
    expect(screen.queryByRole('button', { name: '피코 업무 비서 열기' })).not.toBeInTheDocument()
  }
  view.rerender(<AssistantDemoMount user={user} enabled loggingOut />)
  expect(screen.queryByRole('button', { name: '피코 업무 비서 열기' })).not.toBeInTheDocument()
})
it('opens closed-by-default chat, preserves the four removals and restores keyboard focus', async () => {
  render(<PicoAssistant canProcess />)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await open()
  expect(screen.getByRole('textbox', { name: '피코에게 질문' })).toHaveFocus()
  expect(screen.queryByText('현재 보고 있는 업무')).not.toBeInTheDocument()
  expect(screen.queryByText('PICO WORKFLOW ASSISTANT')).not.toBeInTheDocument()
  expect(document.querySelector('.launcher-dot,.pico-launcher-dot,.live-dot')).not.toBeInTheDocument()
  await userEvent.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '피코 업무 비서 열기' })).toHaveFocus()
})
it('renders a selected fixture and read-only plan without a financial request', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('No financial calls'))
  try {
    render(<PicoAssistant canProcess />)
    await open()
    await userEvent.click(screen.getByRole('button', { name: /지급액 입력했는데 다음은/ }))
    expect(screen.getByText(/저장된 대상 확인 후/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '안내 순서 보기' }))
    const panel = screen.getByRole('tabpanel')
    expect(within(panel).getByRole('heading', { name: '지급 사전검증·확정' })).toBeInTheDocument()
    expect(within(panel).getAllByRole('listitem')).toHaveLength(5)
    expect(within(panel).getByText(/화면 이동·강조와 실제 처리 결과 연결은 다음 단계/)).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  } finally {
    fetch.mockRestore()
  }
})
it('discloses free text as unconnected instead of faking an AI answer or running keywords', async () => {
  render(<PicoAssistant canProcess />)
  await open()
  await userEvent.type(screen.getByRole('textbox', { name: '피코에게 질문' }), '지급액을 999999원으로 확정해줘')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(screen.getByText(/자유 질문 해석은 아직 연결하지 않았어요/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '안내 순서 보기' })).not.toBeInTheDocument()
  expect(screen.getByRole('textbox', { name: '피코에게 질문' })).toHaveValue('')
})
it('shows synthetic amount, basis and missing/unsupported/stale states through explicit selection', async () => {
  render(<PicoAssistant canProcess />)
  await open()
  await userEvent.click(screen.getByRole('button', { name: /이번 달 수수료는 얼마야/ }))
  expect(screen.getByText('100,000원')).toBeInTheDocument()
  expect(screen.getByText(/합성 정액 100000원 정책/)).toBeInTheDocument()
  await userEvent.click(screen.getByText('시연 질문 바꾸기'))
  for (const [scenario, label] of [
    ['PAY-03', '미귀속 10000원'],
    ['PAY-05', '자료 부족'],
    ['PAY-11', '지원 준비 중'],
    ['PAY-12', '다시 확인 필요'],
  ]) {
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '시연 상황' }), scenario)
    await userEvent.click(screen.getByRole('button', { name: '선택한 질문 시연' }))
    expect(screen.getAllByText(new RegExp(label)).length).toBeGreaterThan(0)
  }
})
it('retains conversation on close/open and clears with new conversation', async () => {
  render(<PicoAssistant canProcess />)
  await open()
  await userEvent.click(screen.getByRole('button', { name: /지급액 입력했는데 다음은/ }))
  await userEvent.click(screen.getByRole('button', { name: '비서 닫기' }))
  await open()
  expect(screen.getByText(/저장된 대상 확인 후/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '새 대화' }))
  expect(screen.queryByText(/저장된 대상 확인 후/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /지급액 입력했는데 다음은/ })).toBeInTheDocument()
})
it('drops memory conversation on account change or logout and never persists it', async () => {
  const setItem = vi.spyOn(Storage.prototype, 'setItem')
  try {
    const view = render(<AssistantDemoMount user={user} enabled />)
    await open()
    await userEvent.click(screen.getByRole('button', { name: /지급액 입력했는데 다음은/ }))
    view.rerender(<AssistantDemoMount user={{ ...user, loginId: 'other' }} enabled />)
    await open()
    expect(screen.queryByText(/저장된 대상 확인 후/)).not.toBeInTheDocument()
    view.rerender(<AssistantDemoMount user={{ ...user, loginId: 'other' }} enabled loggingOut />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(setItem).not.toHaveBeenCalled()
  } finally {
    setItem.mockRestore()
  }
})
it('blocks processing guide for read-only role and gives keyboard-operable tabs', async () => {
  render(<PicoAssistant canProcess={false} />)
  await open()
  await userEvent.click(screen.getByRole('button', { name: /지급액 입력했는데 다음은/ }))
  expect(screen.getByText(/조회 역할은 지급을 확정할 수 없습니다/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '안내 순서 보기' })).not.toBeInTheDocument()
  const chat = screen.getByRole('tab', { name: '대화' })
  chat.focus()
  await userEvent.keyboard('{ArrowRight}')
  expect(screen.getByRole('tab', { name: '업무 안내' })).toHaveFocus()
  expect(screen.getByRole('tab', { name: '업무 안내' })).toHaveAttribute('aria-selected', 'true')
})
it('marks past fixture proposals stale instead of reusing them in a newer scenario', async () => {
  render(<PicoAssistant canProcess />)
  await open()
  await userEvent.click(screen.getByRole('button', { name: /지급액 입력했는데 다음은/ }))
  await userEvent.click(screen.getByText('시연 질문 바꾸기'))
  await userEvent.selectOptions(screen.getByRole('combobox', { name: '시연 상황' }), 'PAY-07')
  await userEvent.click(screen.getByRole('button', { name: '선택한 질문 시연' }))
  expect(screen.getByRole('button', { name: '안내 순서 보기' })).toBeDisabled()
  expect(screen.getByText(/이전 시연 상황의 응답/)).toBeInTheDocument()
  expect(screen.getByText('-50,000원')).toBeInTheDocument()
})
