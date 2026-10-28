import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { findGuide, findStep } from './catalog'
export type PicoReply = components['schemas']['PicoAskResponse']
export async function askPico(
  question: string,
  revision: string,
  screenId: string,
  signal: AbortSignal,
): Promise<PicoReply> {
  const response = await apiClient.request<PicoReply>('/api/v1/ai/pico/ask', {
    method: 'POST',
    signal,
    body: {
      question,
      screenId,
      entityRefs: [],
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
