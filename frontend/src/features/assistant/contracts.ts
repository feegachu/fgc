import type { components } from '../../lib/api/schema'

type Schemas = components['schemas']
export const ASSISTANT_CONTRACT_VERSION = 'assistant.v1' as const
export type EntityId = string
export type AmountInput = string
export type WonAmount = string
export type PaymentStage = NonNullable<Schemas['CommissionPaymentCreateRequest']['paymentStage']>
export type WorkflowId = 'payment.amount.review' | 'payment.draft' | 'payment.confirm'
export type TargetId =
  | 'payment.scope'
  | 'payment.amount'
  | 'payment.attributions'
  | 'payment.evidence'
  | 'payment.save'
  | 'payment.precheck'
  | 'payment.precheck.result'
  | 'payment.confirm'
  | 'payment.confirm.dialog'
export type CompletionRuleId =
  | 'SCOPE_ACKNOWLEDGED'
  | 'AMOUNT_REVIEW_ACKNOWLEDGED'
  | 'ATTRIBUTIONS_REVIEWED'
  | 'EVIDENCE_FIELDS_VALID'
  | 'CURRENT_DRAFT_SAVED'
  | 'CURRENT_PRECHECK_PASSED'
  | 'PRECHECK_RESULT_ACKNOWLEDGED'
  | 'PAYMENT_CONFIRMED'
export type CalculationKind =
  | 'EXPECTED_COMMISSION'
  | 'ATTRIBUTION_BALANCE'
  | 'CAP_STATUS'
  | 'PAYMENT_CANDIDATE'
  | 'RECONCILIATION_DIFFERENCE'
  | 'ARBITRAGE_EXCESS'
  | 'JOURNAL_CORRECTION_REVIEW'
export type CalculationStatus = 'CALCULATED' | 'REVIEW_REQUIRED' | 'INSUFFICIENT_DATA' | 'UNSUPPORTED' | 'STALE'
export type MessageOutcome =
  | 'ANSWER_READY'
  | 'GUIDE_READY'
  | 'CLARIFICATION_REQUIRED'
  | 'PERMISSION_DENIED'
  | 'PRECONDITION_REQUIRED'
  | 'NO_SUPPORTED_WORKFLOW'
  | 'TEMPORARILY_UNAVAILABLE'
export interface AssistantContext {
  screenId: string
  paymentId: EntityId | null
  contractId: EntityId | null
  settlementMonth: string | null
  paymentStage: PaymentStage | null
  installmentNo: number | null
  commissionItemId: EntityId | null
  beneficiaryAgentId: EntityId | null
  formDirty: boolean
  clientRevision: number
}
/** assistant.v1 local catalog, not the server GuideStep wire DTO in interface §14-2. */
export interface GuideStep {
  stepId: TargetId
  screenId: 'TRAN-W02'
  targetId: TargetId
  title: string
  instruction: string
  entryRuleId: 'CONTEXT_AVAILABLE'
  completionRuleId: CompletionRuleId
  missingTargetBehavior: 'PAUSE'
  next: readonly { conditionId: CompletionRuleId; stepId: TargetId }[]
}
/** Local catalog fields intentionally differ from the future server proof/GuideStart contract. */
export interface GuideDefinition {
  workflowId: WorkflowId
  guideVersion: '1.0'
  title: string
  description: string
  utterances: readonly string[]
  requiredSlots: readonly (keyof AssistantContext)[]
  capability: 'READ' | 'PROCESS'
  roles: readonly ('SYSTEM_ADMIN' | 'GA_ADMIN' | 'SETTLEMENT' | 'COMPLIANCE')[]
  availableCalculationKinds: readonly CalculationKind[]
  steps: readonly Readonly<GuideStep>[]
  enabled: boolean
}
export interface SourceRef {
  sourceId: string
  sourceType: 'GUIDE' | 'POLICY' | 'SCHEDULE' | 'PAYMENT' | 'RECONCILIATION'
  title: string
  version: string | null
  entityId: EntityId | null
}
export interface Validity {
  stateToken: string
  calculatedAt: string
  expiresAt: string
  inputFingerprint: string
  dependencyFingerprint: string
}
export type AmountKey =
  | 'amount'
  | 'attributedTotal'
  | 'differenceAmount'
  | 'expectedAmount'
  | 'actualAmount'
  | 'correctedAmount'
  | 'limitAmount'
  | 'includedAmount'
  | 'remainingAmount'
  | 'currentExcessAmount'
  | 'projectedExcessAmount'
export interface CalculationLine {
  lineId: EntityId
  sourceRef: SourceRef
  installmentNo: number | null
  basisAmount: WonAmount | null
  calculationType: 'RATE' | 'FIXED' | null
  ratePct: string | null
  fixedAmount: WonAmount | null
  expectedAmount: WonAmount
  rounding: { scale: 0; mode: 'HALF_UP' }
}
type LegacyCapPreview = Schemas['CapPreviewItem']
type CapMoneyKey =
  | 'basePremiumAmount'
  | 'refund12mAmount'
  | 'complianceDeductionAmount'
  | 'limitAmount'
  | 'existingIncludedAmount'
  | 'candidateAmount'
  | 'includedAmount'
  | 'remainingAmount'
export type AssistantCapPreview = Omit<LegacyCapPreview, CapMoneyKey | 'contractId' | 'capRuleSetId' | 'capCheckId'> &
  Record<CapMoneyKey, WonAmount> & { contractId: EntityId; capRuleSetId: EntityId | null; capCheckId: null }
