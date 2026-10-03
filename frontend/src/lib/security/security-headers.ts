/* ── 모든 응답에 붙이는 보안 헤더 (#176) ──────────────────────────────────────────────────────────
 *
 * `next.config.ts` 의 `headers()` 가 모든 경로(`/(.*)`, 빌드 산출물 · 아이콘 포함)에 붙인다. 요청마다 바뀌는 CSP 는 여기 없고
 * proxy 가 화면 요청에만 붙인다(`content-security-policy.ts`). 근거는 docs/conventions.md "보안 헤더 · CSP".
 *
 * `next.config.ts` 가 불러 빌드 설정을 읽을 때도 돌므로 이 파일은 다른 모듈(`@/` 별칭 포함)을 불러오지 않는다.
 *
 * HSTS(`Strict-Transport-Security`)는 여기 두지 않는다. TLS 를 끝내는 Infra nginx 가 웹 · API 도메인에 함께 건다 — 앱은 TLS 를
 * 끝내는 곳이 아니고, API 도메인은 앱을 거치지 않으며, 두 곳에서 걸면 헤더가 겹친다.
 */

/**
 * 쓰지 않는 브라우저 기능을 끈다(`()` = 어느 오리진에도 허용하지 않음). 위치는 쓰지 않는다는 도메인 규칙(동네는 사용자가 고른다)과 같다.
 * 끄지 않는 것: 공유 창(`web-share`) · 링크 복사(`clipboard-write`) — 보고 공유 시트가 쓴다. 알림 · 푸시(2단계)는 이 헤더가 다루지 않는다.
 * 브라우저가 모르는 이름을 넣으면 콘솔 경고가 나므로 Chrome 이 아는 이름만 쓴다.
 */
export const PERMISSIONS_POLICY = [
  'geolocation=()',
  'camera=()',
  'microphone=()',
  'payment=()',
  'usb=()',
  'serial=()',
  'hid=()',
  'bluetooth=()',
  'midi=()',
  'display-capture=()',
  'accelerometer=()',
  'gyroscope=()',
  'magnetometer=()',
].join(', ')

export const SECURITY_HEADERS: ReadonlyArray<{ key: string; value: string }> = [
  // 선언한 Content-Type 대로만 읽는다 — 스크립트 · 스타일로 둔갑한 응답을 막는다
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  // 다른 오리진에는 오리진만 보낸다(경로 · 쿼리를 싣지 않음). 카카오 콜백은 next.config.ts 가 `no-referrer` 로 덮어쓴다
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: PERMISSIONS_POLICY },
  // CSP frame-ancestors 'none' 과 같은 뜻. CSP 를 모르는 브라우저와 CSP 가 붙지 않는 응답(아이콘 등)용
  { key: 'X-Frame-Options', value: 'DENY' },
]
