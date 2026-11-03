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
  return { update: (value = context, id = '600') => result.rerender(view(value, id)) }
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
  const { update } = mount(); enterReason(); submit()
  await waitFor(() => expect(finish).toBeDefined())
  if (mode === 'route') fireEvent.click(screen.getByRole('link', { name: '다른 화면' }))
  else { update(context, '601'); expect(screen.getByLabelText('생성 사유')).toHaveValue('') }
  await act(async () => { finish!(11) })
  expect(screen.getByTestId('location')).not.toHaveTextContent('/schedules/11')
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
