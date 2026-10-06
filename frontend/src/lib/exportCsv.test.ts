import { afterEach, expect, it, vi } from 'vitest'
import { csvFilename, exportCsv } from './exportCsv'
import { apiClient } from './api/client'
import { subscribeApiErrors } from './api/errorNotifications'
import { ApiError } from './api/errors'

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
it('Content-Disposition 일반·UTF-8 파일명을 해석한다', () => {
  expect(csvFilename('attachment; filename="contracts.csv"')).toBe('contracts.csv')
  expect(csvFilename("attachment; filename=plain.csv; filename*=UTF-8''%EA%B3%84%EC%95%BD.csv")).toBe('계약.csv')
  expect(csvFilename("attachment; filename=plain.csv; filename*=UTF-8''%xx")).toBe('plain.csv')
  expect(csvFilename(null)).toBe('export.csv')
  expect(csvFilename('attachment; filename="../contracts.csv"')).toBe('.._contracts.csv')
})
it('서버 blob을 파일명 그대로 저장하고 URL을 해제한다', async () => {
  vi.useFakeTimers()
  const blob = new Blob(['id\n1'], { type: 'text/csv' })
  vi.spyOn(apiClient, 'requestBlob').mockResolvedValue({ blob, disposition: 'attachment; filename="server.csv"' })
  const create = vi.fn(() => 'blob:test')
  const revoke = vi.fn()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    expect(this.download).toBe('server.csv')
    expect(this.href).toBe('blob:test')
  })
  expect(await exportCsv('/api/v1/contracts/export')).toBe('server.csv')
  expect(create).toHaveBeenCalledWith(blob)
  expect(click).toHaveBeenCalledOnce()
  expect(document.querySelector('a')).toBeNull()
  vi.runAllTimers()
  expect(revoke).toHaveBeenCalledWith('blob:test')
  vi.useRealTimers()
})
it('오류 봉투는 공통 Toast 연결점에 전달한 뒤 호출자에게 반환한다', async () => {
  const error = new ApiError({ code: 'FGC-CONT-001', message: '다운로드 실패' }, 'csv-err', 400)
  vi.spyOn(apiClient, 'requestBlob').mockRejectedValue(error)
  const listener = vi.fn()
  const unsubscribe = subscribeApiErrors(listener)
  await expect(exportCsv('/api/v1/contracts/export')).rejects.toBe(error)
  expect(listener).toHaveBeenCalledWith('다운로드 실패 (FGC-CONT-001 · 요청 ID: csv-err)', error)
  unsubscribe()
})
it('CSV는 text/csv로 요청하고 JSON 실패를 ApiError로 반환한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('id\n1', { headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename=x.csv' } }))
  vi.stubGlobal('fetch', fetcher)
  expect((await apiClient.requestBlob('/api/v1/contracts/export')).disposition).toContain('x.csv')
  expect(new Headers(fetcher.mock.calls[0][1].headers).get('Accept')).toBe('text/csv')
  fetcher.mockResolvedValue(Response.json({ data: null, error: { code: 'FGC-CONT-001', message: '실패' }, requestId: 'x' }, { status: 400 }))
  await expect(apiClient.requestBlob('/api/v1/contracts/export')).rejects.toMatchObject({ code: 'FGC-CONT-001', requestId: 'x', status: 400 })
})
