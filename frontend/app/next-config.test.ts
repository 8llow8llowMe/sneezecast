import type { NextConfig } from 'next'

import { describe, expect, it } from 'vitest'

import { KAKAO_CALLBACK_PATH } from '@/features/onboarding/paths'
import { NO_STORE_CACHE_CONTROL, RSC_CACHE_CONTROL, RSC_HEADER } from '@/lib/security/cache-headers'
import { PERMISSIONS_POLICY, SECURITY_HEADERS } from '@/lib/security/security-headers'

import nextConfig from '../next.config'

/** `headers()` 규칙 하나(Next 의 타입) */
type HeaderRule = Awaited<ReturnType<NonNullable<NextConfig['headers']>>>[number]

async function rules(): Promise<HeaderRule[]> {
  return (await nextConfig.headers?.()) ?? []
}

/**
 * 한 요청이 받는 헤더. Next 는 맞는 규칙을 순서대로 적용하고 같은 키는 뒤 규칙이 이긴다
 * (node_modules/next/dist/docs/01-app/03-api-reference/05-config/01-next-config-js/headers.md).
 * 이 테스트의 맞춤은 이 파일의 모양(경로 `/(.*)` · 정확한 경로, 조건 `has` 의 요청 헤더 값 같음)만 다룬다.
 */
async function headersFor(
  path: string,
  requestHeaders: Record<string, string> = {},
): Promise<Map<string, string>> {
  const result = new Map<string, string>()
  for (const rule of await rules()) {
    if (rule.source !== '/(.*)' && rule.source !== path) continue
    const matches = (rule.has ?? []).every(
      (condition) =>
        condition.type === 'header' &&
        condition.key !== undefined &&
        requestHeaders[condition.key] === condition.value,
    )
    if (!matches) continue
    for (const { key, value } of rule.headers) result.set(key.toLowerCase(), value)
  }
  return result
}

const RSC_REQUEST = { [RSC_HEADER.key]: RSC_HEADER.value }

describe('next.config headers', () => {
  it('모든 경로에 보안 헤더를 붙인다', async () => {
    const all = (await rules()).find((rule) => rule.source === '/(.*)')
    expect(all?.headers).toEqual(SECURITY_HEADERS)
    const headers = await headersFor('/')
    expect(headers.get('x-content-type-options')).toBe('nosniff')
    expect(headers.get('referrer-policy')).toBe('strict-origin-when-cross-origin')
    expect(headers.get('x-frame-options')).toBe('DENY')
    expect(headers.get('permissions-policy')).toBe(PERMISSIONS_POLICY)
  })

  it('카카오 콜백 문서는 Referrer-Policy: no-referrer 가 이긴다 (인가 코드가 리퍼러로 새지 않게)', async () => {
    const all = await rules()
    const callback = all.findIndex((rule) => rule.source === KAKAO_CALLBACK_PATH)
    const global = all.findIndex((rule) => rule.source === '/(.*)')
    expect(all[callback]?.headers).toContainEqual({ key: 'Referrer-Policy', value: 'no-referrer' })
    // 같은 키는 뒤 규칙이 이긴다 — 콜백 규칙이 전체 규칙보다 뒤에 있어야 한다
    expect(callback).toBeGreaterThan(global)
    const headers = await headersFor(KAKAO_CALLBACK_PATH)
    expect(headers.get('referrer-policy')).toBe('no-referrer')
    // 나머지 보안 헤더는 콜백에도 그대로다
    expect(headers.get('x-content-type-options')).toBe('nosniff')
  })

  it('문서의 Cache-Control 은 건드리지 않는다 — Next 기본값(no-store)이 그대로다', async () => {
    for (const path of ['/', '/login', '/map', '/me']) {
      expect((await headersFor(path)).has('cache-control')).toBe(false)
    }
  })

  it('앱 안 이동 · 미리 받기(RSC) 응답은 private, no-cache 다 — bfcache 를 막지 않고, max-age 없이 쓸 때마다 다시 확인한다', async () => {
    for (const path of ['/', '/login', '/map', '/me']) {
      expect((await headersFor(path, RSC_REQUEST)).get('cache-control')).toBe(RSC_CACHE_CONTROL)
    }
    expect(RSC_CACHE_CONTROL).not.toMatch(/max-age|no-store|public/)
  })

  it('카카오 콜백은 RSC 요청도 no-store 다 (문서와 같은 값, 콜백 규칙이 RSC 규칙보다 뒤)', async () => {
    const all = await rules()
    const callback = all.findIndex((rule) => rule.source === KAKAO_CALLBACK_PATH)
    const rsc = all.findIndex((rule) => rule.has?.some((condition) => condition.key === 'rsc'))
    expect(callback).toBeGreaterThan(rsc)
    expect((await headersFor(KAKAO_CALLBACK_PATH, RSC_REQUEST)).get('cache-control')).toBe(
      NO_STORE_CACHE_CONTROL,
    )
    expect((await headersFor(KAKAO_CALLBACK_PATH)).get('cache-control')).toBe(
      NO_STORE_CACHE_CONTROL,
    )
  })

  it('CSP 는 여기 두지 않는다 — 요청마다 nonce 가 바뀌어 proxy 가 붙인다', async () => {
    for (const rule of await rules()) {
      expect(rule.headers.map((h) => h.key.toLowerCase())).not.toContain('content-security-policy')
    }
  })

  it('X-Powered-By 를 보내지 않는다', () => {
    expect(nextConfig.poweredByHeader).toBe(false)
  })

  it('HSTS 는 앱이 걸지 않는다 — TLS 를 끝내는 Infra nginx 가 건다', async () => {
    for (const rule of await rules()) {
      expect(rule.headers.map((h) => h.key.toLowerCase())).not.toContain(
        'strict-transport-security',
      )
    }
  })
})

describe('Permissions-Policy', () => {
  const features = new Map(
    PERMISSIONS_POLICY.split(',').map((entry) => {
      const [name, allow] = entry.trim().split('=')
      return [name, allow] as const
    }),
  )

  it('위치 · 카메라 · 마이크를 끈다 (위치를 쓰지 않는다는 도메인 규칙)', () => {
    expect(features.get('geolocation')).toBe('()')
    expect(features.get('camera')).toBe('()')
    expect(features.get('microphone')).toBe('()')
  })

  it('보고 공유 시트가 쓰는 공유 창 · 링크 복사는 끄지 않는다', () => {
    expect(features.has('web-share')).toBe(false)
    expect(features.has('clipboard-write')).toBe(false)
  })

  it('끄는 기능은 모두 어느 오리진에도 허용하지 않는다', () => {
    for (const allow of features.values()) expect(allow).toBe('()')
  })
})
