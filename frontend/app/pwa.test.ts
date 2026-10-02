import { describe, expect, it } from 'vitest'

import { MANIFEST_ICONS } from '@/features/pwa/app-icon'
import { PNG_SIGNATURE, readPng } from '@/test/png'

import tokens from '../docs/design/tokens.json'
import { GET as getManifestIcon } from './app-icons/[file]/route'
import AppleIcon, { size as appleIconSize } from './apple-icon'
import Icon, { size as iconSize } from './icon'
import { metadata, viewport } from './layout'
import manifest from './manifest'

function iconRequest(file: string) {
  return getManifestIcon(new Request(`http://localhost/app-icons/${file}`), {
    params: Promise.resolve({ file }),
  })
}

describe('웹 앱 매니페스트', () => {
  const result = manifest()

  it('홈 화면 앱(standalone)으로 사이트 전체를 연다', () => {
    expect(result).toMatchObject({
      id: '/',
      name: '우리동네체온계',
      short_name: '동네체온계',
      lang: 'ko',
      start_url: '/',
      scope: '/',
      display: 'standalone',
    })
    expect(result.description).toBe(metadata.description)
    // 태블릿 · 데스크톱은 가로 화면이라 방향을 고정하지 않는다
    expect(result.orientation).toBeUndefined()
  })

  it('색은 화면 바탕 토큰(color.bg) · 주소창 색과 같다', () => {
    expect(result.background_color?.toLowerCase()).toBe(tokens.color.bg.toLowerCase())
    expect(result.theme_color).toBe(viewport.themeColor)
  })

  it('192 · 512 아이콘을 any 와 maskable 로 따로 둔다', () => {
    expect(
      result.icons?.map((icon) => `${icon.sizes ?? ''} ${icon.purpose ?? ''} ${icon.type ?? ''}`),
    ).toEqual([
      '192x192 any image/png',
      '512x512 any image/png',
      '192x192 maskable image/png',
      '512x512 maskable image/png',
    ])
  })

  it.each(MANIFEST_ICONS)('$file 주소가 그 크기의 PNG 를 돌려준다', async (icon) => {
    const entry = manifest().icons?.find((item) => item.src === `/app-icons/${icon.file}`)
    expect(entry, `${icon.file} 가 매니페스트에 없음`).toBeDefined()

    const response = await iconRequest(icon.file)
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('image/png')
    expect(await readPng(response)).toEqual({
      signature: PNG_SIGNATURE,
      width: icon.size,
      height: icon.size,
    })
  })

  it('목록에 없는 아이콘 이름은 404 다', async () => {
    const response = await iconRequest('icon-1024.png')
    expect(response.status).toBe(404)
  })
})

describe('파비콘 · iOS 홈 화면 아이콘', () => {
  it('파비콘은 32 PNG 다', async () => {
    expect(iconSize).toEqual({ width: 32, height: 32 })
    expect(await readPng(Icon())).toEqual({ signature: PNG_SIGNATURE, width: 32, height: 32 })
  })

  it('apple-touch-icon 은 180 PNG 다', async () => {
    expect(appleIconSize).toEqual({ width: 180, height: 180 })
    expect(await readPng(AppleIcon())).toEqual({
      signature: PNG_SIGNATURE,
      width: 180,
      height: 180,
    })
  })
})

describe('iOS 홈 화면 앱 메타데이터', () => {
  it('홈 화면 앱으로 열고, 이름은 short_name 과 같고, 상태 표시줄은 흰 바탕이다', () => {
    expect(metadata.appleWebApp).toEqual({
      capable: true,
      title: manifest().short_name,
      statusBarStyle: 'default',
    })
  })
})
