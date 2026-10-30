import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { findGuide, findStep } from './catalog'
import { isEntityId } from './guards'
export type PicoReply = components['schemas']['PicoAskResponse']
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
      conversation: [],
    },
  })
  const data = response.data
  if (
    !data ||
    data.contextRevision !== revision ||
    data.guideVersion !== '1.0' ||
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
    if (
      !ref.workflowId ||
      !findGuide(ref.workflowId, '1.0') ||
      (ref.stepId != null && !findStep(ref.workflowId, '1.0', ref.stepId)) ||
      ref.chunkId !== `${ref.workflowId}:${ref.stepId ?? 'overview'}`
    )
      throw new Error('INVALID_PICO_EVIDENCE')
  }
  if (data.source === 'AI' && data.decision === 'ANSWER_ONLY' && !data.evidenceRefs.length)
    throw new Error('MISSING_PICO_EVIDENCE')
  return data
}
