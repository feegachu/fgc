import { defineConfig } from '@playwright/test'
import config from './playwright.config'

const origin = process.env.FGC_E2E_ORIGIN ?? 'http://localhost:15173'

export default defineConfig({
  ...config,
  testMatch: ['contract.spec.ts', 'contract-parity.spec.ts'],
  workers: 1,
  outputDir: './test-results/contract-server',
  use: { ...config.use, baseURL: `${origin}/app/` },
  webServer: {
    command: `node node_modules/vite/bin/vite.js --port ${new URL(origin).port} --strictPort`,
    url: `${origin}/app/`,
    reuseExistingServer: false,
  },
})
