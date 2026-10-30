import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../../lib/api/client'
import type { CompareExplain, CompareItem, CompareRanking } from './api'
import { ChatbotModal } from './ChatbotModal'

// 선별 단계를 일부러 천천히 보여 주므로(체크 4개 × 1초) 테스트 제한 시간을 넉넉히 둔다.
vi.setConfig({ testTimeout: 15_000 })

const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'chatbot' })
const item = (rank: number, patch: Partial<CompareItem> = {}): CompareItem => ({
  rank,
  offeringId: rank,
  insurer: `${rank}번보험`,
  product: `상품${rank}`,
  expectedReceipt: 900_000,
  cappedReceipt: 900_000 - rank * 10_000,
  carryover: 0,
  clawbackExposure: 200_000 + rank * 10_000,
  conservativeNet: 700_000 - rank * 50_000,
  capImpactPct: 67.083333,
  associationGrade: null,
  evidence: [
    { label: '수령 예상액', rule: '현행수수료 1~1회차 · 기준 보험료 × 650%', value: 650_000, source: '수수료 규칙', policyVersion: 'GA-CUR-2026-V1 v1', asOf: '2026-07-31' },
    { label: '환수 노출액', rule: '현행수수료 1~1회차 · 한도 반영 지급액 × 환수율 34.8%', value: 226_200, source: '환수 규칙', policyVersion: 'CLAW-GA-VIRTUAL-2026 v1', asOf: '2026-07-31' },
  ],
  ...patch,
})
const ranking = (patch: Partial<CompareRanking> = {}): CompareRanking => ({
  rankResultId: 'r-1',
  status: 'READY',
  categoryCode: 'HEALTH_PROTECTION',
  categoryLabel: '건강보험',
  asOf: '2026-07-31',
  ruleVersion: 'TEST-RULE',
  policyVersion: 'GA-CUR-2026-V1 v1',
  basePremium: 100_000,
  clawbackMonth: 12,
  warnings: [],
  capSummary: { usagePct: null, status: null, contractCount: 0 },
  items: [1, 2, 3, 4, 5].map((n) => item(n)),
  excludedItems: [],
  filters: { insurerIds: [], excludeCapOverflow: false },
  ...patch,
})
const explain = (patch: Partial<CompareExplain> = {}): CompareExplain => ({ rankResultId: 'r-1', summary: '건강보험 5개 보험사를 정렬했어요.', summarySource: 'RULE', summaryError: null, ...patch })

let onRank: (body?: unknown) => Promise<unknown>
let onExplain: () => Promise<unknown>
let onAsk: (body?: unknown) => Promise<unknown>
let onNews: () => Promise<unknown>
const clients: QueryClient[] = []

beforeEach(() => {
  onRank = async () => envelope(ranking())
  onExplain = async () => envelope(explain())
  onAsk = async () => envelope({ answer: "답변", source: "RULE" })
  onNews = async () => envelope([{ title: '금감원, 보험 판매 수수료 제도 개선안 발표', link: 'https://www.insjournal.co.kr/news/articleView.html?idxno=1', publishedAt: '2026-10-29 13:50:29' }])
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string, init?: { body?: unknown }) => {
    if (path.endsWith('/categories')) {
      return envelope([
        { code: 'HEALTH_PROTECTION', label: '건강보험', insurerCount: 4, insurers: [{ id: 1, name: '가보험' }, { id: 2, name: '나보험' }] },
        { code: 'SAVINGS', label: '연금저축보험', insurerCount: 0, insurers: [] },
      ])
    }
    if (path.endsWith('/rank')) return onRank(init?.body)
    if (path.endsWith('/explain')) return onExplain()
    if (path.endsWith('/ask')) return onAsk(init?.body)
    if (path.endsWith('/news/headlines')) return onNews()
    throw new Error(`unexpected ${path}`)
  }) as typeof apiClient.request)
})
afterEach(() => {
  vi.restoreAllMocks()
  for (const client of clients.splice(0)) client.clear()
})

function renderModal(onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <ChatbotModal onClose={onClose} />
    </QueryClientProvider>,
  )
  return onClose
}
const pickHealth = async () => {
  await userEvent.click(await screen.findByRole('button', { name: /건강보험/ }))
}
const result = async () => screen.findByRole('region', { name: '참고 순위 1위' }, { timeout: 9000 })

