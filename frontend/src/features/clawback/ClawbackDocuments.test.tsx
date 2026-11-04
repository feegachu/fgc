import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { ClawbackDocuments } from './ClawbackDocuments'
import { downloadNotice } from './api'
import type { CandidateDetail, NoticeDocument } from './api'

const detail: CandidateDetail = {
  candidate: {
    candidateId: 42,
    contractNo: 'CLW-001',
    agentName: '김설계',
    clawbackCaseId: 7,
    calculationStatus: 'CALCULATED',
    sourceVersion: 'source-v2',
  },
  latestCalculation: {
    clawbackCaseId: 7,
    candidateId: 42,
    calculationVersion: 2,
    sourceVersion: 'source-v2',
    amount: 0,
    calculatedAt: '2026-07-31T00:00:00Z',
  },
}
const edition = (version: number): NoticeDocument => ({
  documentNo: 'CLN-7',
  version,
  calculationVersion: 2,
  sha256: 'a'.repeat(64),
  createdAt: '2026-07-31T00:00:00Z',
  createdBy: 5,
  downloadPath: `/api/v1/documents/CLN-7.pdf?version=${version}`,
})
const history = (content: NoticeDocument[] = []) => ({ content, page: 1, totalElements: content.length, totalPages: 1 })
function setup(
  responder: (path: string, options?: RequestOptions) => unknown = () => history(),
  roleCode = 'SETTLEMENT',
  value = detail,
) {
  const request = vi.spyOn(apiClient, 'request').mockImplementation(
    async (path, options) =>
      ({
        data: await responder(path, options),
        error: null,
        requestId: 'notice-test',
      }) as never,
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const onRefresh = vi.fn().mockResolvedValue(undefined)
  function view(next = value, role = roleCode, refreshing = false) {
    return (
      <QueryClientProvider client={client}>
        <ClawbackDocuments
          key={`${next.candidate?.candidateId}:${next.candidate?.sourceVersion}`}
          detail={next}
          roleCode={role}
          refreshing={refreshing}
          onRefresh={onRefresh}
        />
      </QueryClientProvider>
    )
  }
  return { request, client, onRefresh, view, ...render(view()) }
}
const failure = (status: number, code = 'FGC-COMMON-500') =>
  new ApiError({ code, message: '서버 검증 메시지', field: 'calculationVersion' }, 'notice-error', status)
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it.each(['SETTLEMENT', 'GA_ADMIN', 'SYSTEM_ADMIN'])(
  '%s can issue the displayed calculation and sees real zero',
  async (role) => {
    const { request } = setup(undefined, role)
    expect(await screen.findByText('발행된 PDF가 없습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'PDF 생성' })).toBeEnabled()
    expect(screen.getByText('0원')).toBeInTheDocument()
    expect(screen.getAllByText('source-v2')).toHaveLength(2)
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0][0]).toBe('/api/v1/clawbacks/7/notice-documents?page=1&size=20')
  },
)

it('COMPLIANCE sees editions and downloads but no issue action', async () => {
  setup(() => history([edition(1)]), 'COMPLIANCE')
  const table = await screen.findByRole('table', { name: '환수 PDF 발행 이력' })
  expect(within(table).getByText('CLN-7')).toBeInTheDocument()
  expect(within(table).getByText('2026-07-31 09:00')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '발행판 v1 다운로드' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: /PDF 생성|재발행/ })).not.toBeInTheDocument()
})

it.each(['AGENT', 'OTHER'])('hides the entire PDF feature from %s without querying it', (role) => {
  const { request } = setup(undefined, role)
  expect(screen.queryByRole('region')).not.toBeInTheDocument()
  expect(request).not.toHaveBeenCalled()
})

it('uncalculated candidates show missing data and refresh without a fictitious case request', async () => {
  const { request, onRefresh } = setup(undefined, 'SETTLEMENT', { candidate: { candidateId: 42, sourceVersion: 'v1' } })
  expect(screen.getByText('미계산')).toBeInTheDocument()
  expect(screen.queryByText('0원')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'PDF 생성' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '다시 조회' }))
  expect(onRefresh).toHaveBeenCalledOnce()
  expect(request).not.toHaveBeenCalled()
})

it('locks generation while history is loading and never turns a failed history into zero results', async () => {
  let reject!: (reason: Error) => void
  setup(
    () =>
      new Promise((_, no) => {
        reject = no
      }),
  )
  expect(screen.getByText('발행 이력 불러오는 중…')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'PDF 생성' })).toBeDisabled()
  await act(async () => reject(failure(500)))
  expect(await screen.findByRole('alert')).toHaveTextContent('notice-error')
  expect(screen.queryByText('발행된 PDF가 없습니다.')).not.toBeInTheDocument()
})

