import { pathToFileURL } from 'node:url'

export async function waitForBackend(url, timeout = 180_000, alive = () => true) {
  const deadline = Date.now() + timeout
  let cause = 'No response'
  while (Date.now() < deadline && alive()) {
    try {
      const response = await fetch(url, {
        signal: AbortSignal.timeout(Math.min(1500, Math.max(1, deadline - Date.now()))),
      })
      if (response.ok) return
      cause = `HTTP ${response.status}`
    } catch (error) {
      cause = error.cause?.code ?? error.message
    }
    await new Promise((resolve) => setTimeout(resolve, Math.min(500, Math.max(1, deadline - Date.now()))))
  }
  throw new Error(
    `FGC E2E backend readiness failed: ${url} within ${timeout}ms (${cause}). Check e2e-artifacts/backend.log, Docker/PostgreSQL and Flyway; backend may not have started.`,
  )
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await waitForBackend(process.argv[2] ?? 'http://127.0.0.1:18081/actuator/health', Number(process.argv[3] ?? 180000))
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
