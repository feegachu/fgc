import { apiClient } from './api/client'
import type { RequestOptions } from './api/client'
import { notifyApiError } from './api/errorNotifications'

export function csvFilename(disposition: string | null, fallback = 'export.csv'): string {
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition ?? '')
  const plain = /filename\s*=\s*(?:"([^"]*)"|([^;]*))/i.exec(disposition ?? '')
  let name = plain?.[1] ?? plain?.[2]?.trim() ?? fallback
  if (encoded) {
    try { name = decodeURIComponent(encoded[1].trim()) } catch { /* Use the plain filename on malformed encoding. */ }
  }
  // Content-Disposition supplies a filename, never a local path.
  return [...name].map((char) => char === '/' || char === '\\' || char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127 ? '_' : char).join('') || fallback
}
export async function exportCsv(path: string, options: RequestOptions & { filename?: string } = {}) {
  try {
    const { blob, disposition } = await apiClient.requestBlob(path, options)
    const filename = csvFilename(disposition, options.filename)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    try {
      anchor.href = url
      anchor.download = filename
      document.body.append(anchor)
      anchor.click()
    } finally {
      anchor.remove()
      // Let the browser consume the click before releasing the blob URL.
      setTimeout(() => URL.revokeObjectURL(url), 0)
    }
    return filename
  } catch (error) {
    notifyApiError(error)
    throw error
  }
}
