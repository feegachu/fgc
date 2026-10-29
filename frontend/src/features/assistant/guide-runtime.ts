import { create } from 'zustand'
import { findGuide } from './catalog'
import type { TargetId, WorkflowId } from './contracts'

export type RuntimeStatus =
  | 'IDLE'
  | 'PREPARING'
  | 'SHOWING'
  | 'WAITING_INPUT'
  | 'WAITING_RESULT'
  | 'PAUSED'
  | 'BLOCKED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'ERROR'
export interface PaymentObservation {
  owner: string
  scopeKey: string
  revision: number
  canProcess: boolean
  ready: boolean
  amountValid: boolean
  attributionsValid: boolean
  formValid: boolean
  saved: boolean
  busy: boolean
  precheck: 'NONE' | 'PASSED_WITHOUT_TOKEN' | 'REJECTED'
  modalOpen: boolean
}
export interface LocalGuide {
  workflowId: WorkflowId
  index: number
  generation: number
  status: RuntimeStatus
  reason: string
  owner: string
  scopeKey: string
  revision: number
  acknowledgements: TargetId[]
  restartRequired: boolean
}
interface RuntimeStore {
  observation: PaymentObservation | null
  session: LocalGuide | null
  publish: (value: PaymentObservation) => void
  detach: (owner: string) => void
  start: (workflowId: WorkflowId) => boolean
  next: () => void
  previous: () => void
  pause: (reason?: string, restartRequired?: boolean) => void
  resume: () => void
  stop: () => void
  reset: () => void
  targetReady: () => void
  fail: () => void
}
export function completion(session: LocalGuide, observation: PaymentObservation): { ok: boolean; reason: string } {
  const step = findGuide(session.workflowId, '1.0')?.steps[session.index]
  if (!step || !observation.ready) return { ok: false, reason: '화면의 기준정보를 먼저 확인하세요.' }
  if (observation.busy) return { ok: false, reason: '실제 업무 요청 결과를 기다리고 있어요.' }
  switch (step.completionRuleId) {
    case 'SCOPE_ACKNOWLEDGED':
      return { ok: true, reason: '월·지급단계·수취인과 귀속 계약을 직접 확인한 뒤 다음을 누르세요.' }
    case 'AMOUNT_REVIEW_ACKNOWLEDGED':
      return {
        ok: observation.amountValid,
        reason: observation.amountValid
          ? '지급액과 근거를 확인한 뒤 다음을 누르세요.'
          : '금액은 0 이상의 원 단위 정수로 입력하세요.',
      }
    case 'ATTRIBUTIONS_REVIEWED':
      return {
        ok: observation.attributionsValid,
        reason: observation.attributionsValid
          ? '계약별 귀속과 배부 근거를 확인한 뒤 다음을 누르세요.'
          : '귀속행의 계약·날짜·금액·배부 근거를 확인하세요.',
      }
    case 'EVIDENCE_FIELDS_VALID':
      return {
        ok: observation.formValid,
        reason: observation.formValid
          ? '입력과 근거를 직접 확인한 뒤 다음을 누르세요.'
          : '필수 입력·귀속·증빙과 기준정보 선택을 먼저 확인하세요.',
      }
    case 'CURRENT_DRAFT_SAVED':
      return {
        ok: observation.saved && observation.formValid,
        reason:
          observation.saved && observation.formValid
            ? '현재 입력과 일치하는 DRAFT 응답을 확인했어요.'
            : '기존 저장 버튼을 직접 눌러 성공 응답을 확인하세요.',
      }
    case 'CURRENT_PRECHECK_PASSED':
      return {
        ok: false,
        reason:
          observation.precheck === 'REJECTED'
            ? '서버가 검증을 차단했습니다. 기존 결과에서 사유를 확인하세요.'
            : observation.precheck === 'PASSED_WITHOUT_TOKEN'
              ? '현재 저장본의 검증 증표가 없는 API입니다. 가이드 통과 판정은 서버 연계 후 지원합니다.'
              : '기존 사전검증 버튼으로 결과를 확인하세요. 가이드는 검증을 실행하지 않아요.',
      }
    case 'PRECHECK_RESULT_ACKNOWLEDGED':
    case 'PAYMENT_CONFIRMED':
      return { ok: false, reason: '현재 저장본의 서버 검증 증표·확정 상태 연계가 필요합니다.' }
  }
}
export const useGuideRuntime = create<RuntimeStore>((set, get) => ({
  observation: null,
  session: null,
  publish: (value) =>
    set((state) => {
      const old = state.session
      if (!old || ['COMPLETED', 'CANCELLED'].includes(old.status)) return { observation: value }
      if (
        old.owner !== value.owner ||
        old.scopeKey !== value.scopeKey ||
        (!value.canProcess && findGuide(old.workflowId, '1.0')?.capability === 'PROCESS')
      )
        return {
          observation: value,
          session: {
            ...old,
            status: 'PAUSED',
            reason: '화면·지급 범위 또는 권한이 변경됐어요. 현재 대상에서 도움말을 새로 시작하세요.',
            acknowledgements: [],
            restartRequired: true,
          },
        }
      if (value.modalOpen && findGuide(old.workflowId, '1.0')?.steps[old.index]?.targetId !== 'payment.confirm')
        return {
          observation: value,
          session: {
            ...old,
            status: 'PAUSED',
            reason: '확정 확인 창이 열려 뒤쪽 안내를 멈췄어요. 창을 닫고 복귀하세요.',
          },
        }
      if (old.revision !== value.revision)
        return {
          observation: value,
          session: {
            ...old,
            index: 0,
            revision: value.revision,
            status: old.status === 'PAUSED' ? 'PAUSED' : 'PREPARING',
            acknowledgements: [],
            reason: '입력이 변경되어 이전 확인·저장·검증 근거를 다시 확인합니다.',
          },
        }
      return { observation: value }
    }),
  detach: (owner) =>
    set((state) =>
      state.observation?.owner !== owner
        ? {}
        : {
            observation: null,
            session:
              state.session && !['CANCELLED', 'COMPLETED'].includes(state.session.status)
                ? {
                    ...state.session,
                    status: 'PAUSED',
                    reason: '지급 화면을 벗어났어요. 자동 이동하지 않습니다. 화면으로 돌아와 다시 시작하세요.',
                    restartRequired: true,
                  }
                : state.session,
          },
    ),
  start: (workflowId) => {
    const observation = get().observation,
      guide = findGuide(workflowId, '1.0')
    if (!observation || !observation.ready || !guide || (!observation.canProcess && guide.capability === 'PROCESS'))
      return false
    set({
      session: {
        workflowId,
        index: 0,
        generation: (get().session?.generation ?? 0) + 1,
        status: 'PREPARING',
        reason: '',
        owner: observation.owner,
        scopeKey: observation.scopeKey,
        revision: observation.revision,
        acknowledgements: [],
        restartRequired: false,
      },
    })
    return true
  },
  next: () => {
    const { session, observation } = get()
    if (!session || !observation || !['SHOWING', 'WAITING_INPUT', 'WAITING_RESULT', 'BLOCKED'].includes(session.status))
      return
    const guide = findGuide(session.workflowId, '1.0')!
    const checked = completion(session, observation)
    if (!checked.ok) {
      set({
        session: {
          ...session,
          status:
            guide.steps[session.index].targetId === 'payment.precheck' && observation.precheck !== 'NONE'
              ? 'BLOCKED'
              : observation.busy
                ? 'WAITING_RESULT'
                : 'WAITING_INPUT',
          reason: checked.reason,
        },
      })
      return
    }
    const current = guide.steps[session.index]
    const edge = current.next.find((item) => item.conditionId === current.completionRuleId)
    const nextIndex = edge ? guide.steps.findIndex((item) => item.stepId === edge.stepId) : -1
    if (edge && nextIndex < 0) {
      get().fail()
      return
    }
    const last = !edge
    set({
      session: {
        ...session,
        index: last ? session.index : nextIndex,
        status: last ? 'COMPLETED' : 'PREPARING',
        reason: last ? '등록된 도움말 단계를 확인했어요. 실제 지급 확정 완료를 뜻하지 않습니다.' : '',
        acknowledgements: [...new Set([...session.acknowledgements, guide.steps[session.index].targetId])],
      },
    })
  },
  previous: () =>
    set((state) =>
      !state.session || ['COMPLETED', 'CANCELLED', 'PAUSED', 'ERROR'].includes(state.session.status)
        ? {}
        : {
            session: { ...state.session, index: Math.max(0, state.session.index - 1), status: 'PREPARING', reason: '' },
          },
    ),
  pause: (reason = '안내를 잠시 멈췄어요. 입력 내용은 그대로 유지됩니다.', restartRequired = false) =>
    set((state) =>
      !state.session || ['COMPLETED', 'CANCELLED'].includes(state.session.status)
        ? {}
        : {
            session: {
              ...state.session,
              status: 'PAUSED',
              reason,
              restartRequired: state.session.restartRequired || restartRequired,
            },
          },
    ),
  resume: () => {
    const { session, observation } = get()
    if (
      !session ||
      !['PAUSED', 'ERROR'].includes(session.status) ||
      session.restartRequired ||
      !observation ||
      session.owner !== observation.owner ||
      session.scopeKey !== observation.scopeKey ||
      (observation.modalOpen &&
        findGuide(session.workflowId, '1.0')?.steps[session.index]?.targetId !== 'payment.confirm')
    )
      return
    set({ session: { ...session, status: 'PREPARING', reason: '' } })
  },
  stop: () =>
    set((state) => ({
      session: state.session
        ? {
            ...state.session,
            status: 'CANCELLED',
            reason: '안내를 종료했어요. 업무 입력과 저장 상태는 바꾸지 않았습니다.',
          }
        : null,
    })),
  reset: () => set({ session: null }),
  fail: () =>
    set((state) => ({
      session: state.session
        ? {
            ...state.session,
            status: 'ERROR',
            reason: '화면 위치를 확인하지 못했어요. 화면을 확인하고 다시 시도하세요.',
          }
        : null,
    })),
  targetReady: () =>
    set((state) => (state.session?.status === 'PREPARING' ? { session: { ...state.session, status: 'SHOWING' } } : {})),
}))

