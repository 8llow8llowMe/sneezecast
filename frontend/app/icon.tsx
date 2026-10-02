import { renderAppIcon } from '@/features/pwa/app-icon'

// 파비콘(브라우저 탭). Next 가 <link rel="icon" sizes="32x32"> 를 넣고 빌드 때 한 번 그린다
export const size = { width: 32, height: 32 }
export const contentType = 'image/png'

export default function Icon() {
  return renderAppIcon(size.width, 'any')
}
