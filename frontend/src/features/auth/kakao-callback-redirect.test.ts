import { NextRequest } from 'next/server'

import { describe, expect, it } from 'vitest'

import { KAKAO_CALLBACK_PATH } from '@/features/onboarding/paths'

import { kakaoCallbackRedirect } from './kakao-callback-redirect'

const ORIGIN = 'http://localhost:3000'
const request = (path: string, method = 'GET') => new NextRequest(new URL(path, ORIGIN), { method })

describe('kakaoCallbackRedirect', () => {
  it('콜백 쿼리를 fragment 로 옮겨 같은 경로로 303 보낸다', () => {
    const response = kakaoCallbackRedirect(
      request(`${KAKAO_CALLBACK_PATH}?code=SECRET%2Fx&state=S1`),
    )
    expect(response?.status).toBe(303)
    expect(response?.headers.get('location')).toBe(
      `${ORIGIN}${KAKAO_CALLBACK_PATH}#code=SECRET%2Fx&state=S1`,
    )
    expect(response?.headers.get('cache-control')).toBe('no-store')
    expect(response?.headers.get('referrer-policy')).toBe('no-referrer')
  })

  it('사용자 취소(error)도 옮긴다', () => {
    const response = kakaoCallbackRedirect(
      request(`${KAKAO_CALLBACK_PATH}?error=access_denied&state=S1`),
    )
    expect(response?.headers.get('location')).toBe(
      `${ORIGIN}${KAKAO_CALLBACK_PATH}#error=access_denied&state=S1`,
    )
  })

  it('쿼리가 없는 콜백(리다이렉트를 받은 문서 · 새로고침)은 그대로 둔다', () => {
    expect(kakaoCallbackRedirect(request(KAKAO_CALLBACK_PATH))).toBeNull()
  })

  it('GET 이 아니면 그대로 둔다', () => {
    expect(kakaoCallbackRedirect(request(`${KAKAO_CALLBACK_PATH}?code=x`, 'HEAD'))).toBeNull()
  })

  it.each([
    '/login?code=x',
    '/login/kakao/link?code=x',
    '/?code=x',
    '/login/kakao/callbackx?code=x',
  ])('다른 경로 %s 는 건드리지 않는다', (path) => {
    expect(kakaoCallbackRedirect(request(path))).toBeNull()
  })
})