describe('FUN-079 상품 추천 AI 챗봇', () => {
  it('1단계에 최신 뉴스 제목이 나오고 누르면 원문 기사를 새 탭으로 연다', async () => {
    renderModal()
    const link = await screen.findByRole('link', { name: /보험 판매 수수료 제도 개선안/ })
    expect(link).toHaveAttribute('href', 'https://www.insjournal.co.kr/news/articleView.html?idxno=1')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
    expect(screen.queryByText('내 한도 현황')).not.toBeInTheDocument()
  })

  it('뉴스를 못 가져와도 카테고리 선택은 그대로 쓸 수 있다', async () => {
    onNews = async () => {
      throw new Error('down')
    }
    renderModal()
    expect(await screen.findByText('지금은 불러올 수 있는 뉴스가 없어요.')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /건강보험/ })).toBeEnabled()
  })

  it('정상: 선별 → 1위 카드·순위 표·요약이 나온다', async () => {
    renderModal()
    await pickHealth()
    expect(await screen.findByText('내 조건에 맞는 상품을 선별하고 있어요')).toBeInTheDocument()
    const hero = await result()
    expect(within(hero).getByText('상품1')).toBeInTheDocument()
    expect(within(hero).getByText('650,000원')).toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(6)
    expect(await screen.findByText('건강보험 5개 보험사를 정렬했어요.')).toBeInTheDocument()
    expect(screen.getAllByText('자료 없음').length).toBeGreaterThan(0)
  })

  it('결과 화면에 순위 기준이 D-01 가정이고 환수 노출액은 환수 규칙으로 계산했다는 안내를 규칙 버전과 함께 보여 준다', async () => {
    renderModal()
    await pickHealth()
    await result()
    const note = await screen.findByTestId('assumption-note')
    expect(note).toHaveTextContent('D-01에서 정한 가정(PROJECT_ASSUMPTION, 규칙 TEST-RULE)')
    expect(note).toHaveTextContent('1,200% 한도 공통 배수 근사')
    expect(note).toHaveTextContent('12개월차에 해지된다고 보고 환수 규칙의 환수율로 계산했어요')
    expect(note).not.toHaveTextContent('해약환급률')
  })

  it('카테고리를 고르고 결과가 나와도 포커스가 모달 안에 남아 Esc로 닫을 수 있다', async () => {
    const onClose = renderModal()
    await pickHealth()
    await result()
    expect(screen.getByRole('dialog')).toContainElement(document.activeElement as HTMLElement)
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('세부 조건을 고르면 그 조건이 순위 요청에 담기고 결과에 적용 조건이 표시된다', async () => {
    let sent: unknown
    onRank = async (body) => {
      sent = body
      return envelope(ranking({ filters: { insurerIds: [2], excludeCapOverflow: true } }))
    }
    renderModal()
    await screen.findByRole('region', { name: '보험사 세부 선택' })
    await userEvent.click(screen.getByRole('button', { name: '나보험' }))
    await userEvent.click(screen.getByLabelText(/이월이 생기는 상품은 제외/))
    await pickHealth()
    await result()
    expect(sent).toEqual({
      categoryCode: 'HEALTH_PROTECTION',
      filters: { insurerIds: [2], excludeCapOverflow: true },
    })
    expect(screen.getByRole('note')).toHaveTextContent('보험사 1곳 · 한도 초과 상품 제외')
  })

  it('조건에 맞는 상품이 없으면 안내한다', async () => {
    onRank = async () => envelope(ranking({ status: 'INSUFFICIENT_DATA', items: [], rankResultId: null, warnings: ['FILTER_NO_MATCH'] }))
    renderModal()
    await pickHealth()
    expect(await screen.findByText(/선택한 세부 조건에 맞는 상품이 없어요/, undefined, { timeout: 9000 })).toBeInTheDocument()
  })

  it('판매 중인 상품이 없는 카테고리는 선택할 수 없다', async () => {
    renderModal()
    expect(await screen.findByRole('button', { name: /연금저축보험/ })).toBeDisabled()
  })

  it('근거 보기를 누르면 서버 근거와 정책 버전·기준일이 서랍에 나온다', async () => {
    renderModal()
    await pickHealth()
    await result()
    await userEvent.click(screen.getByRole('button', { name: '1번보험 상품1 근거 보기' }))
    const drawer = screen.getByRole('complementary', { name: '산출 근거' })
    expect(within(drawer).getByText('현행수수료 1~1회차 · 기준 보험료 × 650%')).toBeInTheDocument()
    expect(within(drawer).getByText(/GA-CUR-2026-V1 v1 · 기준일 2026-07-31/)).toBeInTheDocument()
    // 보수적 순수령액(650,000원)과 수령 예상 근거 줄(650,000원)에 같은 금액이 함께 나온다
    expect(within(drawer).getAllByText('650,000원')).toHaveLength(2)
  })

  it('한도를 넘어 이월되는 상품은 서랍에 이월 예정액을 보여 준다', async () => {
    onRank = async () => envelope(ranking({ items: [item(1, { carryover: 130_000, capImpactPct: 110.8 })] }))
    renderModal()
    await pickHealth()
    await result()
    await userEvent.click(screen.getByRole('button', { name: '1번보험 상품1 근거 보기' }))
    const drawer = screen.getByRole('complementary', { name: '산출 근거' })
    expect(within(drawer).getByText('한도 초과로 이월되는 예정액')).toBeInTheDocument()
    expect(within(drawer).getByText('130,000원')).toBeInTheDocument()
    expect(within(drawer).getByText('110.8% 초과·이월')).toBeInTheDocument()
  })

  it('Esc는 서랍을 먼저 닫고 그다음에 모달을 닫는다', async () => {
    const onClose = renderModal()
    await pickHealth()
    await result()
    await userEvent.click(screen.getByRole('button', { name: '1번보험 상품1 근거 보기' }))
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('complementary', { name: '산출 근거' })).not.toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('다른 카테고리 보기로 1단계에 돌아간다', async () => {
    renderModal()
    await pickHealth()
    await result()
    await userEvent.click(screen.getByRole('button', { name: '다른 카테고리도 추천 받기' }))
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByText('어떤 카테고리의 상품을 비교해 볼까요?')).toBeInTheDocument()
  })

  it('질문을 고르기 전에는 보내기가 눌리지 않고, 골라서 보내면 답변이 붙는다', async () => {
    renderModal()
    await pickHealth()
    await result()
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: /왜 1위가 1번보험 상품1인지/ }))
    expect(screen.getByRole('button', { name: '보내기' })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))

    expect(await screen.findByText(/1위는 1번보험 상품1예요/)).toBeInTheDocument()
    // 이미 받은 질문 버튼은 다시 누를 수 없고 보내기는 다시 잠긴다
    expect(screen.getByRole('button', { name: /왜 1위가 1번보험 상품1인지/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled()
  })

  it('직접 쓴 질문은 서버 /ask 로 보내고 AI 답변을 대화에 붙인다', async () => {
    let sent: unknown
    onAsk = async (body) => {
      sent = body
      return envelope({ answer: '이월은 한도를 넘어 다음으로 넘어가는 금액이에요.', source: 'LLM' })
    }
    renderModal()
    await pickHealth()
    await result()
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled()

    await userEvent.type(screen.getByLabelText('질문 입력'), '이월이 뭐예요?')
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))

    expect(await screen.findByText(/한도를 넘어 다음으로 넘어가는 금액/)).toBeInTheDocument()
    expect(screen.queryByText('AI 답변')).not.toBeInTheDocument()
    expect(screen.queryByText('AI 요약')).not.toBeInTheDocument()
    expect(sent).toEqual({ rankResultId: 'r-1', question: '이월이 뭐예요?' })
    expect(screen.getByLabelText('질문 입력')).toHaveValue('')
  })

  it('질문 답변을 받지 못하면 실패 안내를 보여 주고 순위 화면은 그대로 둔다', async () => {
    onAsk = async () => {
      throw new Error('boom')
    }
    renderModal()
    await pickHealth()
    await result()
    await userEvent.type(screen.getByLabelText('질문 입력'), '궁금해요')
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))

    expect(await screen.findByText(/답변을 불러오지 못했어요/)).toBeInTheDocument()
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1)
  })

  it('한도·환수 질문은 서버가 준 값으로만 답한다', async () => {
    renderModal()
    await pickHealth()
    await result()
    await userEvent.click(screen.getByRole('button', { name: '한도와 환수가 어떻게 반영됐는지 궁금해요' }))
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByText(/1위 상품의 한도 영향은/)).toBeInTheDocument()
    expect(screen.getByText(/환수 규칙의 항목별 환수율을 지급액에 곱한 값이에요/)).toBeInTheDocument()
  })

  it('후보 상품이 없으면 답변을 찾지 못했어요를 보여 준다', async () => {
    onRank = async () => envelope(ranking({ items: [], warnings: ['NO_CANDIDATE'], rankResultId: null }))
    renderModal()
    await pickHealth()
    expect(await screen.findByText('답변을 찾지 못했어요', undefined, { timeout: 9000 })).toBeInTheDocument()
  })

  it('계산 기준이 확정되지 않았으면 순위 대신 안내를 보여 준다', async () => {
    onRank = async () => envelope(ranking({ status: 'RULE_NOT_CONFIGURED', items: [], rankResultId: null, ruleVersion: null, warnings: ['RULE_NOT_CONFIGURED'] }))
    renderModal()
    await pickHealth()
    expect(await screen.findByText(/계산 기준이 아직 확정되지 않아/, undefined, { timeout: 9000 })).toBeInTheDocument()
  })

  it('자료가 부족해 제외된 상품이 있으면 개수를 알린다', async () => {
    onRank = async () =>
      envelope(ranking({ warnings: ['SOME_EXCLUDED'], excludedItems: [{ offeringId: 9, insurer: 'Z', product: 'Z', reasonCodes: ['CLAWBACK_RATE_MISSING'] }] }))
    renderModal()
    await pickHealth()
    await result()
    expect(screen.getByRole('alert')).toHaveTextContent('자료가 부족한 상품 1개는 순위에서 제외했어요')
  })

  it('제외된 상품은 이름과 사유를 읽을 수 있게 보여 준다', async () => {
    onRank = async () =>
      envelope(ranking({ warnings: ['SOME_EXCLUDED'], excludedItems: [{ offeringId: 9, insurer: '다보험', product: '제외상품', reasonCodes: ['CLAWBACK_RATE_MISSING', 'CAP_RULE_MISSING'] }] }))
    renderModal()
    await pickHealth()
    await result()
    expect(screen.getByRole('list', { name: '비교에서 제외된 상품' })).toHaveTextContent('다보험 제외상품 — 환수 규칙 없음, 한도 규칙 없음')
  })

  it('모든 상품이 제외돼 순위가 비어도 제외 상품과 사유를 보여 준다', async () => {
    onRank = async () =>
      envelope(
        ranking({
          status: 'INSUFFICIENT_DATA',
          items: [],
          rankResultId: null,
          warnings: ['ALL_EXCLUDED'],
          excludedItems: [{ offeringId: 9, insurer: '다보험', product: '제외상품', reasonCodes: ['NO_COMMISSION_RULE'] }],
        }),
      )
    renderModal()
    await pickHealth()
    expect(await screen.findByRole('list', { name: '비교에서 제외된 상품' }, { timeout: 9000 })).toHaveTextContent('다보험 제외상품 — 수수료 규칙 없음')
  })

  it('보험사가 3곳 미만이면 경고를 표시한다', async () => {
    onRank = async () => envelope(ranking({ warnings: ['OTHER_INSURER_LT_3'], items: [item(1), item(2)] }))
    renderModal()
    await pickHealth()
    await result()
    expect(screen.getByRole('alert')).toHaveTextContent('다른 보험사 상품이 2개뿐이에요')
  })

  it('요약이 실패해도 카드와 표는 그대로 두고 요약 말풍선만 실패를 알린다', async () => {
    onExplain = async () => {
      throw new ApiError(null, 'req-ai', 503)
    }
    renderModal()
    await pickHealth()
    await result()
    expect(await screen.findByText(/요약을 불러오지 못했어요\(FGC-AI-001\)/)).toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(6)
  })

  it('순위 조회가 실패하면 오류와 다시 시도를 보여 준다', async () => {
    onRank = async () => {
      throw new ApiError(null, 'req-rank', 500)
    }
    renderModal()
    await pickHealth()
    expect(await screen.findByText('순위를 불러오지 못했어요.')).toBeInTheDocument()
    onRank = async () => envelope(ranking())
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await result()
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6))
  })

  it('자주 묻는 질문은 고정 문구를 보여 주고 서버를 호출하지 않는다', async () => {
    renderModal()
    await userEvent.click(await screen.findByRole('button', { name: '개인정보 안내' }))
    expect(screen.getByText(/고객 이름·주민번호·연락처는 입력하지 마세요/)).toBeInTheDocument()
    expect(vi.mocked(apiClient.request).mock.calls.some(([path]) => String(path).endsWith('/rank'))).toBe(false)
  })
})
