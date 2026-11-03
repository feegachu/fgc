import { findGuide, findStep } from './catalog'
import type { AssistantContext, CalculationResult, MessageResult } from './contracts'

export function isEntityId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}
export function isAmountInput(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9]\d{0,12})(\.\d{1,2})?$/.test(value)
}
export function isWonAmount(value: unknown): value is string {
  return typeof value === 'string' && /^(0|-?[1-9]\d*)$/.test(value)
}
export function isCurrentResponse(response: Pick<MessageResult, 'clientRevision'>, context: AssistantContext): boolean {
  return (
    Number.isSafeInteger(response.clientRevision) &&
    response.clientRevision >= 0 &&
    response.clientRevision === context.clientRevision
  )
}
const contextKeys = [
  'screenId',
  'paymentId',
  'contractId',
  'settlementMonth',
  'paymentStage',
  'installmentNo',
  'commissionItemId',
  'beneficiaryAgentId',
  'formDirty',
  'clientRevision',
] as const

/** UI freshness check only. Server authorization, business state and proof checks remain mandatory. */
export function checkGuideProposal(
  response: MessageResult,
  context: AssistantContext,
  options: { mode?: 'server' | 'synthetic'; now: number; role?: string },
) {
  const proposal = response.workflowProposal
  if (response.outcome !== 'GUIDE_READY' || !proposal) return { ok: false, reason: 'NO_PROPOSAL' } as const
  if (response.synthetic && options.mode !== 'synthetic') return { ok: false, reason: 'SYNTHETIC_ONLY' } as const
  if (!isCurrentResponse(response, context)) return { ok: false, reason: 'STALE_REVISION' } as const
  if (contextKeys.some((key) => proposal.resolvedContext[key] !== context[key]))
    return { ok: false, reason: 'CONTEXT_CHANGED' } as const
  const expiry = Date.parse(proposal.expiresAt)
  if (!Number.isFinite(options.now) || !Number.isFinite(expiry) || expiry <= options.now)
    return { ok: false, reason: 'EXPIRED' } as const
  const definition = findGuide(proposal.workflowId, proposal.guideVersion)
  if (!definition) return { ok: false, reason: 'UNREGISTERED_GUIDE' } as const
  if (definition.requiredSlots.some((slot) => context[slot] == null || context[slot] === ''))
    return { ok: false, reason: 'MISSING_REQUIRED_CONTEXT' } as const
  if (options.role && !definition.roles.some((role) => role === options.role))
    return { ok: false, reason: 'ROLE_DENIED' } as const
  if (options.mode !== 'synthetic' && !options.role) return { ok: false, reason: 'ROLE_REQUIRED' } as const
  return { ok: true, definition } as const
}

/** A visible calculation is not permission to apply its suggested amount. */
export function checkCalculationRecommendation(
  result: CalculationResult,
  context: AssistantContext,
  options: { mode?: 'server' | 'synthetic'; now: number },
) {
  if (result.synthetic && options.mode !== 'synthetic') return { ok: false, reason: 'SYNTHETIC_ONLY' } as const
  if (!isCurrentResponse(result, context)) return { ok: false, reason: 'STALE_REVISION' } as const
  const expiry = Date.parse(result.validity.expiresAt)
  if (!Number.isFinite(options.now) || !Number.isFinite(expiry) || expiry <= options.now)
    return { ok: false, reason: 'EXPIRED' } as const
  const scope = result.resolvedScope
  if (!scope || ['paymentId', 'contractId', 'settlementMonth', 'paymentStage'].some((key) => !(key in scope)))
    return { ok: false, reason: 'UNRESOLVED_SCOPE' } as const
  if ((result.mode === 'HYPOTHETICAL') !== (result.evidenceKind === 'HYPOTHETICAL'))
    return { ok: false, reason: 'EVIDENCE_KIND_MISMATCH' } as const
  if (scope && contextKeys.some((key) => key in scope && scope[key] !== context[key])) {
    return { ok: false, reason: 'CONTEXT_CHANGED' } as const
  }
  if (result.status !== 'CALCULATED' || !result.recommendation.allowed)
    return { ok: false, reason: 'NOT_RECOMMENDED' } as const
  if (!isAmountInput(result.recommendation.suggestedAmount) || !isWonAmount(result.recommendation.suggestedAmount)) {
    return { ok: false, reason: 'INVALID_AMOUNT' } as const
  }
  const target = result.recommendation.targetId
  if (target !== 'payment.amount' || !findStep('payment.draft', '1.0', target)) {
    return { ok: false, reason: 'UNREGISTERED_TARGET' } as const
  }
  return { ok: true, suggestedAmount: result.recommendation.suggestedAmount, targetId: target } as const
}
