import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Link, MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '../../lib/api/client'
import { SchedulePreviewForm } from './SchedulePreviewForm'
import { generatePreview } from './api'
import type { PreviewContext } from './api'

const access = vi.hoisted(() => ({ allowed: true }))
vi.mock('../auth/useAuth', () => ({ usePermission: () => access.allowed }))
vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), generatePreview: vi.fn() }))
const context: PreviewContext = {
  expectedSourceVersion: 'source-1',
  policy: { commissionPolicy: { policyVersionId: 70 }, versionNo: 3, sourceRefs: ['REG-03'],
    selection: { resolution: { status: 'SELECTED', declaredFeeRegimeCode: 'FOUR_YEAR_2027' }, ruleset: { maxInstallments: 48 } } },
}
function Location() {
  const location = useLocation()
  return <><output data-testid="location">{location.pathname}{location.search}</output><Link to="/other">다른 화면</Link></>
}
function mount() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  const view = (value = context, id = '600') => <QueryClientProvider client={client}><MemoryRouter initialEntries={['/contracts/600?tab=schedules&month=2026-07']}>
    <SchedulePreviewForm context={value} contractId={id} stage="GA_TO_FC" /><Location />
  </MemoryRouter></QueryClientProvider>
  const result = render(view())
  return { client, update: (value = context, id = '600') => result.rerender(view(value, id)) }
}
const enterReason = (value = '예정 생성 검증') => fireEvent.change(screen.getByLabelText('생성 사유'), { target: { value } })
const submit = () => fireEvent.submit(screen.getByLabelText('생성 사유').closest('form')!)
beforeEach(() => { access.allowed = true; vi.mocked(generatePreview).mockReset() })
afterEach(() => vi.restoreAllMocks())

it('pending submit is synchronous-locked and completion preserves contract return context', async () => {
  let finish: ((value: number) => void) | undefined
  vi.mocked(generatePreview).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  mount(); enterReason(); submit(); submit()
  await waitFor(() => expect(generatePreview).toHaveBeenCalledTimes(1))
  await act(async () => { finish!(11) })
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/schedules/11'))
  const url = new URL(screen.getByTestId('location').textContent!, 'http://test')
  expect(url.searchParams.get('returnTo')).toBe('/contracts/600?tab=schedules&month=2026-07')
})

it('uncertain response reuses the original body/key even when source policy context refreshes', async () => {
  vi.mocked(generatePreview).mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(11)
  const { update } = mount(); enterReason(); submit()
  await screen.findByRole('button', { name: '생성 결과 다시 확인' })
  update({ ...context, expectedSourceVersion: 'source-2' })
  expect(screen.getByLabelText('생성 사유')).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '생성 결과 다시 확인' }))
  await waitFor(() => expect(generatePreview).toHaveBeenCalledTimes(2))
  expect(vi.mocked(generatePreview).mock.calls[1]).toEqual(vi.mocked(generatePreview).mock.calls[0])
})

it('definitive stale-source rejection preserves reason and uses a new key after refreshed input', async () => {
  vi.mocked(generatePreview).mockRejectedValueOnce(new ApiError({ code: 'FGC-IMPL-SCHEDULE_SOURCE_CHANGED', message: '다시 조회하세요.' }, 'source-conflict', 409)).mockResolvedValueOnce(12)
  const { update } = mount(); enterReason(); submit()
  await screen.findByRole('button', { name: '최신 입력 다시 조회' })
  expect(screen.getByLabelText('생성 사유')).toHaveValue('예정 생성 검증')
  update({ ...context, expectedSourceVersion: 'source-2' }); submit()
  await waitFor(() => expect(generatePreview).toHaveBeenCalledTimes(2))
  const [first, second] = vi.mocked(generatePreview).mock.calls
  expect(second[2].expectedSourceVersion).toBe('source-2')
  expect(second[3]).not.toBe(first[3])
})

it.each(['route', 'target'])('late success cannot navigate after %s changes', async mode => {
  let finish: ((value: number) => void) | undefined
  vi.mocked(generatePreview).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const { update, client } = mount(); enterReason(); submit()
  client.setQueryData(['schedules', 'receipt-test'], { activeYn: true })
  client.setQueryData(['contracts', 'receipt-test'], { scheduleHeaderId: 10 })
  await waitFor(() => expect(finish).toBeDefined())
  if (mode === 'route') fireEvent.click(screen.getByRole('link', { name: '다른 화면' }))
  else { update(context, '601'); expect(screen.getByLabelText('생성 사유')).toHaveValue('') }
  await act(async () => { finish!(11) })
  expect(screen.getByTestId('location')).not.toHaveTextContent('/schedules/11')
  expect(client.getQueryState(['schedules', 'receipt-test'])?.isInvalidated).toBe(true)
  expect(client.getQueryState(['contracts', 'receipt-test'])?.isInvalidated).toBe(true)
})

