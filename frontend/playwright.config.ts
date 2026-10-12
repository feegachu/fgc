import { defineConfig, devices } from '@playwright/test'

const origin = process.env.FGC_E2E_ORIGIN ?? 'http://localhost:5173'

// #403 shell scenarios use API fixtures; domain/real-server scenarios belong to #418.
export default defineConfig({
  testDir: './e2e',
  use: {
    baseURL: `${origin}/app/`,
    trace: 'on-first-retry',
    launchOptions: process.env.FGC_BROWSER_EXECUTABLE ? { executablePath: process.env.FGC_BROWSER_EXECUTABLE } : {},
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- --port ${new URL(origin).port || '5173'}`,
    url: `${origin}/app/`,
    reuseExistingServer: true,
  },
})
