import { fileURLToPath } from 'node:url'

import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    // 기본은 node 다. DOM 이 필요한 컴포넌트 테스트는 그 파일 맨 위에
    // `// @vitest-environment jsdom` 을 적어 파일 단위로 켠다 (docs/conventions.md "테스트").
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}', 'app/**/*.test.{ts,tsx}', 'scripts/**/*.test.mjs'],
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
  },
  resolve: {
    alias: {
      // tsconfig.json 의 paths 와 값이 같아야 한다
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
