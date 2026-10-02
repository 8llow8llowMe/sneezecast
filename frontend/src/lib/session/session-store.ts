import { setAccessTokenProvider, setAccessTokenRefresher } from '@/lib/api/access-token'
import { ApiError, unavailableError } from '@/lib/api/api-error'
import { apiRequest } from '@/lib/api/client'
import { classifyApiError } from '@/lib/api/error-kind'
import { notifySessionExpired } from '@/lib/session-expiry'

import { hasSessionHint, writeSessionHint } from './session-hint'
import {
  openSessionChannel,
  type SessionChannel,
  type SessionMessage,
  withSessionLock,
} from './session-sync'

/* ── 세션 저장소 (실데이터 모드) ─────────────────────────────────────────────────────────────────
 *
 * 로그인 · 재발급 응답(`AuthToken`)을 들고 API 계층에 access token 을 준다(docs/conventions.md "세션 저장소").
 *
 * - **access token 은 이 모듈의 지역 변수에만 둔다.** 화면이 읽는 스냅숏(`SessionSnapshot`)에는 토큰이 없다. 쿠키 · 브라우저 저장소 ·
 *   주소 · console 에 남기지 않는다. 탭 사이 알림(`session-sync.ts`)으로만 같은 오리진의 다른 탭에 넘긴다
 * - 만료 시각은 응답의 `accessTokenExpiresIn`(초)로 정한다(JWT 를 풀지 않는다). 요청 직전에 만료 30초 전이면 먼저 재발급한다
 *   (타이머 없음). 서버가 401(`reissue`)로 거절하면 API 계층이 갈아 끼우기를 불러 같은 요청을 한 번 다시 보낸다
 * - 재발급은 탭 안에서 하나로 묶고(`inflight`), 탭 사이는 잠금으로 줄 세운다. 잠금을 잡은 뒤 그사이 다른 탭 소식으로 세션이
 *   바뀌었으면(`generation`) 재발급하지 않는다
 * - 앱을 열 때(`restoreSession`)는 세션 힌트 쿠키(`sc_session`)가 있을 때만 재발급을 한 번 해 본다
 * - 세션 중 재발급이 일시 장애로 끝나면 공급자 · 갈아 끼우기가 일시 장애(`UNAVAILABLE`)를 던진다 — 토큰 없이 보내 "로그인 필요"
 *   (`SECURITY_001`)로 보이지 않게 한다. 세션은 그대로 두고 다음 요청이 다시 재발급한다
 *
 * 목데이터 모드의 목 세션(`features/auth/auth-client.ts`)과는 따로다. 출처 토글은 어느 쪽도 지우지 않는다.
 * lib 라 features 를 모른다 — 만료 이동은 `notifySessionExpired()` 를 받는 쪽(`session-expiry-watcher.tsx`)이 한다.
 */

export type SessionRole = 'USER' | 'OPERATOR' | 'ADMIN'

/** 로그인 · 재발급 응답 `dataBody` (backend `AuthTokenResponse`) */
export type AuthToken = {
  memberId: string
  role: SessionRole
  accessToken: string
  /** access token 수명(초) */
  accessTokenExpiresIn: number
  /** 다시 동의해야 하는 필수 항목(ConsentType 이름). 없으면 빈 목록 */
  pendingConsents: string[]
  /** 주간 보고를 쓸 수 있는지(`report:write`) */
  reportWritable: boolean
}

/** 화면이 보는 회원 요약. **토큰이 없다** */
export type SessionSummary = Readonly<{
  memberId: string
  role: SessionRole
  pendingConsents: readonly string[]
  reportWritable: boolean
}>

/**
 * - `idle`: 아직 복원하지 않음(서버 그림 · 하이드레이션 첫 그림 · 목데이터 모드)
 * - `restoring`: 힌트가 있어 재발급을 기다림
 * - `guest` · `member`: 정해짐
 */
export type SessionSnapshot =
  | { status: 'idle' }
  | { status: 'restoring' }
  | { status: 'guest' }
  | { status: 'member'; summary: SessionSummary }

export const REISSUE_PATH = '/api/v1/auth/token/reissue'

/** 만료까지 이만큼 남았으면 요청 전에 먼저 재발급한다 */
export const ACCESS_EXPIRY_MARGIN_MS = 30_000

/**
 * 재발급 경합(`AUTH_016`)에 진 뒤 다시 보내기 전에 기다리는 시간. 이긴 탭의 응답이 새 refresh 쿠키(Set-Cookie)를 반영할 틈이다 —
 * 탭 사이 잠금(`navigator.locks`)이 없는 환경에서 바로 다시 보내면 옛 쿠키로 또 진다
 */
export const REISSUE_CONFLICT_RETRY_DELAY_MS = 300

