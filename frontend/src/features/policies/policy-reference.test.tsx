import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { PolicyReferenceInfo } from './PolicyReferenceInfo'

const references = [
  {
    ref: 'REG-03',
    code: 'REG-03',
    group: 'REGULATION',
    registryTitle: '유지관리수수료',
    detailTitle: '유지관리 월 상한',
    explanation: '계약체결비용의 0.8% 이내',
    url: 'https://www.law.go.kr/a',
    statusTags: ['현행규정', 'FAQ'],
  },
  {
    ref: 'REG-05',
    code: 'REG-05',
    group: 'REGULATION',
    registryTitle: '장기유지수수료',
    detailTitle: null,
    explanation: null,
    url: null,
  },
  {
    ref: 'REG-12',
    code: 'REG-12',
    group: 'REGULATION',
    registryTitle: null,
    detailTitle: '스크립트 링크',
    explanation: null,
    url: 'javascript:alert(1)',
  },
]

function mount(refText: string) {
  vi.spyOn(apiClient, 'request').mockResolvedValue({ data: { references }, error: null, requestId: 'r' } as never)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  render(
    <QueryClientProvider client={client}>
      <PolicyReferenceInfo policyVersionId={7} refText={refText} />
    </QueryClientProvider>,
  )
}
afterEach(() => vi.restoreAllMocks())

it('shows the registry title and the detail stored on that policy version with a safe external link', async () => {
  mount('REG-03')
  expect(await screen.findByText('유지관리수수료')).toBeInTheDocument()
  expect(screen.getByText('유지관리 월 상한')).toBeInTheDocument()
  expect(screen.getByText('계약체결비용의 0.8% 이내')).toBeInTheDocument()
  expect(screen.getByLabelText('근거 상태')).toHaveTextContent('현행규정FAQ')
  const link = screen.getByRole('link', { name: '근거 원문 열기' })
  expect(link).toHaveAttribute('href', 'https://www.law.go.kr/a')
  expect(link).toHaveAttribute('target', '_blank')
  expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  expect(apiClient.request).toHaveBeenCalledWith('/api/v1/policies/7', expect.anything())
})

it('says no detail is stored instead of showing a shared sample explanation', async () => {
  mount('REG-05')
  expect(await screen.findByText('이 정책버전에 저장된 상세 설명이 없습니다.')).toBeInTheDocument()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})

it('never turns a non-web scheme into a link', async () => {
  mount('REG-12')
  expect(await screen.findByText('스크립트 링크')).toBeInTheDocument()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})

it('says the explanation is missing when only a title is stored', async () => {
  vi.spyOn(apiClient, 'request').mockResolvedValue({
    data: {
      references: [
        {
          ref: 'SRC-022',
          code: 'SRC-022',
          group: 'SOURCE',
          registryTitle: null,
          detailTitle: '제목만',
          explanation: null,
          url: null,
          statusTags: [],
        },
      ],
    },
    error: null,
    requestId: 'r',
  } as never)
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <PolicyReferenceInfo policyVersionId={8} refText="SRC-022" />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('제목만')).toBeInTheDocument()
  expect(screen.getByText('설명 자료 없음')).toBeInTheDocument()
})
