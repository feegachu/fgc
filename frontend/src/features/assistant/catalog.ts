import type { CompletionRuleId, GuideDefinition, GuideStep, TargetId } from './contracts'

const step = (
  targetId: TargetId,
  title: string,
  instruction: string,
  completionRuleId: CompletionRuleId,
): Readonly<Omit<GuideStep, 'next'>> =>
  Object.freeze({
    stepId: targetId,
    screenId: 'TRAN-W02',
    targetId,
    title,
    instruction,
    entryRuleId: 'CONTEXT_AVAILABLE',
    completionRuleId,
    missingTargetBehavior: 'PAUSE',
  })
const scope = step(
  'payment.scope',
  '지급 대상을 확인하세요',
  '정산월·지급단계·계약·수취인이 처리할 대상과 같은지 확인하세요.',
  'SCOPE_ACKNOWLEDGED',
)
const amount = step(
  'payment.amount',
  '지급액과 계산 근거를 비교하세요',
  '예상액과 실제 명세를 구분해 확인하세요. 추천만으로 지급액을 변경하지 않습니다.',
  'AMOUNT_REVIEW_ACKNOWLEDGED',
)
const save = step(
  'payment.save',
  '초안을 저장하세요',
  '기존 저장 버튼을 직접 누르세요. API가 성공해야 저장 완료입니다.',
  'CURRENT_DRAFT_SAVED',
)

const definitions = Object.freeze([
  Object.freeze({
    workflowId: 'payment.amount.review',
    guideVersion: '1.0',
    title: '지급 금액 확인',
    description: '예상액과 원천 근거를 확인합니다. 저장·확정을 자동으로 이어서 실행하지 않습니다.',
    utterances: Object.freeze(['이번 달 수수료 얼마야?', '예상 지급액 확인']),
    requiredSlots: Object.freeze(['contractId', 'settlementMonth', 'paymentStage'] as const),
    capability: 'READ',
    availableCalculationKinds: Object.freeze(['EXPECTED_COMMISSION', 'CAP_STATUS'] as const),
    steps: Object.freeze([scope, amount]),
    enabled: true,
  }),
  Object.freeze({
    workflowId: 'payment.draft',
    guideVersion: '1.0',
    title: '지급 초안 저장',
    description: '입력과 귀속·근거를 확인한 뒤 직접 저장합니다.',
    utterances: Object.freeze(['확정하지 말고 초안만 저장', '지급 초안 수정']),
    requiredSlots: Object.freeze([]),
    capability: 'PROCESS',
    availableCalculationKinds: Object.freeze(['EXPECTED_COMMISSION', 'ATTRIBUTION_BALANCE'] as const),
    steps: Object.freeze([
      scope,
      amount,
      step(
        'payment.attributions',
        '계약별 귀속을 확인하세요',
        '귀속 합계와 배부 근거를 확인하세요.',
        'ATTRIBUTIONS_REVIEWED',
      ),
      step('payment.evidence', '원천 근거를 입력하세요', '필수 근거와 입력값을 확인하세요.', 'EVIDENCE_FIELDS_VALID'),
      save,
    ]),
    enabled: true,
  }),
  Object.freeze({
    workflowId: 'payment.confirm',
    guideVersion: '1.0',
    title: '지급 사전검증·확정',
    description: '저장 상태에 맞춰 사전검증과 직접 확정을 안내합니다.',
    utterances: Object.freeze(['지급액 입력했는데 다음은?', '지급 확정 방법']),
    requiredSlots: Object.freeze(['paymentId'] as const),
    capability: 'PROCESS',
    availableCalculationKinds: Object.freeze(['PAYMENT_CANDIDATE'] as const),
    steps: Object.freeze([
      scope,
      save,
      step(
        'payment.precheck',
        '사전검증을 실행하세요',
        '기존 사전검증 버튼을 직접 누르세요. 200 응답만으로 통과한 것은 아닙니다.',
        'CURRENT_PRECHECK_PASSED',
      ),
      step(
        'payment.precheck.result',
        '검증 결과를 확인하세요',
        'confirmable과 차단 사유를 확인하세요.',
        'PRECHECK_RESULT_ACKNOWLEDGED',
      ),
      step(
        'payment.confirm',
        '지급을 확정하세요',
        '확인 모달에서 직접 확정하세요. 서버의 CONFIRMED 응답이 필요합니다.',
        'PAYMENT_CONFIRMED',
      ),
    ]),
    enabled: true,
  }),
])

// Register roles and every next edge explicitly; model text cannot invent a branch.
export const GUIDE_CATALOG: readonly Readonly<GuideDefinition>[] = Object.freeze(
  definitions.map((guide) =>
    Object.freeze({
      ...guide,
      roles:
        guide.capability === 'READ'
          ? Object.freeze(['SYSTEM_ADMIN', 'GA_ADMIN', 'SETTLEMENT', 'COMPLIANCE'] as const)
          : Object.freeze(['SYSTEM_ADMIN', 'SETTLEMENT'] as const),
      steps: Object.freeze(
        guide.steps.map((current, index) =>
          Object.freeze({
            ...current,
            next: Object.freeze(
              index + 1 < guide.steps.length
                ? [Object.freeze({ conditionId: current.completionRuleId, stepId: guide.steps[index + 1].stepId })]
                : [],
            ),
          }),
        ),
      ),
    }),
  ),
)

/** Only registered IDs are resolved. Routes, selectors and executable conditions never come from model text. */
export function findGuide(workflowId: string, guideVersion: string): Readonly<GuideDefinition> | null {
  return (
    GUIDE_CATALOG.find(
      (guide) => guide.enabled && guide.workflowId === workflowId && guide.guideVersion === guideVersion,
    ) ?? null
  )
}
export function findStep(workflowId: string, guideVersion: string, stepId: string): Readonly<GuideStep> | null {
  return findGuide(workflowId, guideVersion)?.steps.find((entry) => entry.stepId === stepId) ?? null
}
