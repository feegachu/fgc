import { defineConfig, devices } from '@playwright/test'

// Real HTTP/DB scenarios only. API-fixture tests remain in playwright.config.ts.
export default defineConfig({
  testDir: './e2e-demo',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  globalSetup: './e2e-demo/setup.ts',
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report/demo', open: 'never' }],
    ['junit', { outputFile: 'e2e-artifacts/demo.xml' }],
  ],
  use: {
    baseURL: 'http://127.0.0.1:15173/',
    viewport: { width: 1440, height: 1000 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    video: 'retain-on-failure',
    launchOptions: process.env.FGC_BROWSER_EXECUTABLE ? { executablePath: process.env.FGC_BROWSER_EXECUTABLE } : {},
  },
  projects: [{ name: 'demo-chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'node scripts/e2e-server.mjs',
      url: 'http://127.0.0.1:18081/actuator/health',
      timeout: 200_000,
      reuseExistingServer: false,
      stderr: 'pipe',
    },
    {
      command: 'npm run dev -- --host 127.0.0.1 --port 15173 --strictPort',
      url: 'http://127.0.0.1:15173/',
      env: { FGC_API_TARGET: 'http://127.0.0.1:18081' },
      reuseExistingServer: false,
    },
  ],
})
