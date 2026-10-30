import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { findGuide, findStep } from './catalog'
import { isEntityId } from './guards'
import { findWorkflowGuide, workflowHelpSteps } from './workflow-catalog'
export type PicoReply = components['schemas']['PicoAskResponse']
export type PicoTurn = { role: 'USER' | 'ASSISTANT'; text: string }
export function picoPaymentReference(paymentId?: string | null) {
  return paymentId && isEntityId(paymentId) && BigInt(paymentId) <= BigInt(Number.MAX_SAFE_INTEGER)
    ? { type: 'PAYMENT' as const, id: Number(paymentId) }
    : null
}
export async function askPico(
  question: string,
  revision: string,
  screenId: string,
  signal: AbortSignal,
  paymentId?: string | null,
  conversation: readonly PicoTurn[] = [],
): Promise<PicoReply> {
  const paymentRef = picoPaymentReference(paymentId)
  const response = await apiClient.request<PicoReply>('/api/v1/ai/pico/ask', {
    method: 'POST',
    signal,
    body: {
      question,
      screenId,
      entityRefs: paymentRef ? [paymentRef] : [],
      clientContextRevision: revision,
      guideVersion: '1.0',
      conversation: conversation.slice(-6).map((turn) => ({ ...turn, text: turn.text.slice(0, 2000) })),
    },
  })
  const data = response.data
  if (!data || typeof data.contextRevision !== 'string' || typeof data.guideVersion !== 'string') throw new Error('INVALID_PICO_REPLY')
  if (data.guideVersion !== '1.0') throw new Error('PICO_GUIDE_VERSION_MISMATCH')
  if (data.contextRevision !== revision) throw new Error('STALE_PICO_CONTEXT')
  if (
    data.validatedGuide != null ||
    !['AI', 'FIXED'].includes(data.source ?? '') ||
    !['ANSWER_ONLY', 'ASK_CLARIFICATION', 'UNSUPPORTED'].includes(data.decision ?? '') ||
    typeof data.message !== 'string' ||
    !data.message.trim() ||
    data.message.length > 2000 ||
    !Array.isArray(data.evidenceRefs)
  )
    throw new Error('INVALID_PICO_REPLY')
  for (const ref of data.evidenceRefs) {
    const workflow = ref.workflowId ? findWorkflowGuide(ref.workflowId, '1.0') : null
    if (
      !ref.workflowId ||
      (!findGuide(ref.workflowId, '1.0') && !workflow) ||
      (ref.stepId != null && !findStep(ref.workflowId, '1.0', ref.stepId) && !workflow?.steps.some((step) => step.stepId === ref.stepId) && !workflowHelpSteps(ref.workflowId).some((step) => step.stepId === ref.stepId)) ||
      ref.chunkId !== `${ref.workflowId}:${ref.stepId ?? 'overview'}`
    )
      throw new Error('INVALID_PICO_EVIDENCE')
  }
  if (data.source === 'AI' && data.decision === 'ANSWER_ONLY' && !data.evidenceRefs.length)
    throw new Error('MISSING_PICO_EVIDENCE')
  return data
}