it('unknown regime stays visible as REVIEW_REQUIRED and does not allow generation', () => {
  const { update } = mount()
  update({ policy: { selection: { resolution: { status: 'REVIEW_REQUIRED', reasonCode: 'UNKNOWN_REGIME' } } } })
  expect(screen.getByRole('heading', { name: '적용 체계·정책 검토 필요' })).toBeVisible()
  enterReason(); submit()
  expect(generatePreview).not.toHaveBeenCalled()
})

it('oversized reason and missing permission cannot submit', () => {
  mount(); enterReason('가'.repeat(41)); submit()
  expect(generatePreview).not.toHaveBeenCalled()
})
it('read-only roles have no generation controls', () => {
  access.allowed = false; mount()
  expect(screen.queryByLabelText('생성 사유')).not.toBeInTheDocument()
  expect(generatePreview).not.toHaveBeenCalled()
})

it('keeps unsaved state after failure and clears it only after real server success', async () => {
  vi.mocked(generatePreview).mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(11)
  mount(); enterReason()
  expect(Object.keys(useUnsavedWorkStore.getState().owners)).toHaveLength(1)
  submit()
  await screen.findByRole('button', { name: '생성 결과 다시 확인' })
  expect(Object.keys(useUnsavedWorkStore.getState().owners)).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: '생성 결과 다시 확인' }))
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/schedules/11'))
  expect(Object.keys(useUnsavedWorkStore.getState().owners)).toHaveLength(0)
})

it('7년 정책의 장기유지 구간을 표시하고 선택 체계로 생성한다', async () => {
  vi.mocked(generatePreview).mockResolvedValue(84)
  const { update } = mount()
  update({ ...context, policy: { ...context.policy, commissionPolicy: { policyVersionId: 70, rules: [{ feeComponentType: 'LONG_TERM_MAINTENANCE' }] }, selection: {
    resolution: { status: 'SELECTED', declaredFeeRegimeCode: 'SEVEN_YEAR_2029' },
    ruleset: { maxInstallments: 84, longTermMaintenanceFrom: 67, longTermMaintenanceTo: 84 },
  } } })
  expect(screen.getByText(/정책 정의 67~84회차/)).toBeVisible()
  enterReason(); fireEvent.click(screen.getByRole('button', { name: '7년 예정 생성' }))
  await waitFor(() => expect(generatePreview).toHaveBeenCalledTimes(1))
  expect(vi.mocked(generatePreview).mock.calls[0][2].regime).toBe('SEVEN_YEAR_2029')
})
it('장기유지 미설정은 미준비로 안내하고 승인된 일반 예정 생성은 허용한다', () => {
  const { update } = mount()
  update({ ...context, policy: { ...context.policy, selection: {
    resolution: { status: 'SELECTED', declaredFeeRegimeCode: 'SEVEN_YEAR_2029' }, ruleset: { maxInstallments: 84 },
  } } })
  expect(screen.getByText(/장기유지 계산 미준비/)).toBeVisible()
  enterReason()
  expect(screen.getByRole('button', { name: '7년 예정 생성' })).toBeEnabled()
})

it('7년 응답 유실 뒤 체계가 바뀌어도 원래 체계와 정책으로 결과를 확인한다', async () => {
  vi.mocked(generatePreview).mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(84)
  const { update } = mount()
  update({ ...context, policy: { ...context.policy, selection: {
    resolution: { status: 'SELECTED', declaredFeeRegimeCode: 'SEVEN_YEAR_2029' }, ruleset: { maxInstallments: 84 },
  } } })
  enterReason(); submit()
  await screen.findByRole('button', { name: '생성 결과 다시 확인' })
  update(context)
  expect(screen.getByRole('heading', { name: '7년 예정 스케줄' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '생성 결과 다시 확인' }))
  await waitFor(() => expect(generatePreview).toHaveBeenCalledTimes(2))
  expect(vi.mocked(generatePreview).mock.calls[1]).toEqual(vi.mocked(generatePreview).mock.calls[0])
})

it('도움말은 실제 서버 성공 이후 완료를 표시하고 안내 종료 후에만 이동한다', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{ x: 10, y: 10, width: 100, height: 40 }] as unknown as DOMRectList)
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
  let finish: ((id: number) => void) | undefined
  vi.mocked(generatePreview).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  mount()
  fireEvent.click(screen.getByRole('button', { name: '도움말' }))
  await screen.findByRole('region', { name: '분급 예정 생성 도움말' })
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  enterReason()
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeDisabled())
  expect(generatePreview).not.toHaveBeenCalled()
  submit()
  await waitFor(() => expect(generatePreview).toHaveBeenCalledTimes(1))
  expect(Object.keys(useUnsavedWorkStore.getState().owners)).toHaveLength(1)
  await act(async () => { finish!(11) })
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  expect(Object.keys(useUnsavedWorkStore.getState().owners)).toHaveLength(0)
  expect(screen.getByTestId('location')).toHaveTextContent('/contracts/600')
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  await screen.findByText('서버 저장 확인 · 가이드 완료')
  fireEvent.click(screen.getByRole('button', { name: '안내 마치기' }))
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/schedules/11'))
})