export interface Blocker {
  code: string
  message: string
  contractId: EntityId | null
  transactionAttributionId: EntityId | null
}
export interface CalculationResult {
  calculationId: string
  kind: CalculationKind
  status: CalculationStatus
  clientRevision: number
  synthetic: boolean
  mode: 'EXISTING' | 'HYPOTHETICAL'
  evidenceKind: 'ACTUAL' | 'EXPECTED' | 'HYPOTHETICAL'
  currency: 'KRW'
  resolvedScope: Partial<AssistantContext> | null
  amounts: Partial<Record<AmountKey, WonAmount | null>>
  lines: CalculationLine[]
  capPreview: AssistantCapPreview[]
  confirmable: boolean | null
  blockers: Blocker[]
  missingInputs: string[]
  basis: {
    asOfDate: string | null
    policyVersionIds: EntityId[]
    ruleIds: EntityId[]
    sourceRefs: SourceRef[]
    rounding: { scale: 0; mode: 'HALF_UP'; aggregation: 'ROUND_EACH_LINE_THEN_SUM' }
  }
  recommendation: {
    allowed: boolean
    targetId: TargetId | null
    suggestedAmount: WonAmount | null
    reason: string
    requiredChecks: string[]
  }
  validity: Validity
}
export interface WorkflowProposal {
  proposalId: string
  workflowId: WorkflowId
  guideVersion: string
  resolvedContext: AssistantContext
  expiresAt: string
}
export interface Clarification {
  questionId: string
  prompt: string
  options: { optionId: string; label: string }[]
  requestedSlots: string[]
}
export interface MessageResult {
  conversationId: string
  messageId: string
  clientMessageId: string
  outcome: MessageOutcome
  text: string
  missingSlots: string[]
  clarification: Clarification | null
  workflowProposal: WorkflowProposal | null
  calculations: CalculationResult[]
  sources: SourceRef[]
  clientRevision: number
  synthetic: boolean
}
export interface AssistantEnvelope<T> {
  data: T | null
  error: Schemas['ApiError'] | null
  requestId: string
}
export interface MessageRequest {
  contractVersion: typeof ASSISTANT_CONTRACT_VERSION
  conversationId: string | null
  clientMessageId: string
  text: string
  context: AssistantContext
  clarificationSelection: { questionId: string; optionId: string } | null
}
export interface CandidateAttribution {
  contractId: EntityId | null
  attributionDate: string
  amount: AmountInput
  inclusionDecisionStatus: NonNullable<Schemas['CommissionPaymentAttributionRequest']['inclusionDecisionStatus']>
  exclusionType: Schemas['CommissionPaymentAttributionRequest']['exclusionType'] | null
  inclusionDecisionReason: string
  allocationBasis: string | null
  evidenceRef: string | null
  attributionMethod: NonNullable<Schemas['CommissionPaymentAttributionRequest']['attributionMethod']>
}
export interface PaymentCandidate {
  sourceType: 'GA_MANUAL_PAYMENT'
  sourceBusinessKey: string
  contractId: EntityId | null
  agentId: EntityId | null
  commissionItemId: EntityId
  amount: AmountInput
  settlementMonth: string
  cashflowType: 'PAYMENT' | 'DEDUCTION'
  scheduledPaymentDate: string
  paymentStage: PaymentStage
  allocationPolicyVersion: EntityId | null
  attributions: CandidateAttribution[]
  evidenceRef: string | null
  note: string | null
}
export interface CalculationScope extends Partial<AssistantContext> {
  asOfDate?: string
  reconciliationResultId?: EntityId
}
export interface CalculationRequest {
  contractVersion: typeof ASSISTANT_CONTRACT_VERSION
  kind: CalculationKind
  mode: 'EXISTING' | 'HYPOTHETICAL'
  scope: CalculationScope | null
  clientRevision: number
  candidate:
    | null
    | { balance: { amount: AmountInput; attributionAmounts: AmountInput[] } }
    | { operation: 'NEW_PAYMENT' | 'REPLACE_DRAFT'; payment: PaymentCandidate }
}
export type GuideState = 'GUIDING' | 'PAUSED' | 'BLOCKED' | 'COMPLETED' | 'CANCELLED' | 'ERROR'
export interface GuideSession {
  guideSessionId: string
  workflowId: WorkflowId
  guideVersion: string
  planVersion: number
  state: GuideState
  resolvedContext: AssistantContext
  steps: GuideStep[]
  currentStepId: TargetId
  stepResult: {
    status: 'WAITING' | 'SATISFIED' | 'BLOCKED'
    reasonCode: string
    message: string
    evidenceKind: 'USER_ACK' | 'SERVER_SNAPSHOT' | 'PRECHECK_TOKEN' | 'PAYMENT_STATUS'
  }
  blockers: Blocker[]
  validity: Validity
  synthetic: boolean
}
export interface StartGuideRequest {
  contractVersion: typeof ASSISTANT_CONTRACT_VERSION
  proposalId: string
  context: AssistantContext
  clientCommandId: string
}
export interface RevalidateGuideRequest {
  expectedPlanVersion: number
  intent: 'REFRESH' | 'NEXT' | 'PREVIOUS' | 'REPLAY'
  currentStepId: TargetId
  context: AssistantContext
  precheckToken: string | null
  clientCommandId: string
}
export interface PatchGuideRequest {
  expectedPlanVersion: number
  action: 'PAUSE' | 'RESUME' | 'CANCEL'
  clientCommandId: string
}
