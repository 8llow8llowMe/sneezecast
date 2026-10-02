import { findManifestIcon, MANIFEST_ICONS, renderAppIcon } from '@/features/pwa/app-icon'

/**
 * 웹 앱 매니페스트 아이콘(`/app-icons/icon-192.png` 등). 목록(`MANIFEST_ICONS`)에 있는 파일만 빌드 때 그린다.
 * Next 의 `icon` 파일 규칙은 모든 크기를 `<link rel="icon">` 으로 내보내 maskable 까지 탭 아이콘 후보가 되므로
 * 매니페스트 아이콘은 이 라우트로 따로 둔다.
 */
export const dynamicParams = false

export function generateStaticParams() {
  return MANIFEST_ICONS.map((icon) => ({ file: icon.file }))
}

// 전역 RouteContext 는 `next typegen` 뒤에야 생겨 typegen 전에 도는 린트가 타입을 모른다. 모양을 직접 적는다
export async function GET(_request: Request, { params }: { params: Promise<{ file: string }> }) {
  const { file } = await params
  const icon = findManifestIcon(file)
  // dynamicParams = false 라 Next 가 먼저 404 를 낸다. 목록 밖 이름으로 불려도 그리지 않는다
  if (!icon) return new Response(null, { status: 404 })
  return renderAppIcon(icon.size, icon.purpose)
}