/** No URLs or selectors are accepted from questions or model output. */
export const PAYMENT_TARGETS: readonly TargetId[] = Object.freeze([
  'payment.scope',
  'payment.amount',
  'payment.attributions',
  'payment.evidence',
  'payment.save',
  'payment.precheck',
  'payment.precheck.result',
  'payment.confirm',
  'payment.confirm.dialog',
])
export function resolvePaymentTarget(
  root: ParentNode,
  target: TargetId,
): { element: HTMLElement | null; error: string | null } {
  if (!PAYMENT_TARGETS.includes(target)) return { element: null, error: '등록되지 않은 안내 대상입니다.' }
  const roots = [...root.querySelectorAll<HTMLElement>('[data-guide-screen="TRAN-W02"]')]
  if (roots.length !== 1)
    return { element: null, error: roots.length > 1 ? '지급 화면이 중복되어 안내를 중지했어요.' : null }
  if (roots[0].dataset.guideVersion !== '1.0') return { element: null, error: '화면과 가이드 버전이 다릅니다.' }
  const elements = [...root.querySelectorAll<HTMLElement>(`[data-guide-target="${target}"]`)]
  if (elements.length > 1) return { element: null, error: '안내 대상이 중복되어 중지했어요.' }
  const element = elements[0]
  const modalTarget = target === 'payment.confirm.dialog'
  if (!element || (!modalTarget && !roots[0].contains(element))) return { element: null, error: null }
  const style = getComputedStyle(element)
  if (
    element.closest('[hidden], [inert], [aria-hidden="true"]') ||
    style.display === 'none' ||
    style.visibility === 'hidden' ||
    style.opacity === '0' ||
    !element.getClientRects().length
  )
    return { element: null, error: null }
  return { element, error: null }
}

