import { GUIDE_ID } from './catalog'

export class GuideFailure extends Error {
  readonly reasonCode: string
  constructor(reasonCode: string) {
    super(reasonCode)
    this.reasonCode = reasonCode
  }
}

/** A screen owns its root and optional tab/panel opener. No URL or selector from a proposal. */
export interface GuideTargetRegistration {
  screenId: string
  guideVersion: string
  targetId: string
  root(): HTMLElement | null
  prepare?(signal: AbortSignal): Promise<void>
}

export class GuideTargets {
  private readonly registrations = new Map<string, GuideTargetRegistration>()
  private key(screen: string, version: string, target: string) {
    return `${screen}:${version}:${target}`
  }

  register(registration: GuideTargetRegistration): () => void {
    if (!GUIDE_ID.test(registration.targetId)) throw new GuideFailure('INVALID_TARGET_ID')
    const key = this.key(registration.screenId, registration.guideVersion, registration.targetId)
    if (this.registrations.has(key)) throw new GuideFailure('DUPLICATE_TARGET_REGISTRATION')
    const copy = { ...registration }
    this.registrations.set(key, copy)
    return () => {
      if (this.registrations.get(key) === copy) this.registrations.delete(key)
    }
  }

  has(screen: string, version: string, target: string): boolean {
    return this.registrations.has(this.key(screen, version, target))
  }

  private registration(screen: string, version: string, target: string) {
    const registration = this.registrations.get(this.key(screen, version, target))
    if (!registration) throw new GuideFailure('TARGET_VERSION_OR_REGISTRATION_MISSING')
    return registration
  }

  find(screen: string, version: string, target: string): HTMLElement | null {
    const root = this.registration(screen, version, target).root()
    if (!root?.isConnected) return null
    // target passed the ID grammar at registration, and selection is scoped to the active screen root.
    const nodes = [...root.querySelectorAll<HTMLElement>(`[data-guide-target="${target}"]`)]
    if (root.dataset.guideTarget === target) nodes.unshift(root)
    if (nodes.length > 1) throw new GuideFailure('DUPLICATE_TARGET')
    const node = nodes[0]
    if (!node || node.closest('[hidden], [inert], [aria-hidden="true"]')) return null
    const style = getComputedStyle(node)
    if (
      style.display === 'none' ||
      style.visibility === 'hidden' ||
      style.visibility === 'collapse' ||
      !node.getClientRects().length
    )
      return null
    return node
  }

  async prepare(screen: string, version: string, target: string, signal: AbortSignal): Promise<void> {
    await this.registration(screen, version, target).prepare?.(signal)
  }

  async wait(screen: string, version: string, target: string, signal: AbortSignal): Promise<HTMLElement> {
    while (!signal.aborted) {
      const element = this.find(screen, version, target)
      if (element) return element
      await new Promise<void>((resolve, reject) => {
        const abort = () => {
          clearTimeout(timer)
          reject(new GuideFailure('CANCELLED'))
        }
        const timer = setTimeout(() => {
          signal.removeEventListener('abort', abort)
          resolve()
        }, 50)
        signal.addEventListener('abort', abort, { once: true })
        if (signal.aborted) {
          signal.removeEventListener('abort', abort)
          abort()
        }
      })
    }
    throw new GuideFailure('CANCELLED')
  }
}
