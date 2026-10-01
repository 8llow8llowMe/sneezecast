/**
 * 브라우저에 노출되는 환경변수. 빌드 시점에 값이 인라인된다.
 *
 * **`process.env.NEXT_PUBLIC_X` 를 리터럴로만 읽는다.** Next 는 리터럴 참조만 치환하므로
 * `process.env[key]` 처럼 동적으로 읽으면 브라우저에서 undefined 가 된다.
 * 비밀값은 여기 두지 않는다 — `NEXT_PUBLIC_` 값은 누구나 번들에서 읽을 수 있다.
 */

const LOCAL_SITE_URL = 'http://localhost:3000'
const DEV_API_BASE_URL = 'https://api-dev.sneezecast.com'

/** 끝의 `/` 를 지워 `${base}/api/...` 로 이을 때 `//` 가 생기지 않게 한다 */
export function normalizeBaseUrl(value: string | undefined, fallback: string): string {
  const trimmed = value?.trim()
  return (trimmed ? trimmed : fallback).replace(/\/+$/, '')
}

export const clientEnv = {
  /** 메타데이터 절대 URL 기준. dev = https://dev.sneezecast.com, prod = https://www.sneezecast.com */
  siteUrl: normalizeBaseUrl(process.env.NEXT_PUBLIC_SITE_URL, LOCAL_SITE_URL),
  /** 게이트웨이 주소. 로컬 개발도 팀 dev 게이트웨이를 쓴다 (CORS 가 localhost 를 허용한다) */
  apiBaseUrl: normalizeBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL, DEV_API_BASE_URL),
} as const