it('prevents duplicate clicks, reuses an uncertain attempt after remount, then gives reissue a new key', async () => {
  const docs: NoticeDocument[] = []
  let reject!: (reason: Error) => void
  let first = true
  const { request, rerender, view } = setup((_path, options) => {
    if (options?.method !== 'POST') return history(docs)
    if (first) {
      first = false
      return new Promise((_, no) => {
        reject = no
      })
    }
    const doc = edition(docs.length + 1)
    docs.unshift(doc)
    return doc
  })
  const user = userEvent.setup()
  const button = await screen.findByRole('button', { name: 'PDF 생성' })
  await waitFor(() => expect(button).toBeEnabled())
  await user.dblClick(button)
  const writes = () => request.mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(writes()).toHaveLength(1)
  expect(writes()[0][1]?.body).toEqual({ calculationVersion: 2, expectedSourceVersion: 'source-v2' })
  expect(screen.getByRole('button', { name: 'PDF 생성 중…' })).toBeDisabled()
  await act(async () => reject(new TypeError('응답 유실')))
  rerender(view({ ...detail, candidate: { ...detail.candidate, candidateId: 43 } }))
  rerender(view())
  await waitFor(() => expect(screen.getByRole('button', { name: '발행 재시도' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '발행 재시도' }))
  await screen.findByText(/PDF 발행 완료/)
  await waitFor(() => expect(screen.getByRole('button', { name: '재발행' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '재발행' }))
  await waitFor(() => expect(writes()).toHaveLength(3))
  expect(writes()[0][1]?.idempotencyKey).toBeTruthy()
  expect(writes()[1][1]?.idempotencyKey).toBe(writes()[0][1]?.idempotencyKey)
  expect(writes()[2][1]?.idempotencyKey).not.toBe(writes()[1][1]?.idempotencyKey)
})

it('blocks stale source snapshots while keeping old editions downloadable', async () => {
  setup(() => history([edition(1)]), 'SETTLEMENT', {
    ...detail,
    candidate: { ...detail.candidate, sourceVersion: 'new-source', calculationStatus: 'REVIEW_REQUIRED' },
  })
  await screen.findByRole('table', { name: '환수 PDF 발행 이력' })
  expect(screen.getByText(/현재 원천과 다른 계산판/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '재발행' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '발행판 v1 다운로드' })).toBeEnabled()
})

it.each([401, 403, 409, 422, 500])('shows issuance HTTP %i without adding a successful edition', async (status) => {
  const { onRefresh } = setup((_path, options) => {
    if (options?.method === 'POST') throw failure(status, status === 409 ? 'FGC-IMPL-STATE_CHANGED' : `FAIL-${status}`)
    return history()
  })
  await screen.findByText('발행된 PDF가 없습니다.')
  await userEvent.click(screen.getByRole('button', { name: 'PDF 생성' }))
  await screen.findByText(
    status === 403
      ? 'PDF 발행 권한이 없습니다.'
      : status === 409
        ? '현재 계산판·원천과 발행 요청이 다릅니다. 다시 조회해 주세요.'
        : status === 422
          ? '발행에 필요한 계산·정책 근거를 확인해 주세요.'
          : 'PDF 발행에 실패했습니다. 응답 유실 시 같은 요청으로 재시도합니다.',
  )
  expect(screen.queryByText(/PDF 발행 완료/)).not.toBeInTheDocument()
  if (status === 409) {
    expect(screen.getByRole('button', { name: '발행 재시도' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '다시 조회' }))
    expect(onRefresh).toHaveBeenCalledOnce()
  }
  if (status === 403) expect(screen.queryByRole('button', { name: '발행 재시도' })).not.toBeInTheDocument()
  if (status === 422) expect(screen.getByText('확인 항목: 대상 계산판')).toBeInTheDocument()
})

it('downloads the exact historical edition and does not save JSON failures as a PDF', async () => {
  const blob = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
  const pdf = vi.spyOn(apiClient, 'requestPdf').mockResolvedValue({ blob, disposition: null })
  const create = vi.fn().mockReturnValue('blob:test')
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: vi.fn() }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  setup(() => history([edition(2), { ...edition(1), calculationVersion: 1 }]))
  await screen.findByRole('table')
  await userEvent.click(screen.getByRole('button', { name: '발행판 v1 다운로드' }))
  await waitFor(() => expect(click).toHaveBeenCalledOnce())
  expect(pdf).toHaveBeenCalledWith('/api/v1/documents/CLN-7.pdf?version=1')
  expect(create).toHaveBeenCalledWith(blob)
  pdf.mockRejectedValueOnce(failure(404, 'FGC-COMMON-004'))
  await userEvent.click(screen.getByRole('button', { name: '발행판 v2 다운로드' }))
  await screen.findByText('요청한 발행판의 파일이 없습니다. 발행 이력을 다시 조회해 주세요.')
  expect(click).toHaveBeenCalledOnce()
  expect(create).toHaveBeenCalledOnce()
  expect(screen.getByText(/실제 통지 완료/)).toBeInTheDocument()
})

