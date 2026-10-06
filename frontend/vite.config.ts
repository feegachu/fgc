/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 로컬 bootRun 포트는 application.yml 의 server.port(8081)다. 다른 포트로 띄웠으면 FGC_API_TARGET 으로 덮어쓴다.
const apiTarget = process.env.FGC_API_TARGET ?? 'http://localhost:8081'

// https://vite.dev/config/
export default defineConfig({
  // 공존기: nginx 가 /app/ 에서 서빙한다. 웨이브 C(#419)에서 '/' 로 바꾼다.
  base: '/app/',
  plugins: [react()],
  server: {
    proxy: {
      // 문자열로 쓰면 Vite 가 changeOrigin: true 를 켜 Host 를 백엔드 주소로 바꾼다 → 토큰 API 출처 검사(FGC-AUTH-003)에 걸린다.
      '/api': { target: apiTarget },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
