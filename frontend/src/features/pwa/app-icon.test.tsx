import { describe, expect, it } from 'vitest'

import { PNG_SIGNATURE, readPng } from '@/test/png'

import {
  type AppIconVariant,
  findManifestIcon,
  MASKABLE_SAFE_RADIUS,
  renderAppIcon,
  thermometerShapes,
} from './app-icon'

describe('앱 아이콘 도형', () => {
  const variants: AppIconVariant[] = ['any', 'maskable', 'apple']

  it.each(variants)('%s 도형이 아이콘 안에 들어가고 가로 가운데에 놓인다', (variant) => {
    const size = 512
    const shapes = thermometerShapes(size, variant)
    const left = Math.min(...shapes.map((rect) => rect.left))
    const right = Math.max(...shapes.map((rect) => rect.left + rect.width))
    const top = Math.min(...shapes.map((rect) => rect.top))
    const bottom = Math.max(...shapes.map((rect) => rect.top + rect.height))

    expect(left).toBeGreaterThan(0)
    expect(top).toBeGreaterThan(0)
    expect(right).toBeLessThan(size)
    expect(bottom).toBeLessThan(size)
    expect((left + right) / 2).toBeCloseTo(size / 2, 6)
    expect((top + bottom) / 2).toBeCloseTo(size / 2, 6)
  })

  it('maskable 도형은 조각의 네 모서리까지 안전 영역(가운데 지름 80% 원) 안에 있다', () => {
    const size = 512
    const center = size / 2
    const limit = size * MASKABLE_SAFE_RADIUS
    const corners = thermometerShapes(size, 'maskable').flatMap((rect) => [
      [rect.left, rect.top],
      [rect.left + rect.width, rect.top],
      [rect.left, rect.top + rect.height],
      [rect.left + rect.width, rect.top + rect.height],
    ])
    for (const [x = 0, y = 0] of corners) {
      expect(Math.hypot(x - center, y - center)).toBeLessThanOrEqual(limit)
    }
  })

  it('목록에 없는 파일 이름은 찾지 않는다', () => {
    expect(findManifestIcon('icon-192.png')).toEqual({
      file: 'icon-192.png',
      size: 192,
      purpose: 'any',
    })
    expect(findManifestIcon('icon-1024.png')).toBeUndefined()
    expect(findManifestIcon('../icon-192.png')).toBeUndefined()
  })
})

describe('renderAppIcon', () => {
  it('요청한 크기의 PNG 를 돌려준다', async () => {
    const response = renderAppIcon(192, 'maskable')

    expect(response.headers.get('content-type')).toBe('image/png')
    expect(await readPng(response)).toEqual({ signature: PNG_SIGNATURE, width: 192, height: 192 })
  })
})
