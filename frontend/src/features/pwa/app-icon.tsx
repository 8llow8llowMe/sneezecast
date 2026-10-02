import { ImageResponse } from 'next/og'

import { BRAND_COLOR, THEME_COLOR } from '@/styles/theme-color'

/**
 * **임시 앱 아이콘.** 저장소에 앱 아이콘 · 로고 시안이 없어 브랜드 색(`color.brand`) 바탕에 흰 체온계 도형을 그린다.
 * 시안이 오면 이 파일의 그림(`AppIconArt`)만 바꾸면 파비콘 · iOS 홈 화면 · 매니페스트 아이콘이 모두 바뀐다
 * (docs/design/SCREENS.md "앱 매니페스트 · 아이콘").
 *
 * 바이너리 PNG 를 저장소에 넣지 않고 빌드 때 그린다(`next/og` 의 `ImageResponse`, 빌드 때 한 번 그려 정적 파일로 둔다).
 * 한글 글자는 글꼴을 따로 불러와야 해 도형만 쓴다.
 */

/**
 * 아이콘 모양.
 * - `any`: 탭 · 데스크톱 앱 목록. 모서리를 둥글게 깎고(바깥은 투명) 그림을 크게 둔다.
 * - `maskable`: Android 런처가 원 · 둥근 사각형으로 잘라 쓴다. 바탕을 끝까지 칠하고 그림을 안전 영역(가운데 지름 80% 원) 안에 둔다.
 * - `apple`: iOS 홈 화면. iOS 가 모서리를 직접 깎아 바탕을 끝까지 칠한다(투명하면 검게 보인다).
 */
export type AppIconVariant = 'any' | 'maskable' | 'apple'

type VariantStyle = {
  /** 체온계 높이 / 아이콘 한 변 */
  glyph: number
  /** 바탕 모서리 반지름 / 아이콘 한 변 */
  corner: number
}

const VARIANT_STYLE: Record<AppIconVariant, VariantStyle> = {
  any: { glyph: 0.7, corner: 0.22 },
  maskable: { glyph: 0.5, corner: 0 },
  apple: { glyph: 0.62, corner: 0 },
}

/** maskable 아이콘의 안전 영역 반지름 / 아이콘 한 변 (W3C Web App Manifest "maskable") */
export const MASKABLE_SAFE_RADIUS = 0.4

export type IconRect = {
  left: number
  top: number
  width: number
  height: number
  /** 모서리 반지름 */
  radius: number
}

/**
 * 체온계 도형 조각(관 · 구 · 눈금 셋). 값은 픽셀이다.
 * 눈금이 관 오른쪽으로 나와 도형 전체 폭의 가운데를 아이콘 가운데에 맞춘다.
 */
export function thermometerShapes(size: number, variant: AppIconVariant): IconRect[] {
  const height = size * VARIANT_STYLE[variant].glyph
  const bulb = height * 0.42
  const stemWidth = height * 0.22
  const tickGap = height * 0.06
  const tickLength = height * 0.14
  const tickThickness = height * 0.05

  // 관 가운데를 0 으로 둔 가로 범위: 왼쪽은 구의 반지름, 오른쪽은 눈금 끝
  const leftExtent = bulb / 2
  const rightExtent = stemWidth / 2 + tickGap + tickLength
  const axis = size / 2 - (rightExtent - leftExtent) / 2
  const top = (size - height) / 2

  const stem: IconRect = {
    left: axis - stemWidth / 2,
    top,
    width: stemWidth,
    height: height - bulb / 2,
    radius: stemWidth / 2,
  }
  const bulbRect: IconRect = {
    left: axis - bulb / 2,
    top: top + height - bulb,
    width: bulb,
    height: bulb,
    radius: bulb / 2,
  }
  const ticks = [0.18, 0.34, 0.5].map((at): IconRect => ({
    left: axis + stemWidth / 2 + tickGap,
    top: top + height * at - tickThickness / 2,
    width: tickLength,
    height: tickThickness,
    radius: tickThickness / 2,
  }))
  return [stem, bulbRect, ...ticks]
}

function AppIconArt({ size, variant }: { size: number; variant: AppIconVariant }) {
  return (
    <div
      style={{
        display: 'flex',
        position: 'relative',
        width: '100%',
        height: '100%',
        background: BRAND_COLOR,
        borderRadius: size * VARIANT_STYLE[variant].corner,
      }}
    >
      {thermometerShapes(size, variant).map((rect, index) => (
        <div
          // 고정 순서의 도형 조각이라 순번을 key 로 쓴다
          key={index}
          style={{
            position: 'absolute',
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height,
            borderRadius: rect.radius,
            // 흰색은 color.bg(= THEME_COLOR)다
            background: THEME_COLOR,
          }}
        />
      ))}
    </div>
  )
}

/** 아이콘 PNG 응답. 아이콘 라우트(`app/icon.tsx` · `app/apple-icon.tsx` · `app/app-icons/[file]/route.ts`)가 부른다 */
export function renderAppIcon(size: number, variant: AppIconVariant): ImageResponse {
  return new ImageResponse(<AppIconArt size={size} variant={variant} />, {
    width: size,
    height: size,
  })
}

/** 매니페스트 아이콘 주소의 앞부분. `app/app-icons/[file]/route.ts` 의 폴더 이름과 같아야 한다 */
export const MANIFEST_ICON_BASE = '/app-icons'

export type ManifestIcon = {
  file: string
  size: number
  purpose: 'any' | 'maskable'
}

/**
 * 매니페스트 아이콘. Chrome 설치 조건이 192 · 512 를 요구하고, Android 런처는 maskable 을 쓴다.
 * any 와 maskable 을 한 항목(`purpose: 'any maskable'`)으로 묶지 않는다 — 안전 영역 여백 때문에 any 로 쓰일 때 그림이 작다.
 */
export const MANIFEST_ICONS: readonly ManifestIcon[] = [
  { file: 'icon-192.png', size: 192, purpose: 'any' },
  { file: 'icon-512.png', size: 512, purpose: 'any' },
  { file: 'maskable-192.png', size: 192, purpose: 'maskable' },
  { file: 'maskable-512.png', size: 512, purpose: 'maskable' },
]

export function findManifestIcon(file: string): ManifestIcon | undefined {
  return MANIFEST_ICONS.find((icon) => icon.file === file)
}
