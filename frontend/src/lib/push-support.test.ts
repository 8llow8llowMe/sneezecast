import { describe, expect, it } from 'vitest'

import {
  detectPlatform,
  detectPushSupport,
  parseMockPush,
  type PushEnvironment,
  type PushWindow,
  readPushEnvironment,
} from './push-support'

const UA = {
  iphone:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
  iphoneChrome:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1',
  ipadLegacy:
    'Mozilla/5.0 (iPad; CPU OS 15_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.6 Mobile/15E148 Safari/604.1',
  // iPadOS 13 이상 Safari 는 Mac 과 같은 UA 를 보낸다. 터치 지점 수로 가린다
  macSafari:
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15',
  macChrome:
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  macFirefox:
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) Gecko/20100101 Firefox/127.0',
  windowsEdge:
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0',
  // iPad Chrome 은 Mac UA 에 CriOS 를 붙인다 (터치 지점으로 iPad)
  ipadChrome:
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1',
  macEdge:
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0',
  // Android Chrome 의 "데스크톱 사이트 요청" 은 Android 를 빼고 X11 Linux UA 를 보낸다
  androidDesktopSite:
    'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  android:
    'Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36',
} as const

describe('detectPlatform', () => {
  it.each([
    [UA.iphone, 5, 'iphone'],
    [UA.iphoneChrome, 5, 'iphone'],
    [UA.ipadLegacy, 5, 'ipad'],
    [UA.macSafari, 5, 'ipad'],
    [UA.macSafari, 0, 'mac-safari'],
    [UA.macChrome, 0, 'chromium'],
    [UA.windowsEdge, 0, 'chromium'],
    [UA.android, 5, 'android'],
    [UA.ipadChrome, 5, 'ipad'],
    [UA.macEdge, 0, 'chromium'],
    [UA.androidDesktopSite, 5, 'chromium'],
    [UA.macFirefox, 0, 'other'],
    ['', 0, 'other'],
  ] as const)('%s (터치 %d) → %s', (userAgent, touchPoints, expected) => {
    expect(detectPlatform(userAgent, touchPoints)).toBe(expected)
  })
})

const ALL_APIS = { hasPushManager: true, hasServiceWorker: true, hasNotification: true }
const NO_APIS = { hasPushManager: false, hasServiceWorker: false, hasNotification: false }

function env(overrides: Partial<PushEnvironment>): PushEnvironment {
  return { platform: 'chromium', standalone: false, ...ALL_APIS, ...overrides }
}

describe('detectPushSupport', () => {
  it('iPhone · iPad 는 홈 화면에 추가하기 전이면 API 가 있어도 needs-install 이다', () => {
    expect(detectPushSupport(env({ platform: 'iphone' }))).toBe('needs-install')
    expect(detectPushSupport(env({ platform: 'ipad', ...NO_APIS }))).toBe('needs-install')
  })

  it('홈 화면에 추가한 iPhone 은 API 가 있으면 supported, 없으면(오래된 iOS) unsupported 다', () => {
    expect(detectPushSupport(env({ platform: 'iphone', standalone: true }))).toBe('supported')
    expect(detectPushSupport(env({ platform: 'iphone', standalone: true, ...NO_APIS }))).toBe(
      'unsupported',
    )
  })

  it('그 밖의 기기는 PushManager · 서비스 워커 · Notification 이 모두 있어야 supported 다', () => {
    expect(detectPushSupport(env({ platform: 'android' }))).toBe('supported')
    expect(detectPushSupport(env({ platform: 'mac-safari' }))).toBe('supported')
    expect(detectPushSupport(env({ hasPushManager: false }))).toBe('unsupported')
    expect(detectPushSupport(env({ hasServiceWorker: false }))).toBe('unsupported')
    expect(detectPushSupport(env({ platform: 'other', hasNotification: false }))).toBe(
      'unsupported',
    )
  })
})

function fakeWindow(overrides: Partial<PushWindow> = {}): PushWindow {
  return {
    navigator: { userAgent: UA.android, maxTouchPoints: 5, serviceWorker: {} },
    matchMedia: (query) => ({ matches: query === '(display-mode: standalone)' }),
    PushManager: function PushManager() {},
    Notification: function Notification() {},
    ...overrides,
  }
}

describe('readPushEnvironment', () => {
  it('API 가 있는지와 홈 화면 앱(display-mode: standalone)으로 열렸는지를 읽는다', () => {
    expect(readPushEnvironment(fakeWindow())).toEqual({
      platform: 'android',
      standalone: true,
      ...ALL_APIS,
    })
  })

  it('없는 API 는 false 다. matchMedia 가 없으면 standalone 이 아니다', () => {
    expect(
      readPushEnvironment({ navigator: { userAgent: UA.macFirefox, maxTouchPoints: 0 } }),
    ).toEqual({ platform: 'other', standalone: false, ...NO_APIS })
  })

  it('iOS Safari 의 navigator.standalone 도 홈 화면 앱으로 본다', () => {
    const win = fakeWindow({
      navigator: { userAgent: UA.iphone, maxTouchPoints: 5, standalone: true },
      matchMedia: () => ({ matches: false }),
    })
    expect(readPushEnvironment(win).standalone).toBe(true)
  })

  it('홈 화면에 추가한 iPad(navigator.standalone)는 기기를 iPad 로, 홈 화면 앱으로 본다', () => {
    const win = fakeWindow({
      navigator: {
        userAgent: UA.macSafari,
        maxTouchPoints: 5,
        standalone: true,
        serviceWorker: {},
      },
      matchMedia: () => ({ matches: false }),
    })
    const result = readPushEnvironment(win)
    expect(result).toMatchObject({ platform: 'ipad', standalone: true })
    expect(detectPushSupport(result)).toBe('supported')
  })
})

describe('parseMockPush', () => {
  it('아는 값만 받고 나머지는 무시한다', () => {
    expect(parseMockPush('supported')).toBe('supported')
    expect(parseMockPush('needs-install')).toBe('needs-install')
    expect(parseMockPush('unsupported')).toBe('unsupported')
    expect(parseMockPush('granted')).toBeNull()
    expect(parseMockPush(null)).toBeNull()
  })
})
