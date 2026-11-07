/** Only allow internal checklist destinations, including for server-provided links. */
export function safeInternalLink(linkUrl?: string | null) {
  if (!linkUrl || !linkUrl.startsWith('/') || linkUrl.startsWith('//') || linkUrl.includes('\\')) return null
  return linkUrl
}
