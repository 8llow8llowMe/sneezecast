import { describe, expect, it } from 'vitest'

import { KAKAO_CALLBACK_PATH } from '@/features/onboarding/paths'

import nextConfig from '../next.config'

describe('next.config headers', () => {
  it('카카오 콜백 문서에만 Referrer-Policy: no-referrer 를 붙인다 (인가 코드가 리퍼러로 새지 않게)', async () => {
    const rules = (await nextConfig.headers?.()) ?? []
    expect(rules).toEqual([
      {
        source: KAKAO_CALLBACK_PATH,
        headers: [{ key: 'Referrer-Policy', value: 'no-referrer' }],
      },
    ])
  })
})
