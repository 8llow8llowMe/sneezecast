import { renderAppIcon } from '@/features/pwa/app-icon'

// iOS 홈 화면 아이콘. Next 가 <link rel="apple-touch-icon" sizes="180x180"> 를 넣고 빌드 때 한 번 그린다
export const size = { width: 180, height: 180 }
export const contentType = 'image/png'

export default function AppleIcon() {
  return renderAppIcon(size.width, 'apple')
}