/** 서버 스냅숏으로도 쓰므로 늘 같은 객체다 */
export const IDLE_SESSION: SessionSnapshot = Object.freeze({ status: 'idle' })
const RESTORING: SessionSnapshot = Object.freeze({ status: 'restoring' })
const GUEST: SessionSnapshot = Object.freeze({ status: 'guest' })

type ReissueReason = 'stale' | 'rejected' | 'restore'
type ClearReason = 'logout' | 'expired' | 'withdrawn' | 'remote'

let snapshot: SessionSnapshot = IDLE_SESSION
let accessToken: string | null = null
let expiresAt = 0
/** 세션이 바뀔 때(설정 · 비우기)마다 늘린다. 기다리는 동안 세션이 바뀌었는지 본다 */
let generation = 0
let inflight: Promise<string | null> | null = null
let channel: SessionChannel | null = null
const listeners = new Set<() => void>()

function publish(next: SessionSnapshot): void {
  if (next === snapshot) return
  snapshot = next
  listeners.forEach((listener) => listener())
}

function sameSummary(a: SessionSummary, b: SessionSummary): boolean {
  return (
    a.memberId === b.memberId &&
    a.role === b.role &&
    a.reportWritable === b.reportWritable &&
    a.pendingConsents.length === b.pendingConsents.length &&
    a.pendingConsents.every((consent, index) => consent === b.pendingConsents[index])
  )
}

/** 지금 세션. 바뀔 때만 새 객체다(`useSyncExternalStore` 의 getSnapshot) */
export function getSessionSnapshot(): SessionSnapshot {
  return snapshot
}

/** 세션이 바뀔 때 알림을 받는다. 돌려준 함수로 그만 받는다 */
export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * 로그인 · 재발급 응답으로 세션을 정한다. 힌트 쿠키를 남기고, `broadcast`(기본 true)면 다른 탭에 알린다.
 * 다른 탭에서 받은 토큰은 `broadcast: false` 로 부른다(되돌려 알리지 않는다)
 */
export function setSession(
  token: AuthToken,
  { broadcast = true }: { broadcast?: boolean } = {},
): void {
  accessToken = token.accessToken
  expiresAt = Date.now() + token.accessTokenExpiresIn * 1000
  generation += 1
  writeSessionHint(true)
  const summary: SessionSummary = Object.freeze({
    memberId: token.memberId,
    role: token.role,
    pendingConsents: Object.freeze([...token.pendingConsents]),
    reportWritable: token.reportWritable,
  })
  if (!(snapshot.status === 'member' && sameSummary(snapshot.summary, summary))) {
    publish(Object.freeze({ status: 'member', summary }))
  }
  if (broadcast) channel?.post({ type: 'signed-in', token })
}

/**
 * 세션을 비운다(비회원). 힌트 쿠키를 지운다.
 * - `expired` 이고 이 탭에 세션이 있었으면 `notifySessionExpired()` 를 부른다(로그인 화면 · 만료 토스트)
 * - `remote`(다른 탭의 로그아웃 · 만료)는 알리지 않고 되돌려 보내지도 않는다
 */
export function clearSession(
  reason: ClearReason,
  { broadcast = true }: { broadcast?: boolean } = {},
): void {
  const wasMember = snapshot.status === 'member'
  accessToken = null
  expiresAt = 0
  generation += 1
  writeSessionHint(false)
  publish(GUEST)
  if (broadcast && reason !== 'remote') channel?.post({ type: 'signed-out', reason })
  if (reason === 'expired' && wasMember) notifySessionExpired()
}

/** 재발급이 세션을 끝내는 오류인지: 재로그인(`AUTH_014/015`) · 탈퇴 · 정지 회원(`MEMBER_002/003`) */
function endsSession(error: unknown): boolean {
  if (classifyApiError(error) === 'relogin') return true
  return error instanceof ApiError && (error.code === 'MEMBER_002' || error.code === 'MEMBER_003')
}

