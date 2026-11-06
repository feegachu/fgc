import { useEffect, useState } from 'react'
import type { ContractPage } from '../../contract/api'
import { guideGet } from './api'

const PREFIX = 'FGC-DEMO-'

/** Read all matching pages; lexical ordering does not put 10 after 9. */
async function nextDemoNumber(signal: AbortSignal) {
  let maximum = 0n
  let width = 1
  for (let page = 1; ; page++) {
    const result = await guideGet<ContractPage>(`/contracts?contractNo=${PREFIX}&page=${page}&size=100`, signal)
    signal.throwIfAborted()
    if (!result.content || result.totalPages == null) throw new Error('기존 계약번호를 확인하지 못했습니다. 다시 시도해 주세요.')
    for (const row of result.content) {
      const match = /^FGC-DEMO-(\d+)$/.exec(row.contractNo ?? '')
      if (!match) continue
      const number = BigInt(match[1])
      if (number > maximum) { maximum = number; width = match[1].length }
      else if (number === maximum) width = Math.max(width, match[1].length)
    }
    if (page >= result.totalPages) break
  }
  const value = PREFIX + (maximum + 1n).toString().padStart(width, '0')
  if (value.length > 80) throw new Error('계약번호 길이를 확인해 주세요. 자동 번호를 만들 수 없습니다.')
  return value
}

/** Guide-only draft input. Saving and uniqueness validation remain owned by the contract form/server. */
export function useDemoContractNumber(target: HTMLElement | null, enabled: boolean) {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<{ target: HTMLElement; attempt: number; pending: boolean; error: string }>()
  useEffect(() => {
    if (!enabled || !(target instanceof HTMLInputElement) || target.readOnly || target.disabled || target.value.trim()) return
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10000)])
    // Never overwrite an edit made while the lookup is running, even if it was cleared again.
    const edited = () => { controller.abort(); setState({ target, attempt, pending: false, error: '' }) }
    target.addEventListener('input', edited)
    void nextDemoNumber(signal).then(value => {
      signal.throwIfAborted()
      if (!target.isConnected || target.value.trim() || target.disabled || target.readOnly) return
      target.removeEventListener('input', edited)
      // Same native-control bridge as GuideFieldPicker: React owns the draft and validation.
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(target, value)
      target.dispatchEvent(new Event('input', { bubbles: true }))
      target.dispatchEvent(new Event('change', { bubbles: true }))
      setState({ target, attempt, pending: false, error: '' })
    }).catch(failure => {
      if (!controller.signal.aborted) setState({ target, attempt, pending: false,
        error: failure instanceof Error && failure.name !== 'TimeoutError' ? failure.message : '번호 조회가 지연되고 있습니다. 다시 시도해 주세요.' })
    })
    return () => { controller.abort(); target.removeEventListener('input', edited) }
  }, [target, enabled, attempt])
  const current = enabled && state?.target === target && state.attempt === attempt ? state : undefined
  const awaitingNumber = enabled && target instanceof HTMLInputElement && !target.readOnly && !target.disabled && !target.value.trim()
  return { pending: current?.pending ?? awaitingNumber, error: current?.error ?? '', retry: () => setAttempt(value => value + 1) }
}
