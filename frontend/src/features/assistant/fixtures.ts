import type { AssistantContext, CalculationKind, CalculationResult, MessageResult, WorkflowId } from './contracts'

export const PAYMENT_SCENARIOS = [
  'PAY-01',
  'PAY-02',
  'PAY-03',
  'PAY-04',
  'PAY-05',
  'PAY-06',
  'PAY-07',
  'PAY-08',
  'PAY-09',
  'PAY-10',
  'PAY-11',
  'PAY-12',
] as const
export type PaymentScenario = (typeof PAYMENT_SCENARIOS)[number]

function calculation(
  kind: CalculationKind,
  scenario: PaymentScenario,
  context: AssistantContext,
  now: number,
): CalculationResult {
  return {
    calculationId: `synthetic-${scenario}`,
    kind,
    status: 'CALCULATED',
    clientRevision: context.clientRevision,
    synthetic: true,
    mode: 'EXISTING',
    evidenceKind: 'EXPECTED',
    currency: 'KRW',
    resolvedScope: { ...context },
    amounts: {},
    lines: [],
    capPreview: [],
    confirmable: null,
    blockers: [],
    missingInputs: [],
    basis: {
      asOfDate: null,
      policyVersionIds: [],
      ruleIds: [],
      sourceRefs: [],
      rounding: { scale: 0, mode: 'HALF_UP', aggregation: 'ROUND_EACH_LINE_THEN_SUM' },
    },
    recommendation: {
      allowed: false,
      targetId: null,
      suggestedAmount: null,
      reason: '합성 시연 자료입니다. 실제 지급에 사용하지 마세요.',
      requiredChecks: ['SYNTHETIC_ONLY'],
    },
    validity: {
      stateToken: `synthetic-${scenario}-not-a-proof`,
      calculatedAt: new Date(now).toISOString(),
      expiresAt: new Date(now + 300_000).toISOString(),
      inputFingerprint: 'synthetic-input',
      dependencyFingerprint: 'synthetic-dependencies',
    },
  }
}

