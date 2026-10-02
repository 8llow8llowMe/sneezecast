/**
 * 브라우저 UI(주소창·PWA 상태 표시줄) 색. Next 의 `viewport.themeColor` 는 CSS 변수를 받지 못해
 * 값을 문자열로 둔다. tokens.json `color.bg` 와 같아야 하며 token-sync.test.ts 가 이를 확인한다.
 * 웹 앱 매니페스트의 `theme_color` · `background_color`(설치한 앱의 첫 화면 바탕)도 이 값이다(`app/manifest.ts`).
 */
export const THEME_COLOR = '#ffffff'

/**
 * 앱 아이콘 바탕색. 아이콘 그림(`ImageResponse`)은 CSS 변수를 읽지 못해 값을 문자열로 둔다.
 * tokens.json `color.brand` 와 같아야 하며 token-sync.test.ts 가 이를 확인한다.
 */
export const BRAND_COLOR = '#1f2f57'
