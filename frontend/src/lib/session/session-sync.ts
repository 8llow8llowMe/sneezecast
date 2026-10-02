import type { AuthToken } from './session-store'

/* ── 탭 사이 세션 맞추기 ─────────────────────────────────────────────────────────────────────────
 *
 * 같은 브라우저의 탭들은 refresh 쿠키 하나를 같이 쓴다. 재발급은 refresh 를 회전시키므로 두 탭이 동시에 재발급하면 한쪽이
 * `AUTH_016`(경합)으로 진다. 그래서
 * - 재발급은 탭 사이 잠금(`navigator.locks`, `SESSION_LOCK_NAME`)으로 한 번에 하나만 한다
 * - 재발급 · 로그인 결과(`signed-in`)와 로그아웃 · 만료(`signed-out`)를 `BroadcastChannel`(`SESSION_CHANNEL_NAME`)로 알린다.
 *   받은 탭은 재발급 없이 그 토큰을 쓰거나 세션을 비운다
 *
 * 둘 다 없는 환경(오래된 브라우저 · 테스트)에서는 조용히 강등한다 — 잠금 없이 바로 하고, 알리지 않는다. 경합에 진 탭은
 * `AUTH_016` 을 받아 재발급을 한 번 다시 한다(세션 저장소).
 *
 * 같은 오리진의 탭끼리만 오간다. 받은 값은 모양을 확인한 뒤에만 쓴다.
 */

export const SESSION_CHANNEL_NAME = 'sneezecast:session'
export const SESSION_LOCK_NAME = 'sneezecast:session-reissue'

export type SessionMessage =
  | { type: 'signed-in'; token: AuthToken }
  | { type: 'signed-out'; reason: 'logout' | 'expired' | 'withdrawn' }

export type SessionChannel = {
  post: (message: SessionMessage) => void
  close: () => void
}

/** `BroadcastChannel` 중 쓰는 부분 */
type ChannelLike = Pick<BroadcastChannel, 'postMessage' | 'close'> & {
  onmessage: ((event: MessageEvent) => void) | null
}

/** `navigator.locks` 중 쓰는 부분 */
export type SessionLocks = {
  request: <T>(name: string, callback: () => Promise<T>) => Promise<T>
}

const SIGNED_OUT_REASONS: readonly string[] = ['logout', 'expired', 'withdrawn']
const ROLES: readonly string[] = ['USER', 'OPERATOR', 'ADMIN']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isAuthToken(value: unknown): value is AuthToken {
  return (
    isRecord(value) &&
    typeof value.memberId === 'string' &&
    typeof value.role === 'string' &&
    ROLES.includes(value.role) &&
    typeof value.accessToken === 'string' &&
    value.accessToken !== '' &&
    typeof value.accessTokenExpiresIn === 'number' &&
    Array.isArray(value.pendingConsents) &&
    value.pendingConsents.every((item) => typeof item === 'string') &&
    typeof value.reportWritable === 'boolean'
  )
}

/** 받은 값을 메시지로. 모양이 다르면 null 이다(버린다) */
export function parseSessionMessage(data: unknown): SessionMessage | null {
  if (!isRecord(data)) return null
  if (data.type === 'signed-in' && isAuthToken(data.token)) {
    return { type: 'signed-in', token: data.token }
  }
  if (
    data.type === 'signed-out' &&
    typeof data.reason === 'string' &&
    SIGNED_OUT_REASONS.includes(data.reason)
  ) {
    return { type: 'signed-out', reason: data.reason as 'logout' | 'expired' | 'withdrawn' }
  }
  return null
}

function defaultChannelFactory(): ((name: string) => ChannelLike) | null {
  return typeof BroadcastChannel === 'function' ? (name) => new BroadcastChannel(name) : null
}

const NOOP_CHANNEL: SessionChannel = { post: () => {}, close: () => {} }

/**
 * 다른 탭의 세션 소식을 받는 통로를 연다. `BroadcastChannel` 이 없으면 아무것도 하지 않는 통로다.
 * 자기가 보낸 소식은 자기에게 오지 않는다. `createChannel` 은 테스트가 가짜 통로를 끼울 때만 준다
 */
export function openSessionChannel(
  onMessage: (message: SessionMessage) => void,
  createChannel: ((name: string) => ChannelLike) | null = defaultChannelFactory(),
): SessionChannel {
  if (!createChannel) return NOOP_CHANNEL
  const channel = createChannel(SESSION_CHANNEL_NAME)
  channel.onmessage = (event) => {
    const message = parseSessionMessage(event.data)
    if (message) onMessage(message)
  }
  return {
    post: (message) => channel.postMessage(message),
    close: () => {
      channel.onmessage = null
      channel.close()
    },
  }
}

function defaultLocks(): SessionLocks | null {
  if (typeof navigator === 'undefined') return null
  const locks = (navigator as Partial<Navigator>).locks
  return locks && typeof locks.request === 'function' ? locks : null
}

/**
 * 탭 사이 잠금 안에서 `task` 를 한다. 다른 탭이 잡고 있으면 풀릴 때까지 기다린다.
 * `navigator.locks` 가 없으면 바로 한다. `locks` 는 테스트가 가짜 잠금을 끼울 때만 준다
 */
export function withSessionLock<T>(
  task: () => Promise<T>,
  locks: SessionLocks | null = defaultLocks(),
): Promise<T> {
  if (!locks) return task()
  return locks.request(SESSION_LOCK_NAME, task)
}
