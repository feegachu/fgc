export const MIN_COLUMN_WIDTH = 64
export const MAX_COLUMN_WIDTH = 1200

export function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

export function readLayout(key: string): unknown {
  try {
    return JSON.parse(localStorage.getItem(`fgc:layout:v1:${key}`) ?? 'null')
  } catch {
    return null
  }
}

export function saveLayout(key: string, value: unknown) {
  try {
    if (value === null) localStorage.removeItem(`fgc:layout:v1:${key}`)
    else localStorage.setItem(`fgc:layout:v1:${key}`, JSON.stringify(value))
  } catch {
    // Private browsing/storage restrictions must not prevent resizing.
  }
}