/** 세션 중 재발급의 일시 장애를 API 계층에 넘길 오류로. 분류에 없는 오류도 일시 장애로 본다(원 오류는 `cause`) */
function asUnavailable(error: unknown): ApiError {
  if (classifyApiError(error) === 'unavailable' && error instanceof ApiError) return error
  return unavailableError('network', error instanceof ApiError ? error.status : 0, error)
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/**
 * 재발급을 보낸다. 경합(`AUTH_016`)이면 잠깐 기다려 한 번 더 보낸다. 두 번째 경합은 이 탭의 세션만 끝낸다 —
 * 이긴 탭의 세션은 멀쩡할 수 있어 다른 탭에 알리지 않는다.
 * 세션 중(stale · rejected)의 일시 장애는 던진다. 복원 중의 일시 장애는 null 이다(비회원으로 보인다)
 */
async function requestReissue(reason: ReissueReason): Promise<string | null> {
  for (let attempt = 0; ; attempt += 1) {
    const sent = generation
    try {
      const token = await apiRequest<AuthToken>(REISSUE_PATH, { method: 'POST', auth: false })
      // 기다리는 동안 로그아웃했거나 다른 탭 소식으로 바뀌었으면 응답을 버린다(로그아웃한 세션을 되살리지 않는다)
      if (generation !== sent) return accessToken
      setSession(token)
      return token.accessToken
    } catch (error) {
      // 늦게 온 실패도 버린다 — 이미 비운 세션을 다시 비우거나 만료를 두 번 알리지 않는다
      if (generation !== sent) return accessToken
      const conflict = classifyApiError(error) === 'reissue-conflict'
      if (conflict && attempt === 0) {
        await wait(REISSUE_CONFLICT_RETRY_DELAY_MS)
        if (generation !== sent) return accessToken
        continue
      }
      if (conflict || endsSession(error)) {
        // 복원이면 이 탭에 세션이 없었다 — 알리지 않고 힌트만 지운다
        if (reason === 'restore') {
          writeSessionHint(false)
          publish(GUEST)
        } else {
          // 재로그인 · 탈퇴 · 정지는 refresh 쿠키를 같이 쓰는 다른 탭도 끝났으니 알린다. 두 번째 경합은 이 탭만이다
          clearSession('expired', { broadcast: !conflict })
        }
        return null
      }
      // 일시 장애 · 그 밖: 세션을 끝내지 않는다. 토큰만 버려 다음 요청이 다시 재발급한다
      accessToken = null
      expiresAt = 0
      if (reason !== 'restore') throw asUnavailable(error)
      // 복원이면 힌트를 남긴 채 비회원으로 보인다(다음에 열 때 다시 해 본다)
      publish(GUEST)
      return null
    }
  }
}

/** 재발급. 탭 안에서는 하나로 묶고, 탭 사이는 잠금으로 줄 세운다 */
function reissue(reason: ReissueReason): Promise<string | null> {
  if (inflight) return inflight
  const waited = generation
  const task = withSessionLock(() =>
    // 잠금을 기다리는 동안 다른 탭이 재발급 · 로그아웃을 알렸으면 그 결과를 쓴다
    generation !== waited ? Promise.resolve(accessToken) : requestReissue(reason),
  )
  inflight = task.finally(() => {
    inflight = null
  })
  return inflight
}

/**
 * access token 공급자. 세션이 없으면 null, 만료가 가까우면 먼저 재발급한다. 복원 중이면 복원을 기다린다.
 * 재발급이 일시 장애면 던진다(요청을 보내지 않는다)
 */
function provideAccessToken(): string | null | Promise<string | null> {
  if (snapshot.status === 'restoring') return inflight ?? null
  if (snapshot.status !== 'member') return null
  if (accessToken && expiresAt - Date.now() > ACCESS_EXPIRY_MARGIN_MS) return accessToken
  return reissue('stale')
}

/** 갈아 끼우기. 거절된 토큰을 이미 다른 요청이 바꿨으면 재발급 없이 지금 토큰을 준다. 재발급이 일시 장애면 던진다 */
function refreshRejectedAccessToken(rejected: string): Promise<string | null> {
  if (snapshot.status !== 'member') return Promise.resolve(null)
  if (accessToken && accessToken !== rejected) return Promise.resolve(accessToken)
  return reissue('rejected')
}

/**
 * 앱을 열 때 세션을 되살린다. `idle` 일 때만 한다(여러 번 불러도 한 번). 힌트가 없으면 요청 없이 비회원,
 * 있으면 `restoring` 으로 두고 재발급을 한 번 한다. 실패는 던지지 않는다(비회원으로 보인다)
 */
export async function restoreSession(): Promise<void> {
  if (snapshot.status === 'restoring') {
    await inflight
    return
  }
  if (snapshot.status !== 'idle') return
  if (!hasSessionHint()) {
    publish(GUEST)
    return
  }
  publish(RESTORING)
  await reissue('restore')
}

function receive(message: SessionMessage): void {
  if (message.type === 'signed-in') setSession(message.token, { broadcast: false })
  else clearSession('remote', { broadcast: false })
}

/**
 * API 계층에 공급자 · 갈아 끼우기를 끼우고 다른 탭 소식을 받기 시작한다. 돌려준 함수로 해제한다.
 * 루트 레이아웃의 `SessionBootstrap` 이 한 번 부른다
 */
export function startSession(): () => void {
  setAccessTokenProvider(provideAccessToken)
  setAccessTokenRefresher(refreshRejectedAccessToken)
  const opened = openSessionChannel(receive)
  channel = opened
  return () => {
    setAccessTokenProvider(null)
    setAccessTokenRefresher(null)
    opened.close()
    if (channel === opened) channel = null
  }
}

/** 테스트 정리용. 메모리 세션을 처음(`idle`)으로 되돌린다 — 구독 · 힌트 쿠키는 건드리지 않는다 */
export function resetSessionForTests(): void {
  accessToken = null
  expiresAt = 0
  generation = 0
  inflight = null
  publish(IDLE_SESSION)
}
