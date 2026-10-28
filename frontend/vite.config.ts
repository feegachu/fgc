/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 로컬 bootRun 포트는 application.yml 의 server.port(8081)다. 다른 포트로 띄웠으면 FGC_API_TARGET 으로 덮어쓴다.
const apiTarget = process.env.FGC_API_TARGET ?? 'http://localhost:8081'

// https://vite.dev/config/
export default defineConfig({
  // 웨이브 C(#419): SPA와 브라우저 테스트 모두 루트 경로에서 제공한다.
  base: '/',
  plugins: [
    react(),
    {
      name: 'policy-ui-preview',
      apply: 'serve',
      configureServer(server) {
        server.middlewares.use(async (req, res, next) => {
          if (req.url?.split('?')[0] !== '/policy-preview') return next()
          try {
            const html = await server.transformIndexHtml(
              '/policy-preview',
              '<!doctype html><html lang="ko"><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1.0"/><title>정책 UI 미리보기 · FGC</title></head><body><div id="root"></div><script type="module" src="/src/previews/policyPreview.tsx"></script></body></html>',
            )
            res.setHeader('Content-Type', 'text/html; charset=utf-8')
            res.end(html)
          } catch (error) {
            next(error)
          }
        })
      },
    },
  ],
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
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/lib/api/schema.d.ts', 'src/main.tsx'],
    },
  },
})
