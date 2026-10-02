import { clientEnv } from './env.client'

/* ── 데이터 출처: 실데이터(api) · 목데이터(mock) (#135) ─────────────────────────────────────────────
 *
 * 도메인 클라이언트(`features/<도메인>/*-client.ts`)는 출처를 인자로 받아 백엔드 호출(`src/lib/api/`)과 목 데이터 중 하나로 답한다.
 * 개발 서버가 없거나 백엔드가 준비되지 않은 동안에도 화면을 확인하고, 준비되면 같은 화면으로 실제 응답을 확인하려고 둔다.
 * 행정동이 처음이고, 세션 · 로그인 · 보고 등 앞으로 연동할 도메인도 이 장치를 쓴다(docs/conventions.md "API 계층").
 *
 * - 고른 값은 1st-party 쿠키 `sc_data_source`(`api` | `mock`)에 둔다. 서버 컴포넌트(`data-source.server.ts`)와 화면이 같이 읽어야 해서
 *   브라우저 저장소가 아니라 쿠키다. 화면 토글(`components/data-source-toggle.tsx`)이 읽고 써야 하므로 `HttpOnly` 가 아니다.
 *   값은 출처 이름뿐이라 개인을 알아보는 값이 없다.
 * - 쿠키가 없거나 모르는 값이면 기본값 — 공개 환경변수 `NEXT_PUBLIC_DATA_SOURCE`, 그것도 없거나 모르는 값이면 `mock` 이다.
 * - **전환은 허용 목록의 사이트에서만 된다**(`isDataSourceSwitchable`: dev 웹 `https://dev.sneezecast.com` · 로컬 `http://localhost` 류).
 *   그 밖(운영 · 사이트 주소가 비었거나 틀림)은 쿠키 · 환경변수를 무시하고 늘 `api` 다.
 *   쿠키는 누구나 고칠 수 있다. 운영에서 목으로 바꿀 수 있으면 지어낸 수치 · 안내가 실제 정보처럼 보이고,
 *   목 회원 상태로 회원 화면이 열려 인가를 건너뛴 것처럼 보인다. 실제 데이터 접근은 서버가 인가하지만,
 *   운영 화면이 목 데이터를 그릴 길 자체를 두지 않는다.
 *
 * 서버 · 클라이언트가 같이 쓰므로 `next/headers` 를 import 하지 않는다. 서버에서 쿠키를 읽는 일은 `data-source.server.ts` 가 맡는다.
 */

export type DataSource = 'api' | 'mock'

export const DATA_SOURCE_COOKIE = 'sc_data_source'

/** 1년. 브라우저 상한(400일) 안이다 */
export const DATA_SOURCE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365

/** 출처를 바꿀 수 있는 https 사이트 — dev 웹뿐이다 */
const SWITCHABLE_HTTPS_ORIGINS: readonly string[] = ['https://dev.sneezecast.com']

/** 출처를 바꿀 수 있는 로컬 개발 호스트 (`http:` 일 때만). `*.localhost` 는 따로 본다 */
const LOCAL_HOSTNAMES: readonly string[] = ['localhost', '127.0.0.1', '[::1]']

/** 쿠키 · 환경변수 값을 출처로. 모르는 값이면 null 이다 */
export function parseDataSource(value: string | null | undefined): DataSource | null {
  return value === 'api' || value === 'mock' ? value : null
}

/**
 * 출처를 바꿀 수 있는 사이트인지. dev 웹(`https://dev.sneezecast.com`)이거나, `http:` 이면서 호스트가
 * `localhost` · `127.0.0.1` · `[::1]` · `*.localhost` 일 때만 true 다. 그 밖(운영 · 빈 값 · 오타 · 주소가 아님)은 false 다.
 *
 * **막을 곳이 아니라 열 곳을 적는다** — 운영 빌드에 사이트 주소(`NEXT_PUBLIC_SITE_URL`)를 잘못 넣어도 토글이 열리지 않게
 * 모르는 주소는 닫힌 쪽(늘 `api`)으로 떨어뜨린다.
 */
export function isDataSourceSwitchable(siteUrl: string = clientEnv.siteUrl): boolean {
  let url: URL
  try {
    url = new URL(siteUrl)
  } catch {
    return false
  }
  if (url.protocol === 'https:') return SWITCHABLE_HTTPS_ORIGINS.includes(url.origin)
  if (url.protocol !== 'http:') return false
  return LOCAL_HOSTNAMES.includes(url.hostname) || url.hostname.endsWith('.localhost')
}

type DataSourceConfig = {
  /** 사이트 주소 (`clientEnv.siteUrl`) */
  siteUrl: string
  /** 기본값 환경변수 값 (`clientEnv.dataSource`) */
  defaultValue: string
}

const ENV_CONFIG: DataSourceConfig = {
  siteUrl: clientEnv.siteUrl,
  defaultValue: clientEnv.dataSource,
}

/**
 * 지금 출처. 전환할 수 없는 사이트면 늘 `api`, 아니면 쿠키 값, 쿠키가 없거나 모르는 값이면 기본값(환경변수 → `mock`)이다.
 * `config` 는 테스트가 환경을 바꿔 볼 때만 준다.
 */
export function resolveDataSource(
  cookieValue: string | null | undefined,
  config: DataSourceConfig = ENV_CONFIG,
): DataSource {
  if (!isDataSourceSwitchable(config.siteUrl)) return 'api'
  return parseDataSource(cookieValue) ?? parseDataSource(config.defaultValue) ?? 'mock'
}

/** `a=1; b=2` 꼴의 쿠키 문자열에서 이름이 같은 첫 값. 없으면 undefined 다 */
export function readCookieValue(cookieHeader: string, name: string): string | undefined {
  for (const part of cookieHeader.split(';')) {
    const separator = part.indexOf('=')
    if (separator === -1) continue
    if (part.slice(0, separator).trim() === name) return part.slice(separator + 1).trim()
  }
  return undefined
}

/** 브라우저에서 지금 출처. 서버(문서가 없음)에서는 기본값이다 */
export function readBrowserDataSource(): DataSource {
  if (typeof document === 'undefined') return resolveDataSource(undefined)
  return resolveDataSource(readCookieValue(document.cookie, DATA_SOURCE_COOKIE))
}

const listeners = new Set<() => void>()

/** 브라우저에서 출처가 바뀔 때 알림을 받는다. 돌려준 함수로 그만 받는다 (`useSyncExternalStore` 의 subscribe) */
export function subscribeDataSource(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * 브라우저에서 출처를 고른다(쿠키에 쓴다). 전환할 수 없는 사이트면 아무것도 하지 않는다.
 * `Path=/` · `SameSite=Lax` · 1년. HTTPS 면 `Secure`. 화면이 읽어야 해서 `HttpOnly` 가 아니다(쓸 수도 없다).
 *
 * 서버 컴포넌트가 이미 그린 화면은 바뀌지 않는다 — 부르는 쪽이 `router.refresh()` 로 다시 그리게 한다.
 */
export function writeBrowserDataSource(source: DataSource): void {
  if (!isDataSourceSwitchable()) return
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${DATA_SOURCE_COOKIE}=${source}; Path=/; Max-Age=${DATA_SOURCE_MAX_AGE_SECONDS}; SameSite=Lax${secure}`
  listeners.forEach((listener) => listener())
}
