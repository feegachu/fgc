/** Read-only bridge; payment operations remain in the editor. */
export interface BatchPaymentGuideState {
  contractId: number
  stage: 'GA_TO_FC' | 'INSURER_TO_GA'
  month: string
  paymentIds: number[]
  ready: boolean
}
let read: (() => BatchPaymentGuideState) | undefined
export const readBatchPaymentGuide = () => read?.()
export function connectBatchPaymentGuide(reader: () => BatchPaymentGuideState) {
  read = reader
  return () => { if (read === reader) read = undefined }
}
