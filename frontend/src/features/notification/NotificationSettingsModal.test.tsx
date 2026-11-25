import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiClient } from '../../lib/api/client'
import { useToastStore } from '../../stores/toasts'
import type { NotificationSetting } from './api'
import { NotificationSettingsModal } from './NotificationSettingsModal'

const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'notification-setting-test' })
let saved: unknown[]

function open(setting: NotificationSetting, { agent = false, fail = false } = {}) {
  saved = []
  const onClose = vi.fn()
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    if (url !== '/api/v1/notification-settings/me') throw new Error(`unexpected ${url}`)
    if (options?.method !== 'PUT') return envelope(setting)
    saved.push(options.body)
    if (fail) throw new ApiError({ code: 'FGC-COMMON-002', message: '입력값을 확인하세요.' }, 'request-1', 400)
    return envelope(setting)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <NotificationSettingsModal agent={agent} onClose={onClose} />
    </QueryClientProvider>,
  )
  return { client, onClose, actions: userEvent.setup() }
}

afterEach(() => {
  vi.restoreAllMocks()
  useToastStore.setState({ messages: [] })
})

describe('알림 설정 창', () => {
  it.each([false, true])('설정이 갱신되면 편집 여부(%s)에 맞는 번호를 저장한다', async (edited) => {
    const setting = { mobilePhone: '01000000102', smsConfigured: true }
    const { actions, client } = open(setting)
    const input = await screen.findByLabelText('휴대폰 번호')
    if (edited) {
      await actions.clear(input)
      await actions.type(input, '01000000103')
    }
    client.setQueryData(['notification-setting'], { ...setting, mobilePhone: '01000000104' })
    const expected = edited ? '01000000103' : '01000000104'
    await waitFor(() => expect(input).toHaveValue(expected))
    await actions.click(screen.getByRole('button', { name: '저장' }))
    expect(saved).toEqual([{ mobilePhone: expected, consent: true }])
  })

  it('등록된 번호와 동의 상태를 보여 주고, 받는 알림을 역할에 맞게 한 줄로 알려 준다', async () => {
    open({ mobilePhone: '01000000102', consentedAt: '2026-11-17T10:00:00+09:00', smsConfigured: true })
    expect(await screen.findByLabelText('휴대폰 번호')).toHaveValue('01000000102')
    expect(screen.getByRole('checkbox', { name: '마감 알림 문자를 받습니다' })).toBeChecked()
    expect(screen.getByText('월 통합검증을 확정할 차례가 되면 문자로 알려 드립니다.')).toBeInTheDocument()
    expect(screen.queryByText('지금은 문자 발송이 꺼져 있어 알림 이력만 남습니다.')).not.toBeInTheDocument()
  })

  it('설계사에게는 명세 확정 알림이라고 알려 주고, 문자 발송이 꺼져 있으면 그렇게 적는다', async () => {
    open({ smsConfigured: false }, { agent: true })
    expect(await screen.findByLabelText('휴대폰 번호')).toHaveValue('')
    expect(screen.getByRole('checkbox', { name: '마감 알림 문자를 받습니다' })).not.toBeChecked()
    expect(screen.getByText('수수료 명세가 확정되면 문자로 알려 드립니다.')).toBeInTheDocument()
    expect(screen.getByText('지금은 문자 발송이 꺼져 있어 알림 이력만 남습니다.')).toBeInTheDocument()
  })

  it('번호를 적고 동의하면 저장하고 창을 닫는다', async () => {
    const { onClose, actions } = open({ smsConfigured: true })
    await actions.type(await screen.findByLabelText('휴대폰 번호'), '010-0000-0102')
    await actions.click(screen.getByRole('checkbox', { name: '마감 알림 문자를 받습니다' }))
    await actions.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(saved).toEqual([{ mobilePhone: '01000000102', consent: true }])
    expect(useToastStore.getState().messages.at(-1)?.message).toBe('알림 설정을 저장했습니다.')
  })

  it('붙여 넣은 번호에 줄바꿈 없는 공백이 섞여도 검증에 쓴 숫자만 보낸다', async () => {
    const { onClose, actions } = open({ smsConfigured: true })
    // 화면 검증은 이 공백을 빼고 보지만 서버는 빼지 못해 거절한다. 검증한 값을 그대로 보내야 한다.
    await actions.type(await screen.findByLabelText('휴대폰 번호'), '010 0000 0102')
    await actions.click(screen.getByRole('checkbox', { name: '마감 알림 문자를 받습니다' }))
    await actions.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(saved).toEqual([{ mobilePhone: '01000000102', consent: true }])
  })

  it('다시 열면 캐시에 남은 옛 값이 아니라 방금 읽은 값으로 폼을 채운다', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    let setting: NotificationSetting = {
      mobilePhone: '01000000102',
      consentedAt: '2026-11-17T10:00:00+09:00',
      smsConfigured: true,
    }
    vi.spyOn(apiClient, 'request').mockImplementation(async () => envelope(setting))
    const modal = (
      <QueryClientProvider client={client}>
        <NotificationSettingsModal agent={false} onClose={vi.fn()} />
      </QueryClientProvider>
    )
    const first = render(modal)
    expect(await screen.findByLabelText('휴대폰 번호')).toHaveValue('01000000102')
    first.unmount()
    // 창을 닫은 사이 다른 기기에서 번호를 바꿨다. 옛 값으로 폼을 채우면 그대로 저장할 때 새 번호를 덮어쓴다.
    setting = { ...setting, mobilePhone: '01000000199' }
    await waitFor(() => expect(client.getQueryData(['notification-setting'])).toBeUndefined())

    render(modal)
    expect(screen.queryByLabelText('휴대폰 번호')).not.toBeInTheDocument()
    expect(await screen.findByLabelText('휴대폰 번호')).toHaveValue('01000000199')
  })

  it('휴대폰 번호가 아니면 서버에 보내지 않고 고칠 곳을 알려 준다', async () => {
    const { onClose, actions } = open({ smsConfigured: true })
    await actions.type(await screen.findByLabelText('휴대폰 번호'), '02-123-4567')
    await actions.click(screen.getByRole('checkbox', { name: '마감 알림 문자를 받습니다' }))
    await actions.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('휴대폰 번호를 확인하세요. 예: 010-0000-0000')).toBeInTheDocument()
    expect(saved).toEqual([])
    expect(onClose).not.toHaveBeenCalled()
  })

  it('동의를 풀고 저장하면 번호 없이 수신 해제만 보낸다', async () => {
    const { onClose, actions } = open({
      mobilePhone: '01000000102',
      consentedAt: '2026-11-17T10:00:00+09:00',
      smsConfigured: true,
    })
    await actions.click(await screen.findByRole('checkbox', { name: '마감 알림 문자를 받습니다' }))
    await actions.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(saved).toEqual([{ consent: false }])
  })

  it('저장이 실패하면 창을 닫지 않고 서버가 준 사유를 보여 준다', async () => {
    const { onClose, actions } = open({ smsConfigured: true }, { fail: true })
    await actions.type(await screen.findByLabelText('휴대폰 번호'), '01000000102')
    await actions.click(screen.getByRole('checkbox', { name: '마감 알림 문자를 받습니다' }))
    await actions.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('입력값을 확인하세요.')
    expect(onClose).not.toHaveBeenCalled()
  })
})