it.each([undefined, 'https://example.com/file.pdf', '/api/v1/documents/CLN-7.pdf?version=2'])(
  'rejects an absent or mismatched download path: %s',
  async (path) => {
    const pdf = vi.spyOn(apiClient, 'requestPdf')
    await expect(downloadNotice({ ...edition(1), downloadPath: path })).rejects.toThrow('경로와 발행판')
    expect(pdf).not.toHaveBeenCalled()
  },
)

it('does not put a late issue response into a different candidate selection', async () => {
  let resolve!: (document: NoticeDocument) => void
  const { rerender, view } = setup((_path, options) =>
    options?.method === 'POST'
      ? new Promise((yes) => {
          resolve = yes
        })
      : history(),
  )
  await screen.findByText('발행된 PDF가 없습니다.')
  await userEvent.click(screen.getByRole('button', { name: 'PDF 생성' }))
  rerender(view({ candidate: { candidateId: 55, contractNo: 'NEXT', sourceVersion: 'next' } }))
  await act(async () => resolve(edition(1)))
  expect(screen.getByText(/NEXT/)).toBeInTheDocument()
  expect(screen.queryByText(/PDF 발행 완료/)).not.toBeInTheDocument()
})

it('keeps the attempt key when a 201 response is missing its document', async () => {
  let attempts = 0
  const { request } = setup((_path, options) => {
    if (options?.method !== 'POST') return history()
    return ++attempts === 1 ? null : edition(1)
  })
  await screen.findByText('발행된 PDF가 없습니다.')
  await userEvent.click(screen.getByRole('button', { name: 'PDF 생성' }))
  await screen.findByText(/PDF 발행 응답을 확인할 수 없습니다/)
  expect(screen.queryByText(/PDF 발행 완료/)).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '발행 재시도' }))
  await screen.findByText(/PDF 발행 완료/)
  const writes = request.mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(writes[1][1]?.idempotencyKey).toBe(writes[0][1]?.idempotencyKey)
})

it('retries REQUEST_IN_PROGRESS with the same key without treating it as a stale source', async () => {
  let attempts = 0
  const { request } = setup((_path, options) => {
    if (options?.method !== 'POST') return history()
    if (++attempts === 1) throw failure(409, 'FGC-IMPL-REQUEST_IN_PROGRESS')
    return edition(1)
  })
  await screen.findByText('발행된 PDF가 없습니다.')
  await userEvent.click(screen.getByRole('button', { name: 'PDF 생성' }))
  const retry = await screen.findByRole('button', { name: '발행 재시도' })
  expect(retry).toBeEnabled()
  await userEvent.click(retry)
  await screen.findByText(/PDF 발행 완료/)
  const writes = request.mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(writes[1][1]?.idempotencyKey).toBe(writes[0][1]?.idempotencyKey)
})

it('treats a missing history page as an error and allows retry', async () => {
  let reads = 0
  setup(() => (++reads === 1 ? null : history()))
  await screen.findByText('발행 이력 응답을 확인할 수 없습니다. 다시 조회해 주세요.')
  expect(screen.queryByText('발행된 PDF가 없습니다.')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '다시 조회' }))
  await screen.findByText('발행된 PDF가 없습니다.')
})

it('pages the issue history independently and blocks issue actions while detail refreshes', async () => {
  const { rerender, view, request } = setup((path) => ({
    content: [edition(path.includes('page=2') ? 1 : 21)],
    page: path.includes('page=2') ? 2 : 1,
    totalElements: 21,
    totalPages: 2,
  }))
  await screen.findByRole('button', { name: '발행판 v21 다운로드' })
  await userEvent.click(
    within(screen.getByRole('navigation', { name: 'PDF 발행 이력 페이지' })).getByRole('button', {
      name: /^2$/,
    }),
  )
  await screen.findByRole('button', { name: '발행판 v1 다운로드' })
  expect(request.mock.calls.at(-1)?.[0]).toContain('page=2&size=20')
  rerender(view(detail, 'SETTLEMENT', true))
  expect(screen.getByRole('button', { name: '재발행' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '발행판 v1 다운로드' })).toBeDisabled()
})
