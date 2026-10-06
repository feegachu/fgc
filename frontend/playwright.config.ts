import { defineConfig, devices } from '@playwright/test'

// #403 shell scenarios use API fixtures; domain/real-server scenarios belong to #418.
export default defineConfig({
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:5173/app/',
    trace: 'on-first-retry',
    launchOptions: process.env.FGC_BROWSER_EXECUTABLE ? { executablePath: process.env.FGC_BROWSER_EXECUTABLE } : {},
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173/app/',
    reuseExistingServer: true,
  },
})