/** Local execution events only; no question, form text, financial payload or personal data. */
export interface GuideRuntimeEvent {
  kind: 'STARTED' | 'STEP_CHANGED' | 'PAUSED' | 'FINISHED' | 'STOPPED' | 'FAILED'
  workflowId: WorkflowId
  stepId: TargetId | null
  reasonCode: string | null
}
export function subscribeGuideEvents(listener: (event: GuideRuntimeEvent) => void) {
  return useGuideRuntime.subscribe((state, previous) => {
    const current = state.session,
      old = previous.session
    if (!current) {
      if (old) listener({ kind: 'STOPPED', workflowId: old.workflowId, stepId: null, reasonCode: 'LOCAL_STOP' })
      return
    }
    const started = current.generation !== old?.generation
    const changed = current.index !== old?.index
    const kind = started
      ? 'STARTED'
      : current.status === old?.status && !changed
        ? null
        : current.status === 'PAUSED'
          ? 'PAUSED'
          : current.status === 'ERROR'
            ? 'FAILED'
            : current.status === 'COMPLETED'
              ? 'FINISHED'
              : current.status === 'CANCELLED'
                ? 'STOPPED'
                : changed
                  ? 'STEP_CHANGED'
                  : null
    if (!kind) return
    listener({
      kind,
      workflowId: current.workflowId,
      stepId: findGuide(current.workflowId, '1.0')?.steps[current.index]?.stepId ?? null,
      reasonCode: ['PAUSED', 'ERROR'].includes(current.status) ? `LOCAL_${current.status}` : null,
    })
  })
}
