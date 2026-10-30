import type { CompareItem } from './api'
import { rate } from '../../../lib/format'

export const won = (n: number) => `${n.toLocaleString('ko-KR')}원`

/**
 * 한도 영향 배지: 이월액이 있으면 초과·이월, 90% 이상 근접, 그 미만 여유(기획서 §4-3, 근접 임계값은 [가정]).
 * 초과 여부는 서버가 반올림해 준 비율이 아니라 실제 이월액으로 판정한다(99.96%가 100.0%로 보여도 이월은 아니다).
 */
export function capBadge(pct: number | null, carryover = 0): { tone: 'ok' | 'warn' | 'bad' | 'none'; text: string } {
  if (pct == null) return { tone: 'none', text: '자료 없음' }
  if (carryover > 0) return { tone: 'bad', text: `${rate(pct)}% 초과·이월` }
  if (pct >= 90) return { tone: 'warn', text: `${rate(pct)}% 근접` }
  return { tone: 'ok', text: `${rate(pct)}% 여유` }
}

const GRADES: Record<string, string> = { VERY_HIGH: '매우 높음', HIGH: '높음', NORMAL: '보통', LOW: '낮음', VERY_LOW: '매우 낮음' }
/** 협회 등급은 협회 자료 값을 표시만 한다. 자료가 없으면 "자료 없음". */
export const gradeLabel = (grade: string | null) => (grade ? (GRADES[grade] ?? grade) : '자료 없음')

export const FAQ = [
  { key: 'about', label: '피코 AI 소개', body: '고른 카테고리의 보험사별 상품을 내 계약 기준으로 비교해 참고 순위를 알려 드려요. 추천을 확정하지 않으며, 모든 숫자는 근거 보기에서 확인할 수 있어요.' },
  { key: 'rank', label: '순위 기준', body: '보수적 순수령액(한도 반영 수령 예상액 − 환수 노출액)이 큰 순서예요. 같으면 환수 노출이 작은 쪽, 그래도 같으면 상품 ID 기준 표시 순서예요. 협회 등급은 표시만 해요.' },
  { key: 'limit', label: '1,200% 한도 영향', body: '상품의 첫해 수령 예상액이 한도(정책에 따른 기준)에 얼마나 가까운지 보여 줘요. 100%를 넘는 부분은 이월 예정액으로 따로 계산해요.' },
  { key: 'grade', label: '협회 수수료 등급', body: '협회 공시 등급을 읽기 전용으로 보여 줘요. 순위 계산에는 쓰지 않고, 자료가 없으면 "자료 없음"으로 표시해요.' },
  { key: 'privacy', label: '개인정보 안내', body: '고객 이름·주민번호·연락처는 입력받지 않아요. 상품 구조와 내 수수료·한도 정보만 사용해요.' },
]

export type FollowUpKind = 'why' | 'limit'

/** 결과 아래에 보여 주는 연관 질문 문구. 1위 상품 이름만 서버 값에서 가져온다. */
export function followUpQuestion(kind: FollowUpKind, top: CompareItem) {
  return kind === 'why' ? `왜 1위가 ${top.insurer} ${top.product}인지 궁금해요` : '한도와 환수가 어떻게 반영됐는지 궁금해요'
}

/** 연관 질문의 답. 서버가 준 순위·금액·한도 값만 풀어 쓰고 새 수치를 만들지 않는다. */
export function followUpAnswer(kind: FollowUpKind, items: CompareItem[]) {
  const top = items[0]
  if (kind === 'why') {
    // 순수령액이 2위와 같으면 "가장 높다"가 아니라 서버가 실제로 순위를 가른 기준을 heroWhy 가 설명한다.
    const tied = items.length > 1 && items[1].conservativeNet === top.conservativeNet
    const lead = `1위는 ${top.insurer} ${top.product}예요. 한도를 반영한 수령 예상액 ${won(top.cappedReceipt)}에서 환수 노출액 ${won(top.clawbackExposure)}을 뺀 보수적 순수령액은 ${won(top.conservativeNet)}${tied ? '이에요.' : '으로 가장 높아요.'}`
    return [lead, heroWhy(items)].filter(Boolean).join(' ')
  }
  const cap = `1위 상품의 한도 영향은 ${capBadge(top.capImpactPct, top.carryover).text}예요.`
  const carry = top.carryover > 0 ? ` 한도를 넘는 ${won(top.carryover)}은 이월 예정액으로 따로 계산해요.` : ''
  const claw =
    top.clawbackRatePct == null
      ? ''
      : ` 환수 노출액 ${won(top.clawbackExposure)}은 지급액에 ${rate(top.clawbackRatePct)}%를 곱한 값이고, 이 환수율은 해약환급률을 임시로 쓴 값이에요.`
  return `${cap}${carry}${claw} 자세한 계산은 근거 보기에서 확인할 수 있어요.`
}

/** 1위가 2위보다 얼마나 앞서는지를 서버가 준 값으로만 풀어 쓴다(새 수치를 만들지 않는다). */
export function heroWhy(items: CompareItem[]) {
  if (items.length < 2) return ''
  const [top, second] = items
  const gap = top.conservativeNet - second.conservativeNet
  // 서버 정렬: 순수령액 내림차순, 같으면 환수 노출액 오름차순, 그래도 같으면 상품 ID 오름차순.
  if (gap === 0) {
    return top.clawbackExposure < second.clawbackExposure
      ? '2위와 순수령액은 같고, 환수 노출액이 더 작아서 1위예요.'
      : '2위와 순수령액·환수 노출액이 같아서 표시 순서(상품 ID) 기준으로 1위예요.'
  }
  const claw =
    top.clawbackExposure <= second.clawbackExposure
      ? '환수 부담도 더 작아요.'
      : `환수 부담은 ${won(top.clawbackExposure - second.clawbackExposure)} 더 크지만 수령 예상액이 더 높아요.`
  return `2위보다 순수령액이 ${won(gap)} 많아요. ${claw}`
}
