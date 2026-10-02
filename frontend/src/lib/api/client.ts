import { clientEnv } from '@/lib/env.client'

import { resolveAccessToken } from './access-token'
import { ApiError, unavailableError } from './api-error'
import { readEnvelope } from './envelope'

/**
 * 백엔드 API 를 부르는 얇은 `fetch` 래퍼. 화면 코드는 `fetch` 를 직접 부르지 않고(ESLint 가 막는다) 도메인 클라이언트
 * (`features/<도메인>/*-client.ts`)가 이 함수를 부른다. 데이터 패칭 라이브러리는 쓰지 않는다(docs/conventions.md "API 계층").
 *
 * - 주소: `clientEnv.apiBaseUrl`(게이트웨이) + `/api/...`. 다른 오리진으로는 보내지 않는다 — 토큰이 새지 않게 한다
 * - `credentials: 'include'`: refresh 쿠키(`Path=/api/v1/auth`)가 재발급 · 로그아웃 · 세션 API 에 실린다
 * - `cache: 'no-store'`: 회원별 · 주별로 바뀌는 응답을 브라우저 · Next 가 캐시하지 않게 한다
 * - 타임아웃 `API_TIMEOUT_MS`: 넘으면 끊고 일시 장애다. 게이트웨이 업스트림 상한(10초)보다 길게 두어 게이트웨이의
 *   `GATEWAY_004`(504) 봉투를 먼저 받는다
 * - 성공 봉투면 `dataBody` 를, 실패 봉투면 `ApiError`(서버 코드 · 문구 · 필드 오류)를, 봉투가 없거나 응답을 못 받으면
 *   `ApiError`(`UNAVAILABLE`)를 던진다. 호출한 쪽이 `signal` 로 취소하면 그 사유를 그대로 던진다
 *
 * 요청 바디 · 토큰 · 응답을 로그로 남기지 않는다. 비밀번호 · 토큰 · 인증 코드는 `query` 가 아니라 `body` 로 보낸다.
 *
 * 응답 본문의 모양은 검사하지 않는다(`T` 는 호출한 쪽의 약속이다). 화면 모델로 옮기는 일은 도메인 클라이언트가 맡는다.
 */

/** 게이트웨이 업스트림 상한(`GATEWAY_RESPONSE_TIMEOUT` 10초)보다 약간 길다 */
export const API_TIMEOUT_MS = 12_000

export type ApiMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export type ApiQuery = Readonly<Record<string, string | number | boolean | undefined>>

export type ApiRequestOptions = {
  /** 기본 GET */
  method?: ApiMethod
  /** 주소 쿼리. undefined 인 값은 뺀다. 민감한 값(비밀번호 · 토큰 · 인증 코드 · 건강 정보)을 싣지 않는다 */
  query?: ApiQuery
  /** JSON 으로 보낼 바디. GET 에는 줄 수 없다 */
  body?: unknown
  /**
   * `Authorization: Bearer` 를 실을지. 기본 true — 공급자(`setAccessTokenProvider`)가 토큰을 주면 싣는다.
   * **재발급(`POST /api/v1/auth/token/reissue`)은 false 로 부른다.** 게이트웨이와 auth 필터는 경로와 무관하게 헤더가 있으면
   * access 를 검사해, 만료된 access 를 실으면 refresh 가 멀쩡해도 `SECURITY_002` 로 끝난다.
   */
  auth?: boolean
  /** 호출한 쪽의 취소(화면을 떠남 · 검색어가 바뀜). 취소하면 일시 장애가 아니라 그 사유를 던진다 */
  signal?: AbortSignal
}

function buildUrl(path: string, query: ApiQuery | undefined): string {
  // `//host` 는 프로토콜 상대 주소다. 경로만 받아 늘 게이트웨이로 보낸다.
  if (!path.startsWith('/') || path.startsWith('//')) {
    throw new TypeError(`API 경로는 / 로 시작하는 게이트웨이 경로여야 한다: ${path.split('?')[0]}`)
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined) params.append(key, String(value))
  }
  const search = params.toString()
  if (!search) return `${clientEnv.apiBaseUrl}${path}`
  return `${clientEnv.apiBaseUrl}${path}${path.includes('?') ? '&' : '?'}${search}`
}

/** 본문을 JSON 으로 읽는다. JSON 이 아니면(HTML 오류 페이지 · 빈 본문) undefined */
function parseJson(text: string): unknown {
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { method = 'GET', query, body, auth = true, signal } = options

  // fetch 는 잘못 만든 요청도 네트워크 실패와 같은 TypeError 로 거절한다. 프로그램 오류가 일시 장애로 숨지 않게 먼저 막는다.
  const url = buildUrl(path, query)
  if (method === 'GET' && body !== undefined) {
    throw new TypeError('GET 요청에는 바디를 줄 수 없다')
  }
  const payload = body === undefined ? undefined : JSON.stringify(body)

  const headers = new Headers({ Accept: 'application/json' })
  if (payload !== undefined) headers.set('Content-Type', 'application/json')
  if (auth) {
    const token = await resolveAccessToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }

  signal?.throwIfAborted()

  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, API_TIMEOUT_MS)
  const forwardAbort = () => controller.abort(signal?.reason)
  signal?.addEventListener('abort', forwardAbort, { once: true })

  let status = 0
  let text: string
  try {
    const response = await fetch(url, {
      method,
      headers,
      ...(payload === undefined ? {} : { body: payload }),
      credentials: 'include',
      cache: 'no-store',
      signal: controller.signal,
    })
    status = response.status
    // 본문 읽기도 타임아웃 안에 둔다 — 머리만 오고 본문이 멈출 수 있다.
    text = await response.text()
  } catch (error) {
    if (timedOut) throw unavailableError('timeout', 0, error)
    if (signal?.aborted) throw signal.reason
    throw unavailableError('network', status, error)
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', forwardAbort)
  }

  const envelope = readEnvelope(parseJson(text))
  if (!envelope) throw unavailableError('no-envelope', status)

  const header = envelope.dataHeader
  if (!header.success) {
    throw new ApiError({
      status,
      code: header.resultCode,
      message: header.resultMessage,
      fieldErrors: header.fieldErrors,
    })
  }
  return envelope.dataBody as T
}
