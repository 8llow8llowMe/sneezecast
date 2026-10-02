// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { NO_LOGIN_RETURN } from './login-return'
import type * as store from './login-return-store'
import {
  afterKakaoLoginPath,
  clearLoginReturn,
  LOGIN_RETURN_STORAGE_KEY,
  LOGIN_RETURN_TTL_MS,
  peekLoginReturn,
  saveLoginReturn,
  takeLoginReturn,
  withSavedLoginReturn,
} from './login-return-store'

const NOW = 1_800_000_000_000
const REPORT = { next: '/', region: '11680640', intent: 'report' as const }
const ME = { next: '/me', region: null, intent: null }

const stored = () => window.sessionStorage.getItem(LOGIN_RETURN_STORAGE_KEY)
const writeRaw = (value: unknown) =>
  window.sessionStorage.setItem(
    LOGIN_RETURN_STORAGE_KEY,
    typeof value === 'string' ? value : JSON.stringify(value),
  )

/** 페이지를 새로 연 것처럼 모듈 변수 없이 저장소만 남긴 채 모듈을 다시 불러온다(카카오 왕복 · 새로고침) */
async function reloadStore(): Promise<typeof store> {
  vi.resetModules()
  return import('./login-return-store')
}

describe('login-return-store', () => {
  beforeEach(() => {
    clearLoginReturn()
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('둔 값을 그대로 읽고, 저장소에는 허용 목록 경로 · 동네 코드 · intent · 시각만 남긴다', () => {
    saveLoginReturn(REPORT, NOW)
    expect(peekLoginReturn(NOW)).toEqual(REPORT)
    expect(JSON.parse(stored() ?? 'null')).toEqual({
      v: 1,
      next: '/',
      region: '11680640',
      intent: 'report',
      savedAt: NOW,
    })
  })

  it('읽고 지우기(take)는 한 번만 돌려주고 저장소도 비운다', () => {
    saveLoginReturn(ME, NOW)
    expect(takeLoginReturn(NOW)).toEqual(ME)
    expect(takeLoginReturn(NOW)).toEqual(NO_LOGIN_RETURN)
    expect(stored()).toBeNull()
  })

  it('들고 갈 것이 없으면(홈 · 동네 없음 · 보고 아님) 앞서 둔 값을 지운다', () => {
    saveLoginReturn(ME, NOW)
    saveLoginReturn(NO_LOGIN_RETURN, NOW)
    expect(peekLoginReturn(NOW)).toEqual(NO_LOGIN_RETURN)
    expect(stored()).toBeNull()
  })

  it('둘러보기 동네만 있어도 들고 간다 (로그인 뒤 그 동네 홈)', () => {
    saveLoginReturn({ next: '/', region: '11440660', intent: null }, NOW)
    expect(peekLoginReturn(NOW)).toEqual({ next: '/', region: '11440660', intent: null })
  })

  it('넣는 값도 검증한다 — 목록 밖 경로는 홈, 모양이 다른 동네는 버리고, 홈이 아니면 intent 를 버린다', () => {
    saveLoginReturn({ next: '//evil.example', region: '../x', intent: 'report' }, NOW)
    expect(peekLoginReturn(NOW)).toEqual({ next: '/', region: null, intent: 'report' })
    saveLoginReturn({ next: '/me', region: '11440660', intent: 'report' }, NOW)
    expect(peekLoginReturn(NOW)).toEqual({ next: '/me', region: '11440660', intent: null })
  })

  it('30분이 지나면 버린다 (저장소에서도 지운다)', async () => {
    saveLoginReturn(ME, NOW)
    expect(peekLoginReturn(NOW + LOGIN_RETURN_TTL_MS)).toEqual(ME)
    expect(peekLoginReturn(NOW + LOGIN_RETURN_TTL_MS + 1)).toEqual(NO_LOGIN_RETURN)

    const reloaded = await reloadStore()
    expect(reloaded.peekLoginReturn(NOW + LOGIN_RETURN_TTL_MS + 1)).toEqual(NO_LOGIN_RETURN)
    expect(stored()).toBeNull()
  })

  describe('페이지를 새로 연 뒤 (카카오 왕복 · 새로고침)', () => {
    it('저장소에서 되살린다', async () => {
      saveLoginReturn(REPORT, NOW)
      const reloaded = await reloadStore()
      expect(reloaded.peekLoginReturn(NOW + 1000)).toEqual(REPORT)
    })

    it.each([
      ['JSON 이 아님', '{not json'],
      ['객체가 아님', '"/me"'],
      ['판이 다름', { v: 2, next: '/me', region: null, intent: null, savedAt: NOW }],
      ['시각이 없음', { v: 1, next: '/me', region: null, intent: null }],
      ['앞으로의 시각', { v: 1, next: '/me', region: null, intent: null, savedAt: NOW + 60_000 }],
    ])('오염된 값(%s)은 버리고 저장소에서 지운다', async (_, value) => {
      writeRaw(value)
      const reloaded = await reloadStore()
      expect(reloaded.peekLoginReturn(NOW)).toEqual(NO_LOGIN_RETURN)
      expect(stored()).toBeNull()
    })

    it.each([
      ['다른 오리진', { next: '//evil.example' }, { next: '/' }],
      ['절대 주소', { next: 'https://evil.example/me' }, { next: '/' }],
      ['쿼리가 붙음', { next: '/me?confirm=withdraw' }, { next: '/' }],
      ['목록 밖', { next: '/login' }, { next: '/' }],
      ['문자열이 아닌 경로', { next: 42 }, { next: '/' }],
      ['동네 모양이 다름', { next: '/me', region: '<script>' }, { next: '/me', region: null }],
      ['홈이 아닌데 intent', { next: '/me', intent: 'report' }, { next: '/me', intent: null }],
      ['모르는 intent', { next: '/', intent: 'share' }, { next: '/', intent: null }],
    ])('고친 값(%s)은 주소 쿼리와 같은 규칙으로 다시 검증한다', async (_, patch, expected) => {
      const base: Record<string, unknown> = { v: 1, region: null, intent: null, savedAt: NOW }
      writeRaw({ ...base, ...patch })
      const reloaded = await reloadStore()
      expect(reloaded.peekLoginReturn(NOW)).toEqual({ ...NO_LOGIN_RETURN, ...expected })
    })
  })

  describe('저장소를 쓸 수 없을 때', () => {
    it('저장소 접근이 예외면 같은 문서 안에서는 모듈 변수로 잇고, 새로 열면 홈이다', async () => {
      vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => {
        throw new DOMException('blocked', 'SecurityError')
      })
      saveLoginReturn(ME, NOW)
      expect(peekLoginReturn(NOW)).toEqual(ME)
      expect(takeLoginReturn(NOW)).toEqual(ME)
      expect(peekLoginReturn(NOW)).toEqual(NO_LOGIN_RETURN)

      saveLoginReturn(ME, NOW)
      const reloaded = await reloadStore()
      expect(reloaded.peekLoginReturn(NOW)).toEqual(NO_LOGIN_RETURN)
    })

    it('쓰기가 실패하면(쿼터) 앞선 값을 지우고 모듈 변수로 잇는다', async () => {
      saveLoginReturn(ME, NOW)
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('full', 'QuotaExceededError')
      })
      saveLoginReturn(REPORT, NOW)
      expect(peekLoginReturn(NOW)).toEqual(REPORT)
      // 앞선 값(/me)이 새로 연 문서에 끼어들지 않는다
      expect(stored()).toBeNull()
      const reloaded = await reloadStore()
      expect(reloaded.peekLoginReturn(NOW)).toEqual(NO_LOGIN_RETURN)
    })

    it('읽기가 예외여도 홈으로 정상 동작한다', async () => {
      saveLoginReturn(ME, NOW)
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('broken')
      })
      const reloaded = await reloadStore()
      expect(reloaded.peekLoginReturn(NOW)).toEqual(NO_LOGIN_RETURN)
      expect(reloaded.afterKakaoLoginPath()).toBe('/')
    })
  })

  describe('흐름 끝 주소', () => {
    it('카카오 로그인 뒤에는 둔 곳으로 가고 지운다 — 보고하려던 로그인은 같은 동네 홈의 보고 진입', () => {
      saveLoginReturn(REPORT)
      expect(afterKakaoLoginPath()).toBe('/?region=11680640&report=start')
      expect(afterKakaoLoginPath()).toBe('/')
    })

    it('로그인 화면으로 돌려보낼 때는 둔 곳을 쿼리로 싣고 지우지 않는다', () => {
      saveLoginReturn(ME)
      expect(withSavedLoginReturn('/login?error=kakao-fail&kakao=expired')).toBe(
        '/login?error=kakao-fail&kakao=expired&next=%2Fme',
      )
      expect(peekLoginReturn()).toEqual(ME)
      clearLoginReturn()
      expect(withSavedLoginReturn('/login/email')).toBe('/login/email')
    })
  })
})
