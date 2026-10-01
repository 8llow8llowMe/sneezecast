/**
 * 이 기기 · 브라우저가 PWA 웹 푸시를 받을 수 있는지 (S12 홈 화면 추가 안내 · Settings-nopush · State-push-inapp).
 *
 * **알림 권한을 묻지 않고, 구독 · 서비스 워커 등록도 하지 않는다.** API 가 있는지와 홈 화면 앱으로 열렸는지만 읽는다.
 * 푸시 구독은 2단계 기능이다(docs/design/SCREENS.md "홈 화면 추가 · 알림 미지원").
 *
 * | 값 | 뜻 |
 * | --- | --- |
 * | `supported` | 지금 이 화면에서 알림을 켤 수 있다 |
 * | `needs-install` | iPhone · iPad 다. 홈 화면에 추가한 앱에서만 알림을 받는다 (iOS 16.4 이상 Safari 의 규칙) |
 * | `unsupported` | 이 브라우저에는 푸시 API 가 없다 (인앱 브라우저 · 오래된 브라우저 · 오래된 iOS 홈 화면 앱) |
 */
export const PUSH_SUPPORTS = ['supported', 'needs-install', 'unsupported'] as const
export type PushSupport = (typeof PUSH_SUPPORTS)[number]

/**
 * 설치 안내 묶음을 고르는 기기 종류. 브라우저가 보내는 UA 로 가린다 — 틀릴 수 있어 화면은 모르는 기기(`other`)에도
 * 안내를 다 보인다. 화면 판단 밖(권한 · 보안)에는 쓰지 않는다.
 */
export type DevicePlatform = 'iphone' | 'ipad' | 'android' | 'chromium' | 'mac-safari' | 'other'

/** 판단에 쓰는 브라우저 값. 테스트가 흉내 낼 수 있게 `window` 에서 쓰는 것만 적는다 */
export type PushWindow = {
  navigator: {
    userAgent: string
    maxTouchPoints?: number
    serviceWorker?: unknown
    /** iOS Safari 전용. 홈 화면 앱으로 열렸으면 true */
    standalone?: boolean
  }
  matchMedia?: (query: string) => { matches: boolean }
  PushManager?: unknown
  Notification?: unknown
}

export type PushEnvironment = {
  platform: DevicePlatform
  /** 홈 화면(또는 설치한 앱)으로 열렸는지 */
  standalone: boolean
  hasPushManager: boolean
  hasServiceWorker: boolean
  hasNotification: boolean
}

/**
 * UA 와 터치 지점 수로 기기 종류를 가린다.
 * - iPadOS 13 이상 Safari 는 Mac 과 같은 UA(`Macintosh`)를 보낸다. Mac 에는 터치 화면이 없어 터치 지점이 2 이상이면 iPad 로 본다.
 * - Chrome · Edge UA 에도 `Safari` 가 들어 있어 Chromium 을 Mac Safari 보다 먼저 가린다. Android 는 그보다 먼저다.
 */
export function detectPlatform(userAgent: string, maxTouchPoints: number): DevicePlatform {
  if (/iPhone|iPod/.test(userAgent)) return 'iphone'
  if (/iPad/.test(userAgent)) return 'ipad'
  if (/Android/.test(userAgent)) return 'android'
  const mac = /Macintosh/.test(userAgent)
  if (mac && maxTouchPoints >= 2) return 'ipad'
  if (/Edg\/|Chrome\/|Chromium\//.test(userAgent)) return 'chromium'
  if (mac && /Safari\//.test(userAgent)) return 'mac-safari'
  return 'other'
}

/** 브라우저 값을 읽는다. 없는 API 는 false 다(`matchMedia` 가 없으면 홈 화면 앱이 아니라고 본다) */
export function readPushEnvironment(win: PushWindow): PushEnvironment {
  const { navigator } = win
  const standaloneMedia = win.matchMedia?.('(display-mode: standalone)').matches ?? false
  return {
    platform: detectPlatform(navigator.userAgent, navigator.maxTouchPoints ?? 0),
    standalone: standaloneMedia || navigator.standalone === true,
    hasPushManager: win.PushManager !== undefined,
    hasServiceWorker: navigator.serviceWorker !== undefined,
    hasNotification: win.Notification !== undefined,
  }
}

/**
 * 웹 푸시를 받을 수 있는지. iPhone · iPad 는 홈 화면 앱이 아니면 API 가 보여도 알림을 받지 못해 `needs-install` 이다.
 * 홈 화면 앱인데 API 가 없으면(iOS 16.4 미만) 설치해도 받지 못하므로 `unsupported` 다.
 * 홈 화면 앱이 아닌 오래된 iOS 도 `needs-install` 로 본다 — UA 의 iOS 버전은 믿기 어려워 가리지 않는다.
 */
export function detectPushSupport(env: PushEnvironment): PushSupport {
  const ios = env.platform === 'iphone' || env.platform === 'ipad'
  if (ios && !env.standalone) return 'needs-install'
  return env.hasPushManager && env.hasServiceWorker && env.hasNotification
    ? 'supported'
    : 'unsupported'
}

/** QA 용 푸시 지원 덮어쓰기 쿼리 (`?mock-push=supported|needs-install|unsupported`) */
export const MOCK_PUSH_PARAM = 'mock-push'

/** 쿼리 값을 푸시 지원 값으로. 없거나 모르는 값이면 null 이다(무시한다) */
export function parseMockPush(value: string | null): PushSupport | null {
  return PUSH_SUPPORTS.find((support) => support === value) ?? null
}
