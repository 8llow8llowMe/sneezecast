import { describe, expect, it } from 'vitest'

import { REPORT_PARAM } from '@/features/report/types'

import {
  afterLoginHref,
  expiredLoginHref,
  isReturning,
  loginHref,
  loginReturnFrom,
  loginReturnFromSearch,
  loginReturnTo,
  NO_LOGIN_RETURN,
  REPORT_ENTRY_PARAM,
} from './login-return'

describe('loginReturnFrom', () => {
  it('허용 목록 안의 next 와 둘러보기 동네를 읽는다', () => {
    expect(loginReturnFrom({ next: '/me', region: '11440660' })).toEqual({
      next: '/me',
      region: '11440660',
      intent: null,
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
      intent: null,
    })
  })

  it('보고하려던 로그인(?intent=report)을 읽는다 — 돌아갈 곳은 홈이다', () => {
    expect(loginReturnFrom({ region: '11680640', intent: 'report' })).toEqual({
      next: '/',
      region: '11680640',
      intent: 'report',
    })
  })

  it.each([
    ['모르는 값', 'share'],
    ['빈 값', ''],
    ['대문자', 'REPORT'],
  ])('intent 가 %s 이면 버린다', (_, intent) => {
    expect(loginReturnFrom({ intent }).intent).toBeNull()
  })

  it('intent 는 홈으로 돌아갈 때만 받는다 — next 가 홈이 아니면 버린다(보고 진입은 홈의 시트다)', () => {
    expect(loginReturnFrom({ next: '/me', intent: 'report' })).toEqual({
      next: '/me',
      region: null,
      intent: null,
    })
  })

  it('목록 밖 next 는 홈이 되어 intent 를 그대로 받는다 (next 규칙은 바꾸지 않는다)', () => {
    expect(loginReturnFrom({ next: '//evil.example', intent: 'report' })).toEqual({
      next: '/',
      region: null,
      intent: 'report',
    })
  })
})

describe('loginReturnFromSearch', () => {
  it('주소 쿼리(URLSearchParams)에서 같은 규칙으로 읽는다', () => {
    expect(loginReturnFromSearch(new URLSearchParams('region=11680640&intent=report'))).toEqual({
      next: '/',
      region: '11680640',
      intent: 'report',
    })
    expect(loginReturnFromSearch(new URLSearchParams('next=%2Fme&intent=report'))).toEqual({
      next: '/me',
      region: null,
      intent: null,
    })
  })
})

describe('loginHref · afterLoginHref', () => {
  it('돌아갈 곳을 로그인 주소에 이어 붙이고, 로그인 뒤에는 동네만 남겨 그곳으로 간다', () => {
    const ret = { next: '/me', region: '11440660', intent: null }
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

describe('보고하려던 로그인 (intent=report)', () => {
  const ret = { next: '/', region: '11680640', intent: 'report' as const }

  it('로그인 주소에 intent 를 이어 붙이고 next(홈)는 붙이지 않는다', () => {
    expect(loginHref('/login', ret)).toBe('/login?region=11680640&intent=report')
    expect(loginHref('/login/email', { ...ret, region: null })).toBe('/login/email?intent=report')
  })

  it('로그인 뒤에는 같은 동네 홈의 보고 진입(report=start)으로 간다 — 회원 상태에 맞는 시트는 홈이 고친다', () => {
    expect(afterLoginHref(ret)).toBe('/?region=11680640&report=start')
    expect(afterLoginHref({ ...ret, region: null })).toBe('/?report=start')
  })

  it('돌아갈 곳이 있는 로그인으로 본다 — 로그인 화면의 뒤로가 보고를 누른 화면으로 되돌린다', () => {
    expect(isReturning(ret)).toBe(true)
  })

  it('보고 진입 쿼리 이름은 홈의 보고 흐름과 같다', () => {
    expect(REPORT_ENTRY_PARAM).toBe(REPORT_PARAM)
  })
})

describe('지금 화면으로 돌아올 로그인 (loginReturnTo · expiredLoginHref, #140)', () => {
  it('허용 목록 안 경로면 next 로, 둘러보기 동네는 남기고 보고하려던 표시는 없다', () => {
    const search = new URLSearchParams('region=11440660&mock-auth=member&report=start')
    expect(loginReturnTo('/me/devices', search)).toEqual({
      next: '/me/devices',
      region: '11440660',
      intent: null,
    })
    expect(expiredLoginHref(loginReturnTo('/me/devices', search))).toBe(
      '/login?reason=expired&next=%2Fme%2Fdevices&region=11440660',
    )
  })

  it.each(['/map', '/official', '/login', '/me/unknown', '//evil.example'])(
    '목록 밖 경로(%s)는 next 를 싣지 않는다',
    (path) => {
      expect(expiredLoginHref(loginReturnTo(path, new URLSearchParams()))).toBe(
        '/login?reason=expired',
      )
    },
  )
})
