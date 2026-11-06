import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ScreenHelp } from './ScreenHelp'
import { snapshotScreen } from './screen-snapshot'
import type { ScreenSnapshot } from './screen-snapshot'

vi.mock('./screen-snapshot', () => ({ snapshotScreen: vi.fn() }))
beforeEach(() => { vi.useFakeTimers(); vi.mocked(snapshotScreen).mockReset() })
afterEach(() => { cleanup(); vi.useRealTimers() })

it('opens before capture and ignores cancelled results after closing and reopening', async () => {
  const requests: { signal: AbortSignal; resolve: (snapshot: ScreenSnapshot) => void }[] = []
  vi.mocked(snapshotScreen).mockImplementation((_root, _areas, signal) => new Promise(resolve => {
    requests.push({ signal, resolve })
  }))
  render(<main id="main-content"><ScreenHelp screenId="CONT-W01" /></main>)
  fireEvent.click(screen.getByRole('button', { name: '도움말' }))
  expect(screen.getByRole('dialog', { name: '보험계약 목록 도움말' })).toBeVisible()
  expect(screen.getByRole('status')).toBeVisible()
  expect(snapshotScreen).not.toHaveBeenCalled()
  await act(async () => { await vi.advanceTimersByTimeAsync(50) })
  expect(requests).toHaveLength(1)

  fireEvent.click(screen.getByRole('button', { name: '창 닫기' }))
  expect(requests[0].signal.aborted).toBe(true)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '도움말' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(50) })
  expect(requests).toHaveLength(2)
  const snapshot: ScreenSnapshot = { content: document.createElement('div'), width: 100, height: 100, areas: [], scroll: [] }
  await act(async () => { requests[0].resolve(snapshot) })
  expect(screen.getByRole('status')).toBeVisible()
  expect(screen.queryByRole('img')).not.toBeInTheDocument()
  await act(async () => { requests[1].resolve(snapshot) })
  expect(screen.getByRole('img', { name: '보험계약 목록 화면 미리보기' })).toBeVisible()
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('keeps the modal closable and explains a preview failure', async () => {
  vi.mocked(snapshotScreen).mockRejectedValue(new Error('capture failed'))
  render(<main id="main-content"><ScreenHelp screenId="CONT-W01" /></main>)
  fireEvent.click(screen.getByRole('button', { name: '도움말' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(50) })
  expect(screen.getByRole('alert')).toHaveTextContent('화면 미리보기를 불러오지 못했습니다.')
  fireEvent.click(screen.getByRole('button', { name: '창 닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})
