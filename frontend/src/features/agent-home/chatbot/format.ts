import type { CompareItem } from './api'
import type { StatusTone } from '../../../components/StatusBadge'
import { rate } from '../../../lib/format'

export const won = (n: number) => `${n.toLocaleString('ko-KR')}원`

/**
 * 한도 영향 배지: 이월액이 있으면 초과/이월, 90% 이상 근접, 그 미만 여유(기획서 §4-3, 근접 임계값은 [가정]).
 * 초과 여부는 비율이 아니라 실제 이월액으로 판정한다(한도와 정확히 같으면 100%여도 이월은 0원이다).
 * 비율은 서버 원값을 소수 첫째 자리 절사로 보여 준다(화면정의서 공통규칙 3). tone은 공용 StatusBadge 색이다.
 */
export function capBadge(pct: number | null, carryover = 0): { tone: StatusTone; text: string } {
  if (pct == null) return { tone: 'warning', text: '자료 없음' }
  if (carryover > 0) return { tone: 'error', text: `${rate(pct)}% 사용 · 초과/이월` }
  if (pct >= 90) return { tone: 'warning', text: `${rate(pct)}% 사용 · 근접` }
  return { tone: 'neutral', text: `${rate(pct)}% 사용 · 여유` }
}

const GRADES: Record<string, string> = { VERY_HIGH: '매우 높음', HIGH: '높음', NORMAL: '보통', LOW: '낮음', VERY_LOW: '매우 낮음' }
/** 협회 등급은 협회 자료 값을 표시만 한다. 자료가 없으면 "자료 없음". */
export const gradeLabel = (grade: string | null) => (grade ? (GRADES[grade] ?? grade) : '자료 없음')
export const gradeTone = (grade: string | null): StatusTone => (grade ? 'info' : 'warning')

export const FAQ = [
  { key: 'about', label: '피코 AI 소개', body: '고른 카테고리의 보험사별 상품을 내 계약 기준으로 비교해 참고 순위를 알려 드려요. 추천을 확정하지 않으며, 모든 숫자는 계산 내역에서 확인할 수 있어요.' },
  { key: 'rank', label: '순위 기준', body: '해지를 가정했을 때의 순수령액(수령액 − 예상 환수액)이 큰 순서예요. 수령액에는 한도를 반영해요. 같으면 예상 환수액이 적은 쪽, 그래도 같으면 상품 등록 순서로 정해요. 협회 등급은 참고용이에요.' },
  { key: 'limit', label: '1,200% 한도 여유', body: '상품의 첫해 수령액이 한도(정책에 따른 기준)에 얼마나 가까운지 보여 줘요. 비율은 한도 사용률이에요. 100%를 넘는 부분은 이월 예정액으로 따로 계산해요.' },
  { key: 'grade', label: '협회 수수료 등급', body: '협회 공시 등급을 읽기 전용으로 보여 줘요. 순위 계산에는 쓰지 않고, 자료가 없으면 "자료 없음"으로 표시해요.' },
  { key: 'privacy', label: '개인정보 안내', body: '고객 이름, 주민번호, 연락처는 입력하지 마세요. 주민번호나 연락처로 보이는 질문은 AI에 보내지 않아요. 상품 구조와 내 수수료, 한도 정보만 사용해요.' },
]

export type FollowUpKind = 'why' | 'limit'

/** 결과 아래에 보여 주는 연관 질문 문구. 1위 상품 이름만 서버 값에서 가져온다. */
export function followUpQuestion(kind: FollowUpKind, top: CompareItem) {
  return kind === 'why' ? `왜 1위가 ${top.insurer} ${top.product}인지 궁금해요` : '한도와 환수가 어떻게 반영됐는지 궁금해요'
}

/** 연관 질문의 답. 서버가 준 순위·금액·한도 값만 풀어 쓰고 새 수치를 만들지 않는다. */
export function followUpAnswer(kind: FollowUpKind, items: CompareItem[], clawbackMonth: number) {
  const top = items[0]
  if (kind === 'why') {
    // 순수령액이 2위와 같으면 "가장 높다"가 아니라 서버가 실제로 순위를 가른 기준을 heroWhy 가 설명한다.
    const tied = items.length > 1 && items[1].conservativeNet === top.conservativeNet
    const lead = `1위는 ${top.insurer} ${top.product}예요. ${clawbackMonth}개월 차에 해지한다고 가정하면, 수령액 ${won(top.cappedReceipt)}에서 예상 환수액 ${won(top.clawbackExposure)}을 뺀 순수령액은 ${won(top.conservativeNet)}${tied ? '이에요.' : '으로 가장 높아요.'}`
    return [lead, heroWhy(items)].filter(Boolean).join(' ')
  }
  const cap = `1위 상품의 한도 여유는 ${capBadge(top.capImpactPct, top.carryover).text} 상태예요.`
  const carry = top.carryover > 0 ? ` 한도를 넘는 ${won(top.carryover)}은 이월 예정액으로 따로 계산해요.` : ''
  const claw = ` 예상 환수액 ${won(top.clawbackExposure)}은 ${clawbackMonth}개월 차에 해지한다고 가정하고, 환수 규칙의 항목별 환수율을 지급액에 곱한 값이에요.`
  return `${cap}${carry}${claw} 자세한 계산은 계산 내역에서 확인할 수 있어요.`
}

/** 1위가 2위보다 얼마나 앞서는지를 서버가 준 값으로만 풀어 쓴다(새 수치를 만들지 않는다). */
export function heroWhy(items: CompareItem[]) {
  if (items.length < 2) return ''
  const [top, second] = items
  const gap = top.conservativeNet - second.conservativeNet
  // 서버 정렬: 순수령액 내림차순, 같으면 환수 노출액 오름차순, 그래도 같으면 상품 ID 오름차순.
  if (gap === 0) {
    return top.clawbackExposure < second.clawbackExposure
      ? '2위와 순수령액은 같고, 예상 환수액이 더 적어서 1위예요.'
      : '2위와 순수령액과 예상 환수액이 같아서 상품 등록 순서로 1위예요.'
  }
  const claw =
    top.clawbackExposure < second.clawbackExposure
      ? '예상 환수액도 더 적어요.'
      : top.clawbackExposure === second.clawbackExposure
        ? '예상 환수액은 같아요.'
        : `예상 환수액은 ${won(top.clawbackExposure - second.clawbackExposure)} 더 많지만 수령액이 더 높아요.`
  return `2위보다 순수령액이 ${won(gap)} 많아요. ${claw}`
}
