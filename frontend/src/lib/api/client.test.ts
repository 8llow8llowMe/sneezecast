import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { clientEnv } from '@/lib/env.client'

import { setAccessTokenProvider, setAccessTokenRefresher } from './access-token'
import { ApiError, UNAVAILABLE_CODE, UNAVAILABLE_MESSAGE } from './api-error'
import { API_TIMEOUT_MS, apiRequest } from './client'

const fetchMock = vi.fn<typeof fetch>()

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function success(dataBody: unknown): unknown {
  return {
    dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
    dataBody,
  }
}

function failure(resultCode: string, resultMessage: string, fieldErrors: unknown = null): unknown {
  return { dataHeader: { success: false, resultCode, resultMessage, fieldErrors }, dataBody: null }
}

/** 마지막 fetch 호출의 주소와 옵션 */
function lastCall(): { url: string; init: RequestInit } {
  const call = fetchMock.mock.calls.at(-1)
  if (!call) throw new Error('fetch 가 불리지 않았다')
  const [input, init] = call
  // apiRequest 는 늘 문자열 주소로 부른다
  if (typeof input !== 'string') throw new Error('fetch 를 문자열 주소로 부르지 않았다')
  return { url: input, init: init ?? {} }
}

function lastHeaders(): Headers {
  return new Headers(lastCall().init.headers)
}

/** 신호가 끊기면 그 사유로 거절하는 fetch (응답이 오지 않는 서버) */
function hangingFetch(_input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return new Promise((_resolve, reject) => {
    const signal = init?.signal
    signal?.addEventListener('abort', () => {
      reject(signal.reason instanceof Error ? signal.reason : new Error('aborted'))
    })
  })
}

