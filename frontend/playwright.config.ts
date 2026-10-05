import { defineConfig, devices } from '@playwright/test'

// 설치·설정만 둔다. 시나리오와 CI 연결은 #418.
// 처음 한 번: npx playwright install chromium
export default defineConfig({
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:5173/app/',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173/app/',
    reuseExistingServer: true,
  },
})
