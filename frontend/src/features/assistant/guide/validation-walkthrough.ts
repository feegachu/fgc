import type { ScreenHelpStep } from './ScreenHelp'
import { menuStep } from './menu-handoff'

const step = (targetId: string, selector: string, title: string, instruction: string, interaction: string): ScreenHelpStep => ({
  targetId, selector, title, instruction, interaction, group: '월 통합검증', details: [], concise: true,
  presentation: { fixedCard: 'right' },
})
const summary = '[aria-labelledby="vrun-summary-title"]'
export const validationWalkthrough: ScreenHelpStep[] = [
  step('validation.header', '[aria-labelledby="vrun-header-title"]', '검증 실행 확인', '검증월과 실행 회차를 확인하세요.', '계산완료와 최종 확정은 서로 다릅니다.'),
  { ...step('validation.preview', '[data-guide-target="validation.progress"]', '10단계 진행 상태', '검증이 진행되는 순서를 살펴보세요.', '실제 실행은 잠시 후 직접 시작합니다.'),
    onEnter: () => { window.dispatchEvent(new Event('fgc-validation-guide-preview-start')); return () => { window.dispatchEvent(new Event('fgc-validation-guide-preview-stop')) } } },
  { ...step('validation.summary', '[data-guide-target="validation.results"]', '결과 요약 먼저 보기', '여섯 카드는 검증 순서대로 채워집니다.', '아래 대상·예외·확정 전 점검도 함께 살펴보세요.'), presentation: { fixedCard: 'right', allowPageScroll: true, scrollTargetTop: true } },
  { ...step('validation.start', '[data-guide-target="validation.execute"]', '검증 시작', '강조된 실행 버튼을 누르세요.', '실행 중에는 안내를 숨기고 결과를 보여드립니다.'), requireAction: true, advanceOnEvent: 'fgc-validation-execute-accepted' },
  { ...step('validation.playback', '[data-guide-target="validation.results"]', '결과 표시 중', '결과 카드가 모두 나타날 때까지 기다립니다.', '오류가 발생하면 진행 단계에서 확인하세요.'), presentation: { fixedCard: 'right', allowPageScroll: true, scrollTargetTop: true }, requireAction: true },
  step('validation.summaryComplete', summary, '결과 요약', '한도·차익거래·원장·대사·예외를 확인합니다.', '전체 건수와 각 항목의 상태를 함께 살펴보세요.'),
  step('validation.targets', '[aria-labelledby="vrun-target-title"]', '대상 선별 결과', '선정·제외·검토필요 계약과 사유입니다.', '검토필요 계약은 상품과 환급률표를 확인하세요.'),
  step('validation.exceptions', '[aria-labelledby="vrun-exception-title"]', '예외 생성 결과', '신규·재검출·재발·미처리 건수를 확인합니다.', '이번 실행의 검출과 남아 있는 업무건을 구분하세요.'),
  { ...step('validation.checklist', '[data-guide-target="validation.finalizeReview"]', '확정 전 8단계 점검', '미충족 조건이 있으면 아래 확정 버튼을 누를 수 없습니다.', '미충족 항목의 바로가기에서 원인을 확인하세요.'), presentation: { fixedCard: 'center', scrollTargetTop: true } },
  menuStep('validation.openReconciliation', 'reco-w01', '대사 실행/결과', '/reconciliations'),
]
