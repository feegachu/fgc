import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { findGuide } from './catalog'

type GuideRequest = components['schemas']['PicoGuideRequest']
type GuideResponse = components['schemas']['PicoGuideResponse']
export async function startPicoGuide(request: Omit<GuideRequest, 'prepareToken'>, signal: AbortSignal) {
  async function call(action: 'prepare' | 'start', token?: string): Promise<GuideResponse> {
    if (signal.aborted) throw new Error('STALE_GUIDE_CONTEXT')
    const { data } = await apiClient.request<GuideResponse>(`/api/v1/ai/pico/guides/${action}`, {
      method: 'POST',
      signal,
      body: { ...request, prepareToken: token ?? null },
    })
    if (
      !data ||
      data.status !== (action === 'prepare' ? 'PREPARED' : 'STARTED') ||
      data.workflowId !== request.workflowId ||
      data.guideVersion !== request.guideVersion ||
      data.paymentId !== request.paymentId ||
      data.clientContextRevision !== request.clientContextRevision ||
      data.stepId !== 'payment.scope' ||
      !findGuide(data.workflowId!, '1.0') ||
      !Number.isFinite(Date.parse(data.expiresAt ?? '')) ||
      (action === 'prepare' ? !/^[A-Za-z0-9_-]{43}$/.test(data.prepareToken ?? '') : data.prepareToken != null)
    )
      throw new Error('INVALID_GUIDE_START')
    return data
  }
  const prepared = await call('prepare')
  return call('start', prepared.prepareToken!)
}