/** Explicitly selected fixtures, NOT a question classifier, calculator, network client or production fallback. */
export function readPaymentFixture(scenario: PaymentScenario, context: AssistantContext, now: number): MessageResult {
  if (!PAYMENT_SCENARIOS.includes(scenario)) throw new Error('등록되지 않은 합성 시나리오입니다.')
  if (!Number.isFinite(now) || !Number.isSafeInteger(context.clientRevision) || context.clientRevision < 0) {
    throw new Error('합성 응답의 시각과 clientRevision을 확인하세요.')
  }
  const result: MessageResult = {
    conversationId: 'synthetic-conversation',
    messageId: `synthetic-${scenario}-${context.clientRevision}`,
    clientMessageId: `synthetic-request-${scenario}`,
    outcome: 'ANSWER_READY',
    text: '',
    missingSlots: [],
    clarification: null,
    workflowProposal: null,
    calculations: [],
    clientRevision: context.clientRevision,
    synthetic: true,
    sources: [
      {
        sourceId: 'payment-guide-v1',
        sourceType: 'GUIDE',
        title: '지급 처리 가이드 · 합성 시연',
        version: '1.0',
        entityId: null,
      },
    ],
  }
  const clarify = (slots: string[], text: string) => {
    result.outcome = 'CLARIFICATION_REQUIRED'
    result.text = text
    result.missingSlots = slots
    result.clarification = {
      questionId: `synthetic-question-${scenario}`,
      prompt: text,
      options: [],
      requestedSlots: [...slots],
    }
  }
  const propose = (workflowId: WorkflowId, text: string) => {
    result.outcome = 'GUIDE_READY'
    result.text = text
    result.workflowProposal = {
      proposalId: `synthetic-proposal-${scenario}`,
      workflowId,
      guideVersion: '1.0',
      resolvedContext: { ...context },
      expiresAt: new Date(now + 300_000).toISOString(),
    }
  }
  switch (scenario) {
    case 'PAY-01': {
      const required = ['contractId', 'settlementMonth', 'paymentStage'] as const
      const missing = required.filter((slot) => !context[slot])
      if (missing.length) {
        clarify(missing, '예상 수수료를 확인할 계약·정산월·지급단계를 선택하세요.')
        break
      }
      const calc = calculation('EXPECTED_COMMISSION', scenario, context, now)
      calc.amounts = { expectedAmount: '100000' }
      const source = {
        sourceId: 'synthetic-policy-1',
        sourceType: 'POLICY' as const,
        title: '합성 정액 100000원 정책',
        version: '1.0',
        entityId: '1',
      }
      calc.basis.policyVersionIds = ['1']
      calc.basis.ruleIds = ['1']
      calc.basis.sourceRefs = [source]
      calc.lines = [
        {
          lineId: '1',
          sourceRef: source,
          installmentNo: context.installmentNo,
          basisAmount: null,
          calculationType: 'FIXED',
          ratePct: null,
          fixedAmount: '100000',
          expectedAmount: '100000',
          rounding: { scale: 0, mode: 'HALF_UP' },
        },
      ]

      calc.recommendation = {
        allowed: true,
        targetId: 'payment.amount',
        suggestedAmount: '100000',
        reason: '예상액 100000원은 합성 예시입니다. 실제 명세와 비교해야 합니다.',
        requiredChecks: ['SYNTHETIC_ONLY', 'SOURCE_EVIDENCE_REQUIRED'],
      }
      result.calculations.push(calc)
      result.text = '합성 예시의 예상 수수료는 100000원입니다. 실제 명세 금액과 구분해 확인하세요.'
      break
    }
    case 'PAY-02':
      propose(
        context.paymentId ? 'payment.confirm' : 'payment.draft',
        context.formDirty || !context.paymentId
          ? '입력값을 먼저 저장하고 사전검증을 진행하세요.'
          : '저장된 대상 확인 후 사전검증과 확정을 진행하세요.',
      )
      break
    case 'PAY-03': {
      const calc = calculation('ATTRIBUTION_BALANCE', scenario, context, now)
      calc.mode = 'HYPOTHETICAL'
      calc.evidenceKind = 'HYPOTHETICAL'
      calc.resolvedScope = null
      calc.amounts = { amount: '100000', attributedTotal: '90000', differenceAmount: '10000' }
      calc.recommendation.targetId = 'payment.attributions'
      calc.recommendation.reason = '미귀속 10000원입니다. 어느 계약에 귀속할지 확인하세요.'
      calc.recommendation.requiredChecks.push('ALLOCATION_BASIS_REQUIRED')
      result.calculations.push(calc)
      result.text = '합성 예시: 지급 100000원, 귀속 합계 90000원, 미귀속 10000원입니다. 자동 배부하지 않습니다.'
      break
    }
    case 'PAY-04':
      if (!context.paymentId) {
        clarify(['paymentId'], '검사할 저장된 지급 초안을 선택하세요.')
        break
      }
      result.outcome = 'PRECONDITION_REQUIRED'
      result.text =
        '현재 고정 응답에는 실제 저장본의 사전검증 증표가 없습니다. 기존 화면에서 사전검증을 직접 실행해야 합니다.'
      break
    case 'PAY-05': {
      const calc = calculation('PAYMENT_CANDIDATE', scenario, context, now)
      calc.mode = 'HYPOTHETICAL'
      calc.evidenceKind = 'HYPOTHETICAL'
      calc.status = 'INSUFFICIENT_DATA'
      calc.amounts = { amount: '150000' }
      calc.missingInputs = ['candidate.operation', 'candidate.payment']
      result.calculations.push(calc)
      clarify(
        [...calc.missingInputs],
        '15만원으로 대체할 초안인지 추가 지급인지, 전체 귀속·근거를 확인하세요. 금액만으로 지급 가능 여부를 계산하지 않습니다.',
      )
      break
    }
    case 'PAY-06':
      clarify(
        ['candidate.operation', 'candidate.payment'],
        '잔여 한도 20만원만으로 추가 지급을 승인할 수 없습니다. 전체 지급 후보와 원천 근거를 확인하세요.',
      )
      break
    case 'PAY-07': {
      const calc = calculation('RECONCILIATION_DIFFERENCE', scenario, context, now)
      calc.evidenceKind = 'ACTUAL'
      calc.amounts = {
        expectedAmount: '100000',
        actualAmount: '50000',
        differenceAmount: '-50000',
        correctedAmount: null,
      }
      calc.recommendation.requiredChecks.push('SOURCE_EVIDENCE_REQUIRED')
      result.calculations.push(calc)
      result.text =
        '합성 예시: 예상 100000원, 확정 지급 50000원, 차액 -50000원입니다. 정정 전체액은 원천 확인 전 미정이며 부족분 추가 지급을 자동 추천하지 않습니다.'
      break
    }
    case 'PAY-08':
      propose(
        context.paymentId ? 'payment.confirm' : 'payment.draft',
        '이전 추천과 검증은 재사용하지 않습니다. 변경값을 저장한 뒤 다시 검증하세요.',
      )
      result.workflowProposal!.resolvedContext.formDirty = true
      break
    case 'PAY-09':
      clarify(['intent'], '정책 수정, 지급 초안 수정, 확정 지급 정정 중 어떤 업무인가요?')
      result.clarification!.options = [
        { optionId: 'policy-edit', label: '정책 수정 · 지원 범위 확인' },
        { optionId: 'draft-edit', label: '지급 초안 수정' },
        { optionId: 'confirmed-correction', label: '확정 지급 정정' },
      ]
      break
    case 'PAY-10':
      result.outcome = 'PERMISSION_DENIED'
      result.text = '조회 역할은 지급을 확정할 수 없습니다. 처리 권한이 있는 담당자에게 요청하세요.'
      break
    case 'PAY-11': {
      const calc = calculation('EXPECTED_COMMISSION', scenario, context, now)
      calc.status = 'UNSUPPORTED'
      result.calculations.push(calc)
      result.outcome = 'NO_SUPPORTED_WORKFLOW'
      result.text =
        '4년·7년 회차 매핑 검증 전에는 월 지급액을 추천하지 않습니다. 총액을 48·84로 나눠 대체하지 않습니다.'
      break
    }
    case 'PAY-12': {
      const calc = calculation('PAYMENT_CANDIDATE', scenario, context, now)
      calc.status = 'STALE'
      calc.validity.expiresAt = new Date(now).toISOString()
      result.calculations.push(calc)
      result.outcome = 'PRECONDITION_REQUIRED'
      result.text = '지급 또는 정책이 변경된 상황의 합성 예시입니다. 이전 추천을 폐기하고 재조회·재계산하세요.'
      break
    }
  }
  result.text = `[합성 시연 · API/AI 미연결] ${result.text}`
  return result
}
