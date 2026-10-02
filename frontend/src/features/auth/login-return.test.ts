import { describe, expect, it } from 'vitest'

import {
  afterLoginHref,
  isReturning,
  loginHref,
  loginReturnFrom,
  NO_LOGIN_RETURN,
} from './login-return'

describe('loginReturnFrom', () => {
  it('허용 목록 안의 next 와 둘러보기 동네를 읽는다', () => {
    expect(loginReturnFrom({ next: '/me', region: '11440660' })).toEqual({
      next: '/me',
      region: '11440660',
    })
  })

  it.each([
    ['없음', undefined],
    ['다른 오리진', '//evil.example'],
    ['절대 주소', 'https://evil.example/me'],
    ['쿼리가 붙음', '/me?confirm=withdraw'],
    ['목록 밖 경로', '/login'],
  ])('next 가 %s 이면 홈이다 (오픈 리다이렉트 방지)', (_, next) => {
    expect(loginReturnFrom({ next }).next).toBe('/')
  })

  it('값이 여러 개면 첫 값을 쓰고, 빈 동네는 없는 것으로 본다', () => {
    expect(loginReturnFrom({ next: ['/me', '/'], region: '' })).toEqual({
      next: '/me',
      region: null,
    })
  })
})

describe('loginHref · afterLoginHref', () => {
  it('돌아갈 곳을 로그인 주소에 이어 붙이고, 로그인 뒤에는 동네만 남겨 그곳으로 간다', () => {
    const ret = { next: '/me', region: '11440660' }
    expect(loginHref('/login/email', ret)).toBe('/login/email?next=%2Fme&region=11440660')
    expect(afterLoginHref(ret)).toBe('/me?region=11440660')
    expect(isReturning(ret)).toBe(true)
  })

  it('돌아갈 곳이 없으면 쿼리 없이 로그인하고 홈으로 간다', () => {
    expect(loginHref('/login/email', NO_LOGIN_RETURN)).toBe('/login/email')
    expect(afterLoginHref(NO_LOGIN_RETURN)).toBe('/')
    expect(isReturning(NO_LOGIN_RETURN)).toBe(false)
  })
})
