import type { DevicePlatform } from '@/lib/push-support'

/**
 * 홈 화면 추가 방법 묶음 (S12 시안 Install · Install-T · Install-D 의 문구 그대로).
 * 모바일 시안은 iPhone · Android, 태블릿 · 데스크톱 시안은 iPad·Mac · Chrome·Edge 묶음이다.
 */
export const INSTALL_GUIDES = {
  iphone: {
    title: 'iPhone (Safari)',
    steps: [
      '화면 아래 공유 버튼 누르기',
      '"홈 화면에 추가" 고르기',
      '추가한 앱을 열고 알림 허용하기',
    ],
  },
  android: {
    title: 'Android (Chrome)',
    steps: ['"설치" 안내가 뜨면 설치 누르기', '알림 허용하기'],
  },
  apple: {
    title: 'iPad·Mac (Safari)',
    steps: [
      '공유 버튼 누르기',
      '"홈 화면에 추가" 또는 "Dock에 추가" 고르기',
      '추가한 앱에서 알림 허용하기',
    ],
  },
  browser: {
    title: 'Chrome·Edge',
    steps: ['주소창의 설치 아이콘 누르기', '알림 허용하기'],
  },
} as const satisfies Record<string, { title: string; steps: readonly string[] }>

export type InstallGuideKey = keyof typeof INSTALL_GUIDES

/** 기기를 모를 때 모바일에 보일 묶음 (Install) */
export const MOBILE_GUIDE_KEYS = ['iphone', 'android'] as const satisfies readonly InstallGuideKey[]
/** 기기를 모를 때 태블릿 · 데스크톱에 보일 묶음 (Install-T · -D) */
export const WIDE_GUIDE_KEYS = ['apple', 'browser'] as const satisfies readonly InstallGuideKey[]

/**
 * 기기에 맞는 묶음 하나. 모르는 기기(`other`)이거나 아직 판별 전(`null`, 서버 · 하이드레이션 첫 그림)이면 null 이다 —
 * 그때는 화면 폭에 맞는 두 묶음을 다 보인다.
 */
export function guideFor(platform: DevicePlatform | null): InstallGuideKey | null {
  switch (platform) {
    case 'iphone':
      return 'iphone'
    case 'android':
      return 'android'
    case 'ipad':
    case 'mac-safari':
      return 'apple'
    case 'chromium':
      return 'browser'
    default:
      return null
  }
}
