import type { ScreenArea } from './screen-overviews'

export interface ScreenSnapshot {
  content: HTMLElement
  width: number
  height: number
  areas: (ScreenArea & { x: number; y: number })[]
  scroll: { element: Element; top: number; left: number }[]
}

// Freeze layout and typography, rather than enumerating hundreds of computed
// properties per node. The copied stylesheets supply the remaining decoration.
const capturedProperties = [
  'display', 'position', 'inset', 'z-index', 'box-sizing',
  'width', 'height', 'min-width', 'max-width', 'min-height', 'max-height',
  'margin', 'padding', 'border-top', 'border-right', 'border-bottom', 'border-left', 'border-radius',
  'flex', 'flex-direction', 'flex-wrap', 'order', 'align-items', 'align-self', 'align-content',
  'justify-content', 'justify-items', 'justify-self', 'gap',
  'grid-template-columns', 'grid-template-rows', 'grid-column', 'grid-row',
  'grid-auto-flow', 'grid-auto-columns', 'grid-auto-rows',
  'overflow', 'overflow-wrap', 'white-space', 'word-break', 'text-overflow',
  'font-family', 'font-size', 'font-weight', 'font-style', 'line-height', 'letter-spacing',
  'text-align', 'text-transform', 'vertical-align', 'color', 'background',
  'opacity', 'visibility', 'box-shadow', 'transform', 'transform-origin',
  'object-fit', 'aspect-ratio', 'table-layout', 'border-collapse', 'border-spacing',
]

/** Copy the displayed UI into an inert, isolated preview. No business handlers are copied. */
export async function snapshotScreen(root: HTMLElement, areas: ScreenArea[], signal: AbortSignal): Promise<ScreenSnapshot> {
  signal.throwIfAborted()
  const bounds = root.getBoundingClientRect()
  const width = Math.max(1, bounds.width)
  const height = Math.max(1, bounds.height)
  const visibleAreas = areas.flatMap(area => {
    const element = root.matches(area.selector) ? root : root.querySelector<HTMLElement>(area.selector)
    if (!element || !element.getClientRects().length || element.closest('[hidden], [aria-hidden="true"]')) return []
    const style = getComputedStyle(element)
    if (style.visibility === 'hidden' || style.display === 'none') return []
    const box = element.getBoundingClientRect()
    let left = Math.max(bounds.left, box.left), top = Math.max(bounds.top, box.top)
    let right = Math.min(bounds.right, box.right), bottom = Math.min(bounds.bottom, box.bottom)
    for (let parent = element.parentElement; parent && root.contains(parent); parent = parent.parentElement) {
      const parentStyle = getComputedStyle(parent)
      const clip = parent.getBoundingClientRect()
      if (/(auto|scroll|hidden|clip)/.test(parentStyle.overflowY)) {
        top = Math.max(top, clip.top); bottom = Math.min(bottom, clip.bottom)
      }
      if (/(auto|scroll|hidden|clip)/.test(parentStyle.overflowX)) {
        left = Math.max(left, clip.left); right = Math.min(right, clip.right)
      }
    }
    return right > left && bottom > top ? [{ ...area, x: left - bounds.left + 12, y: top - bounds.top + 12 }] : []
  })
  const clone = root.cloneNode(true) as HTMLElement
  const originals = [root, ...root.querySelectorAll('*')]
  const copies = [clone, ...clone.querySelectorAll('*')]
  const scroll: ScreenSnapshot['scroll'] = []
  let batchStart = performance.now()
  for (let index = 0; index < originals.length; index++) {
    const original = originals[index]
    const copy = copies[index]
    for (const attribute of [...copy.attributes]) {
      if (attribute.name.startsWith('on') || attribute.name === 'autofocus') copy.removeAttribute(attribute.name)
    }
    if (copy instanceof HTMLElement || copy instanceof SVGElement) {
      const computedStyle = getComputedStyle(original)
      // Preserve inline chart styles and custom properties already on the clone.
      copy.style.cssText += ';' + capturedProperties
        .map(property => `${property}:${computedStyle.getPropertyValue(property)}!important`).join(';')
      copy.style.setProperty('animation', 'none', 'important')
      copy.style.setProperty('transition', 'none', 'important')
    }
    if (original instanceof HTMLInputElement && copy instanceof HTMLInputElement) {
      if (original.type !== 'file') copy.value = original.type === 'password' ? '' : original.value
      copy.checked = original.checked
    }
    if (original instanceof HTMLTextAreaElement && copy instanceof HTMLTextAreaElement) copy.value = original.value
    if (original instanceof HTMLSelectElement && copy instanceof HTMLSelectElement) copy.value = original.value
    if (original.scrollTop || original.scrollLeft) scroll.push({ element: copy, top: original.scrollTop, left: original.scrollLeft })
    if (performance.now() - batchStart >= 8) {
      // Let the modal paint and accept Escape/close while long tables are copied.
      await new Promise<void>(resolve => window.setTimeout(resolve, 0))
      signal.throwIfAborted()
      batchStart = performance.now()
    }
  }
  clone.querySelectorAll('script, iframe, object, embed, .payment-guide-card, .payment-guide-spotlight').forEach(node => node.remove())
  clone.style.setProperty('width', `${width}px`, 'important')
  clone.style.setProperty('max-width', 'none', 'important')
  clone.style.setProperty('margin', '0', 'important')
  let content = clone
  // Keep ancestor selectors and inherited theme variables inside the shadow root.
  for (let parent = root.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
    const wrapper = parent.cloneNode(false) as HTMLElement
    wrapper.removeAttribute('id')
    wrapper.style.cssText = `display:block!important;position:static!important;width:${width}px!important;height:auto!important;min-height:0!important;margin:0!important;padding:0!important;overflow:visible!important;transform:none!important`
    wrapper.append(content)
    content = wrapper
  }
  const computed = getComputedStyle(root)
  for (const property of Array.from(computed)) {
    if (property.startsWith('--')) content.style.setProperty(property, computed.getPropertyValue(property))
  }
  content.inert = true
  return { content, width, height, areas: visibleAreas, scroll }
}
