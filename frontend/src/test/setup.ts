import { afterEach } from 'vitest'

/**
 * 모든 테스트 파일 앞에서 돈다 (vitest.config.mts `setupFiles`).
 *
 * `globals: false` 라 Testing Library 의 자동 정리가 걸리지 않는다. 그대로 두면 앞 테스트에서
 * 렌더한 DOM 이 남아 `getByRole` 이 여러 개를 찾는다. jsdom 을 켠 파일에서만 정리를 등록한다 —
 * node 환경 파일에서 react-dom 을 불러올 이유가 없다.
 */
if (typeof window !== 'undefined') {
  const { cleanup } = await import('@testing-library/react')
  afterEach(cleanup)
}
