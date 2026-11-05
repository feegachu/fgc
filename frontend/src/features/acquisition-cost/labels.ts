import { rate, won } from '../../lib/format'
import type { AcquisitionCheck } from './api'

/** 금액 칸 공통. null은 자료 없음이고 실제 0만 0원이다. */
export const amountText = (value?: number | null) => (value == null ? '자료 없음' : won(value))
export const percentText = (value?: number | null) => (value == null ? '자료 없음' : `${rate(value)}%`)
export const policyText = (policy?: AcquisitionCheck['limitPolicy']) =>
  policy ? `${policy.policyCode} v${policy.versionNo}` : '자료 없음'