async function caught(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise
  } catch (error) {
    return error
  }
  throw new Error('거절되지 않았다')
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  setAccessTokenProvider(null)
  setAccessTokenRefresher(null)
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('apiRequest 요청 모양', () => {
  it('게이트웨이 주소에 경로와 쿼리를 잇고 credentials · cache · Accept 를 싣는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success([])))

    await apiRequest('/api/v1/districts', { query: { query: '역삼 1동', page: undefined } })

    const { url, init } = lastCall()
    expect(url).toBe(`${clientEnv.apiBaseUrl}/api/v1/districts?query=%EC%97%AD%EC%82%BC+1%EB%8F%99`)
    expect(init.method).toBe('GET')
    expect(init.credentials).toBe('include')
    expect(init.cache).toBe('no-store')
    expect(init.body).toBeUndefined()
    expect(lastHeaders().get('Accept')).toBe('application/json')
    expect(lastHeaders().has('Content-Type')).toBe(false)
  })

  it('바디가 있으면 JSON 으로 보내고 Content-Type 을 단다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success(null)))

    await apiRequest('/api/v1/auth/email/send-code', {
      method: 'POST',
      body: { email: 'user@example.com' },
    })

    const { init } = lastCall()
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{"email":"user@example.com"}')
    expect(lastHeaders().get('Content-Type')).toBe('application/json')
  })

  it('경로가 / 로 시작하지 않거나 다른 오리진이면 보내지 않는다 (토큰이 다른 곳으로 새지 않게)', async () => {
    await expect(apiRequest('api/v1/districts')).rejects.toThrow(TypeError)
    await expect(apiRequest('//evil.example/api')).rejects.toThrow(TypeError)
    await expect(apiRequest('https://evil.example/api')).rejects.toThrow(TypeError)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('GET 에 바디를 주면 네트워크 장애로 숨기지 않고 바로 TypeError 를 던진다', async () => {
    await expect(apiRequest('/api/v1/districts', { body: { a: 1 } })).rejects.toThrow(TypeError)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('apiRequest 인증 헤더', () => {
  it('공급자가 없으면 Authorization 을 싣지 않는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success(null)))

    await apiRequest('/api/v1/members/me')

    expect(lastHeaders().has('Authorization')).toBe(false)
  })

  it('공급자가 토큰을 주면 Bearer 로 싣는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success(null)))
    setAccessTokenProvider(() => 'access-token-1')

    await apiRequest('/api/v1/members/me')

    expect(lastHeaders().get('Authorization')).toBe('Bearer access-token-1')
  })

  it('비동기 공급자도 기다려서 싣는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success(null)))
    setAccessTokenProvider(() => Promise.resolve('access-token-2'))

    await apiRequest('/api/v1/members/me')

    expect(lastHeaders().get('Authorization')).toBe('Bearer access-token-2')
  })

  it('공급자가 null 이나 빈 문자열을 주면 싣지 않는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success(null)))
    setAccessTokenProvider(() => null)
    await apiRequest('/api/v1/districts')
    expect(lastHeaders().has('Authorization')).toBe(false)

    fetchMock.mockResolvedValue(jsonResponse(success(null)))
    setAccessTokenProvider(() => '')
    await apiRequest('/api/v1/districts')
    expect(lastHeaders().has('Authorization')).toBe(false)
  })

  it('auth: false 면 토큰이 있어도 싣지 않고 공급자를 부르지도 않는다 (재발급)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success(null)))
    const provider = vi.fn(() => 'expired-access')
    setAccessTokenProvider(provider)

    await apiRequest('/api/v1/auth/token/reissue', { method: 'POST', auth: false })

    expect(lastHeaders().has('Authorization')).toBe(false)
    expect(provider).not.toHaveBeenCalled()
  })

  it('공급자가 던진 오류는 그대로 넘기고 요청을 보내지 않는다', async () => {
    const failure = new Error('재발급 실패')
    setAccessTokenProvider(() => Promise.reject(failure))

    await expect(apiRequest('/api/v1/members/me')).rejects.toBe(failure)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('apiRequest 401 재시도', () => {
  const expired = () => jsonResponse(failure('SECURITY_002', '만료된 토큰입니다.'), 401)

  /** fetch 호출마다 실린 Authorization */
  function sentTokens(): (string | null)[] {
    return fetchMock.mock.calls.map(([, init]) => new Headers(init?.headers).get('Authorization'))
  }

  it('reissue 갈래(SECURITY_002)면 갈아 끼운 토큰으로 같은 요청을 한 번 다시 보낸다', async () => {
    fetchMock.mockResolvedValueOnce(expired()).mockResolvedValueOnce(jsonResponse(success('ok')))
    setAccessTokenProvider(() => 'old-access')
    const refresher = vi.fn(() => Promise.resolve('new-access'))
    setAccessTokenRefresher(refresher)

    await expect(
      apiRequest('/api/v1/reports', { method: 'POST', body: { week: '2026-W40' } }),
    ).resolves.toBe('ok')

    expect(refresher).toHaveBeenCalledExactlyOnceWith('old-access')
    expect(sentTokens()).toEqual(['Bearer old-access', 'Bearer new-access'])
    // 주소 · 메서드 · 바디 · Content-Type 은 같다
    const [first, second] = fetchMock.mock.calls
    expect(second?.[0]).toBe(first?.[0])
    expect(second?.[1]?.method).toBe('POST')
    expect(second?.[1]?.body).toBe(first?.[1]?.body)
    expect(new Headers(second?.[1]?.headers).get('Content-Type')).toBe('application/json')
  })

  it('다시 보낸 요청도 401 이면 그 오류를 던지고 더 재발급하지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(expired()).mockResolvedValueOnce(expired())
    setAccessTokenProvider(() => 'old-access')
    const refresher = vi.fn(() => Promise.resolve('new-access'))
    setAccessTokenRefresher(refresher)

    const error = await caught(apiRequest('/api/v1/members/me'))

    expect(error).toMatchObject({ status: 401, code: 'SECURITY_002' })
    expect(refresher).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('갈아 끼우지 못하면(null · 갈아 끼우기 없음) 원래 오류를 던진다', async () => {
    setAccessTokenProvider(() => 'old-access')
    fetchMock.mockResolvedValueOnce(expired())
    await expect(apiRequest('/api/v1/members/me')).rejects.toMatchObject({ code: 'SECURITY_002' })

    setAccessTokenRefresher(() => Promise.resolve(null))
    fetchMock.mockResolvedValueOnce(expired())
    await expect(apiRequest('/api/v1/members/me')).rejects.toMatchObject({ code: 'SECURITY_002' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['SECURITY_001', 401],
    ['SECURITY_006', 403],
    ['AUTH_014', 401],
  ])('%s(%i)는 다시 보내지 않는다', async (code, status) => {
    fetchMock.mockResolvedValueOnce(jsonResponse(failure(code, '거절'), status))
    setAccessTokenProvider(() => 'access')
    const refresher = vi.fn(() => Promise.resolve('new-access'))
    setAccessTokenRefresher(refresher)

    await expect(apiRequest('/api/v1/members/me')).rejects.toMatchObject({ code })
    expect(refresher).not.toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('토큰을 싣지 않은 요청(auth: false · 공급자 없음)은 401 이어도 다시 보내지 않는다', async () => {
    const refresher = vi.fn(() => Promise.resolve('new-access'))
    setAccessTokenRefresher(refresher)

    fetchMock.mockResolvedValueOnce(expired())
    await expect(apiRequest('/api/v1/districts')).rejects.toMatchObject({ code: 'SECURITY_002' })

    setAccessTokenProvider(() => 'access')
    fetchMock.mockResolvedValueOnce(expired())
    await expect(
      apiRequest('/api/v1/auth/token/reissue', { method: 'POST', auth: false }),
    ).rejects.toMatchObject({ code: 'SECURITY_002' })

    expect(refresher).not.toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('갈아 끼우기가 던진 오류(재발급 일시 장애)는 그대로 넘기고 다시 보내지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(expired())
    setAccessTokenProvider(() => 'old-access')
    const failure = new ApiError({
      status: 0,
      code: UNAVAILABLE_CODE,
      message: UNAVAILABLE_MESSAGE,
    })
    setAccessTokenRefresher(() => Promise.reject(failure))

    await expect(apiRequest('/api/v1/members/me')).rejects.toBe(failure)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('재발급을 기다리는 동안 취소하면 다시 보내지 않고 취소 사유를 던진다', async () => {
    fetchMock.mockResolvedValueOnce(expired())
    setAccessTokenProvider(() => 'old-access')
    const controller = new AbortController()
    const reason = new Error('화면을 떠남')
    setAccessTokenRefresher(() => {
      controller.abort(reason)
      return Promise.resolve('new-access')
    })

    await expect(apiRequest('/api/v1/members/me', { signal: controller.signal })).rejects.toBe(
      reason,
    )
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('apiRequest 응답 봉투', () => {
  it('성공 봉투면 dataBody 를 돌려준다', async () => {
    const body = { code: '11230510', name: '역삼1동', sigungu: '서울특별시 강남구', active: true }
    fetchMock.mockResolvedValue(jsonResponse(success(body)))

    await expect(apiRequest('/api/v1/districts/11230510')).resolves.toEqual(body)
  })

  it('본문 없는 성공(Response<Void>)은 null 이다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(success(null)))

    await expect(
      apiRequest('/api/v1/auth/email/send-code', { method: 'POST', body: {} }),
    ).resolves.toBeNull()
  })

  it('실패 봉투는 상태 · 코드 · 서버 문구를 실은 ApiError 다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(failure('AUTH_011', '이메일 또는 비밀번호가 일치하지 않습니다.'), 401),
    )

    const error = await caught(
      apiRequest('/api/v1/auth/login', { method: 'POST', body: { email: 'a', password: 'b' } }),
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 401,
      code: 'AUTH_011',
      message: '이메일 또는 비밀번호가 일치하지 않습니다.',
      fieldErrors: [],
      unavailableReason: undefined,
    })
  })

  it('검증 실패의 필드 오류를 함께 싣는다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        failure('MEMBER_102', '닉네임은 2~10자로 입력해 주세요.', [
          { code: 'MEMBER_102', field: 'nickname', message: '닉네임은 2~10자로 입력해 주세요.' },
          { code: 'MEMBER_999', field: 'nickname' },
        ]),
        400,
      ),
    )

    const error = await caught(
      apiRequest('/api/v1/members/me', { method: 'PATCH', body: { nickname: 'a' } }),
    )

    expect(error).toMatchObject({
      status: 400,
      code: 'MEMBER_102',
      fieldErrors: [
        { code: 'MEMBER_102', field: 'nickname', message: '닉네임은 2~10자로 입력해 주세요.' },
      ],
    })
  })

  it('게이트웨이의 실패 봉투(504 GATEWAY_004)도 서버 코드 그대로다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(failure('GATEWAY_004', '서비스 응답이 지연되고 있습니다.'), 504),
    )

    await expect(caught(apiRequest('/api/v1/districts?x'))).resolves.toMatchObject({
      status: 504,
      code: 'GATEWAY_004',
    })
  })

  it.each([
    [
      'Spring 기본 오류 JSON',
      jsonResponse({ timestamp: 'x', status: 500, error: 'Internal' }, 500),
    ],
    ['HTML 오류 페이지', new Response('<html>502 Bad Gateway</html>', { status: 502 })],
    ['빈 본문', new Response(null, { status: 204 })],
    ['코드 없는 실패 헤더', jsonResponse({ dataHeader: { success: false }, dataBody: null }, 500)],
    ['성공 상태의 JSON 이 아닌 본문', new Response('ok', { status: 200 })],
  ])('봉투가 없는 응답(%s)은 일시 장애다', async (_label, response) => {
    fetchMock.mockResolvedValue(response)

    const error = await caught(apiRequest('/api/v1/districts'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: response.status,
      code: UNAVAILABLE_CODE,
      message: UNAVAILABLE_MESSAGE,
      unavailableReason: 'no-envelope',
    })
  })
})

describe('apiRequest 네트워크 · 타임아웃', () => {
  it('네트워크 실패는 상태 0 의 일시 장애다', async () => {
    const cause = new TypeError('Failed to fetch')
    fetchMock.mockRejectedValue(cause)

    const error = await caught(apiRequest('/api/v1/districts'))

    expect(error).toMatchObject({
      status: 0,
      code: UNAVAILABLE_CODE,
      unavailableReason: 'network',
      cause,
    })
  })

  it('타임아웃까지 응답이 없으면 요청을 끊고 일시 장애로 알린다', async () => {
    vi.useFakeTimers()
    fetchMock.mockImplementation(hangingFetch)

    const pending = caught(apiRequest('/api/v1/districts'))
    await vi.advanceTimersByTimeAsync(API_TIMEOUT_MS - 1)
    expect(lastCall().init.signal?.aborted).toBe(false)

    await vi.advanceTimersByTimeAsync(1)

    expect(lastCall().init.signal?.aborted).toBe(true)
    await expect(pending).resolves.toMatchObject({
      status: 0,
      code: UNAVAILABLE_CODE,
      unavailableReason: 'timeout',
    })
  })

  it('타임아웃은 게이트웨이 업스트림 상한(10초)보다 길다 — 게이트웨이의 504 봉투를 먼저 받는다', () => {
    expect(API_TIMEOUT_MS).toBeGreaterThan(10_000)
  })

  it('본문을 읽는 중에 시간이 넘어도 타임아웃이다', async () => {
    vi.useFakeTimers()
    fetchMock.mockImplementation((_input, init) =>
      Promise.resolve({
        status: 200,
        text: () => hangingFetch(_input, init).then(() => ''),
      } as unknown as Response),
    )

    const pending = caught(apiRequest('/api/v1/districts'))
    await vi.advanceTimersByTimeAsync(API_TIMEOUT_MS)

    await expect(pending).resolves.toMatchObject({ unavailableReason: 'timeout' })
  })

  it('호출한 쪽이 취소하면 일시 장애로 바꾸지 않고 취소 사유를 그대로 던진다', async () => {
    fetchMock.mockImplementation(hangingFetch)
    const controller = new AbortController()
    const reason = new DOMException('화면을 떠났다', 'AbortError')

    const pending = caught(apiRequest('/api/v1/districts', { signal: controller.signal }))
    await Promise.resolve()
    controller.abort(reason)

    await expect(pending).resolves.toBe(reason)
  })

  it('이미 취소된 신호면 보내지 않는다', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(apiRequest('/api/v1/districts', { signal: controller.signal })).rejects.toBe(
      controller.signal.reason,
    )
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('끝난 요청은 타이머를 남기지 않는다', async () => {
    vi.useFakeTimers()
    fetchMock.mockResolvedValue(jsonResponse(success(null)))

    await apiRequest('/api/v1/districts')

    expect(vi.getTimerCount()).toBe(0)
  })
})
