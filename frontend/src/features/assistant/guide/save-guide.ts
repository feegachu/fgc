/** Per-editor, memory-only bridge. Only the existing save handler may report server success. */
export class SaveGuide {
  private listeners = new Set<() => void>()
  private state = { valid: false, pending: false, savedId: null as number | string | null, error: '' }
  private input: unknown
  private revision = 0
  private pendingRevision = 0
  private active = false
  private acknowledge: ((continueNavigation: boolean) => void) | null = null
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(patch: Partial<typeof this.state>) {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((listener) => listener())
  }
  update(input: unknown, valid: boolean) {
    const changed = input !== this.input
    this.input = input
    if (changed) this.revision++
    if (changed || valid !== this.state.valid) this.publish({ valid, ...(changed ? { savedId: null, error: '' } : {}) })
  }
  isActive() { return this.active }
  activate() { this.active = true }
  deactivate(continueNavigation = true) {
    this.active = false
    this.acknowledge?.(continueNavigation)
    this.acknowledge = null
  }
  begin() { this.pendingRevision = this.revision; this.publish({ pending: true, savedId: null, error: '' }) }
  fail(error: string) { this.publish({ pending: false, savedId: null, error }) }
  async saved(id: number | string): Promise<boolean> {
    if (this.pendingRevision !== this.revision) throw new Error('처리 중 입력 또는 선택 대상이 바뀌었습니다. 저장 결과를 다시 조회하세요.')
    if (!Number.isSafeInteger(Number(id)) || Number(id) <= 0) throw new Error('저장 결과를 확인하지 못했습니다.')
    const acknowledged = this.active ? new Promise<boolean>((resolve) => { this.acknowledge = resolve }) : Promise.resolve(true)
    this.publish({ pending: false, savedId: id, error: '' })
    return acknowledged
  }
}
