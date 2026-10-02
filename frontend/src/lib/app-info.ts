/**
 * 앱 이름 · 설명. 루트 레이아웃 메타데이터(`app/layout.tsx`)와 웹 앱 매니페스트(`app/manifest.ts`)가 같이 쓴다.
 * 화면 머리줄의 서비스 이름 글자는 시안 그대로 각 화면에 둔다.
 */
export const APP_NAME = '우리동네체온계'

/**
 * 홈 화면 · 앱 목록 아이콘 아래 이름(매니페스트 `short_name`, iOS `apple-mobile-web-app-title`).
 * `우리동네체온계`(7자)는 iPhone · Android 런처의 아이콘 이름 폭(한글 약 5~6자)을 넘어 `우리동네체…` 로 잘린다.
 * 잘려도 뜻이 남는 `체온계` 를 살려 `동네체온계`(5자)로 줄인다. 앱 로고 · 이름 시안이 오면 다시 정한다.
 */
export const APP_SHORT_NAME = '동네체온계'

export const APP_DESCRIPTION = '이웃의 주간 건강 보고로 우리 동네 증상 변화를 확인해요.'
